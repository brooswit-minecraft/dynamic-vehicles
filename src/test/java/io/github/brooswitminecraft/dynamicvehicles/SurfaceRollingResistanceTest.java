package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.brooswitminecraft.dynamicterrain.SurfaceMath;
import io.github.brooswitminecraft.dynamicterrain.SurfaceOverrides;
import io.github.brooswitminecraft.dynamicterrain.SurfaceProperties;

/**
 * MINECRAFT-450: rolling resistance exercised at the real Dynamic Terrain seam, as far as a plain JUnit
 * unit test can reach it. {@link SurfaceMath}, {@link SurfaceProperties} and {@link
 * SurfaceOverrides#resolve} are all pure (no {@code net.minecraft}/{@code BlockGetter} types), so these
 * tests call the REAL classes {@link io.github.brooswitminecraft.dynamicterrain.Surfaces#at} itself
 * delegates to - not a reimplementation or hand-rolled stand-in for them - composed exactly the way {@link
 * SableCarBody#tick} composes {@code Surfaces.at()}'s result with {@link WheelMath#rollingCoefficient} and
 * {@link WheelMath#tire}.
 *
 * <p><b>Known, confirmed gap this test class does NOT cover</b> (documented per the ticket, not silently
 * accepted): {@code Surfaces.at(BlockGetter, BlockPos)} itself - the actual block lookup, and therefore
 * {@code SableCarBody.tick}'s own raycast-to-{@code SurfaceProperties} resolution - cannot be driven from
 * this plain unit test. This repo's {@code test} sourceSet has no path to any {@code net.minecraft}/
 * NeoForge class at all (confirmed two ways: (1) the MINECRAFT-117 history in this repo, which tried
 * ModDevGradle's {@code neoForge.unitTest} mode specifically to get registry-backed Minecraft types into
 * JUnit and reverted it after a real CI failure, leaving {@code DispatcherProfessionTest}'s own javadoc
 * recording "this repo's test sourceSet has no path to vanilla/NeoForge classes"; (2) a throwaway probe in
 * this ticket's own branch that tried to {@code import net.minecraft.core.BlockPos} etc. from {@code
 * src/test} and failed {@code compileTestJava} with "package net.minecraft.core does not exist" for every
 * such import). So a hand-rolled {@code BlockGetter} fake - the approach this ticket's own brief
 * suggested - cannot even be compiled here, let alone exercised: {@code BlockGetter} itself is one of the
 * unresolvable types. The only place in this codebase that CAN drive a real raycast against a real block
 * and a real {@code Surfaces.at} call is a {@code @GameTest} (see {@code EnvironmentGameTests} for the
 * established pattern) running under {@code ./gradlew runGameTestServer} - a separate CI gate from {@code
 * ./gradlew test} this ticket's brief asked to keep green. Adding a new GameTest was judged out of scope
 * for a verification ticket whose own hard rules say "zero production code changes... is a valid, good
 * outcome" and ask for tests to be iterated against {@code ./gradlew test} specifically; it is called out
 * here, explicitly, as a remaining gap rather than silently declared covered.
 */
class SurfaceRollingResistanceTest {
    private static final double N = 2943.0; // a quarter of a 1200 kg car at rest, this repo's own test convention
    private static final double DT = 0.05;
    private static final double EFFECTIVE_MASS = WheelMath.EFFECTIVE_MASS;

    // --- AC2: terrain coefficients are data-driven via SurfaceOverrides/SurfaceMath, with a documented
    // override-wins-over-family-wins-over-nothing fallback chain -----------------------------------------

    @Test
    void surfaceMathInfersDifferentRollingResistanceForDifferentMaterialFamilies() {
        SurfaceProperties stone = SurfaceMath.infer(SurfaceMath.Family.STONE, SurfaceMath.NORMAL_FRICTION, 1.5, 6.0);
        SurfaceProperties sand = SurfaceMath.infer(SurfaceMath.Family.SAND, SurfaceMath.NORMAL_FRICTION, 0.5, 0.5);
        assertTrue(sand.rollingResistance() > stone.rollingResistance(),
                "sand's inferred rollingResistance must be higher than stone's");
        assertTrue(sand.deformability() > stone.deformability(), "sand must infer as more deformable than stone");
    }

    @Test
    void surfaceOverridesResolveHasNoDeclarationByDefaultSoCallersFallBackToSurfaceMath() {
        // Without SurfaceOverrideLoader ever loading anything (no config in this unit test), the static
        // table backing SurfaceOverrides.resolve starts empty - this IS the fallback chain Surfaces.at
        // relies on ("declared override wins; otherwise SurfaceMath.infer"), exercised directly.
        Optional<SurfaceProperties> declared = SurfaceOverrides.resolve("minecraft:stone", "STONE");
        assertTrue(declared.isEmpty(), "with no overrides loaded, resolve() must report nothing declared, "
                + "so Surfaces.at's caller falls back to SurfaceMath.infer rather than silently getting a stale/default value");
    }

    // --- AC2/AC3/AC4: rollingCoefficient composed from a real SurfaceProperties, exactly as SableCarBody.tick does ---

    private static double coefficientFor(SurfaceProperties surface, double rollingScale) {
        return WheelMath.rollingCoefficient(surface.rollingResistance(), surface.deformability(), rollingScale);
    }

    @Test
    void pavementRollingCoefficientMatchesItsOwnRollingResistanceAtNeutralScale() {
        // SurfaceProperties.PAVEMENT has deformability 0.0, so rollingCoefficient must reduce to exactly
        // rollingResistance * rollingScale with no deformability gain added - the "pavement is the
        // reference surface" contract SurfaceProperties itself documents.
        double coefficient = coefficientFor(SurfaceProperties.PAVEMENT, 1.0);
        assertEquals(SurfaceProperties.PAVEMENT.rollingResistance(), coefficient, 1e-12);
    }

    @Test
    void coastDownContrastsBetweenTwoRealSurfacesAtNeutralRollingScale() {
        SurfaceProperties pavement = SurfaceProperties.PAVEMENT;
        SurfaceProperties sand = SurfaceMath.infer(SurfaceMath.Family.SAND, SurfaceMath.NORMAL_FRICTION, 0.5, 0.5);
        double pavementCoefficient = coefficientFor(pavement, 1.0);
        double sandCoefficient = coefficientFor(sand, 1.0);

        double vPavement = 10.0;
        double vSand = 10.0;
        for (int i = 0; i < 40; i++) {
            vPavement += WheelMath.tire(vPavement, 0, N, WheelMath.BASE_FRICTION, pavementCoefficient, 1, 0, 0, DT)
                    .longitudinal() / EFFECTIVE_MASS * DT;
            vSand += WheelMath.tire(vSand, 0, N, WheelMath.BASE_FRICTION, sandCoefficient, 1, 0, 0, DT)
                    .longitudinal() / EFFECTIVE_MASS * DT;
        }
        assertTrue(vSand < vPavement, "coasting on real sand-inferred properties must lose more speed than real pavement: "
                + "pavement=" + vPavement + " sand=" + vSand);
    }

    @Test
    void perVehicleRollingScaleChangesTheRealSurfacesCoefficientProportionally() {
        // VehicleSpec.rollingScale's own documented contract: a vehicle tuned below the neutral 1.0 (e.g.
        // ROCK_CRAWLER's 0.5, MONSTER_TRUCK's 0.55) must roll easier than a vehicle at 1.0 on the SAME
        // real surface - checked against VehicleSpec.CAR/ROCK_CRAWLER's own shipped numbers, not invented
        // ones, so this also pins that those constants still mean what their javadoc says.
        SurfaceProperties sand = SurfaceMath.infer(SurfaceMath.Family.SAND, SurfaceMath.NORMAL_FRICTION, 0.5, 0.5);
        double carCoefficient = coefficientFor(sand, VehicleSpec.CAR.rollingScale());
        double crawlerCoefficient = coefficientFor(sand, VehicleSpec.ROCK_CRAWLER.rollingScale());
        assertTrue(crawlerCoefficient < carCoefficient,
                "ROCK_CRAWLER's lower rollingScale must give a lower coefficient than CAR's on the same surface");
        assertEquals(carCoefficient * (VehicleSpec.ROCK_CRAWLER.rollingScale() / VehicleSpec.CAR.rollingScale()),
                crawlerCoefficient, 1e-9, "rollingScale must multiply the coefficient proportionally, not clamp or floor it");
    }

    // --- AC5: rolling resistance is not double-counted against the drift-tire path, which must reuse the
    // exact same rollingCoefficient term rather than recomputing or re-applying it -------------------------

    @Test
    void driftTireModelAppliesTheSameRollingResistanceAsThePlainTireWhenNotSliding() {
        SurfaceProperties sand = SurfaceMath.infer(SurfaceMath.Family.SAND, SurfaceMath.NORMAL_FRICTION, 0.5, 0.5);
        double coefficient = coefficientFor(sand, VehicleSpec.DRIFT.rollingScale());
        double vLong = 10.0;
        double mu = WheelMath.BASE_FRICTION;

        WheelMath.Tire plain = WheelMath.tire(vLong, 0, N, mu, coefficient, 1, 0, 0, DT);
        WheelMath.Tire drift = DriftTireModel.tire(vLong, 0, N, mu, coefficient, 1, 0, 0, 1.0, EFFECTIVE_MASS, DT,
                VehicleSpec.DRIFT.tireTuning(), true, false, 0.0);

        // Front wheel, no lateral slip, no handbrake: DriftTireModel's own mu adjustments are all no-ops
        // here (front grip is untouched by the rear split; no slip means no falloff), so the two calls must
        // deliver byte-for-byte the same rolling-resistance-driven longitudinal force - proof the drift path
        // reuses WheelMath's rolling resistance rather than adding its own on top.
        assertEquals(plain.longitudinal(), drift.longitudinal(), 1e-9,
                "DriftTireModel must not add a second, independent rolling-resistance contribution on top of WheelMath's own");
    }
}
