package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * MINECRAFT-171/183: old-format saved vehicles and spawn items under the multi-seat specs MINECRAFT-170
 * introduced for {@code drift_car}/{@code car}/{@code trophy_truck}/{@code truck}.
 *
 * <p>Verified here, not just by reading the code: before this change, {@code CarEntity}'s saved NBT held
 * only {@code OrientationX/Y/Z/W} (see {@link OrientationNbt}, extracted from {@code
 * CarEntity.readAdditionalSaveData} so it can run without the Minecraft/NeoForge bootstrap a {@code
 * CompoundTag} or {@code CarEntity} instance would need) &mdash; no seat count or layout was ever persisted.
 * {@code CarEntity}'s {@code seats} field (a {@link SeatAssignment}) is instead always built fresh from
 * {@code spec.seatCount()} in the constructor (see {@code CarEntity(EntityType, Level)}), so an old
 * single-seat-era entity loading under the new multi-seat spec gets that spec's full seat count with no
 * extra wiring, regardless of what its orientation tag does or doesn't contain. This test exercises exactly
 * that: the pure NBT-parsing seam with old-format inputs, and the pure {@code SeatAssignment}/{@code
 * VehicleSpec} seat construction a loaded entity would get, together covering the OLD-format-NBT -&gt;
 * NEW-code path without needing a live {@code CarEntity}.
 *
 * <p>NOT verified here: in-game loading of a real pre-MINECRAFT-170 world save (no Minecraft bootstrap is
 * available in this test environment) &mdash; see the PR description.
 */
class MultiSeatMigrationCompatTest {

    private static final List<VehicleSpec> MULTI_SEAT_SPECS =
            List.of(VehicleSpec.DRIFT, VehicleSpec.CAR, VehicleSpec.TROPHY, VehicleSpec.TRUCK);

    /** Pre-MINECRAFT-170 NBT: only the orientation quaternion, exactly as every vehicle used to save itself. */
    @Test
    void oldFormatOrientationOnlyTagIsReadWithoutError() {
        OrientationNbt.Orientation orientation = OrientationNbt.read(true, 0.1f, 0.2f, 0.3f, 0.9f);
        assertEquals(new OrientationNbt.Orientation(0.1f, 0.2f, 0.3f, 0.9f), orientation);
    }

    /** Even older/never-synced NBT: no orientation keys at all (e.g. a vehicle that never left the ground). */
    @Test
    void emptyOrMissingOrientationTagIsReadWithoutErrorAndYieldsNoOrientation() {
        assertNull(OrientationNbt.read(false, 0.0f, 0.0f, 0.0f, 0.0f));
    }

    /**
     * The actual migration guarantee: whatever an old tag's orientation keys say (or don't), a loaded
     * entity's seats come from its spec alone, sized to the spec's full (new) seat count with seat 0 as the
     * driver &mdash; {@code SeatAssignment} is never populated from NBT, old or new.
     */
    @Test
    void anOldEntityLoadingUnderANewMultiSeatSpecGetsTheSpecsFullSeatCountWithSeatZeroAsDriver() {
        for (VehicleSpec spec : MULTI_SEAT_SPECS) {
            // Mirrors CarEntity's constructor: `new SeatAssignment<>(spec.seatCount())`, built fresh at
            // construction time regardless of anything (or nothing) the old save data says.
            SeatAssignment<Object> seats = new SeatAssignment<>(spec.seatCount());
            assertEquals(spec.seatCount(), seats.seatCount());

            Object driver = new Object();
            assertEquals(0, seats.add(driver), "first boarder always takes seat 0, the driver's seat");
            assertEquals(driver, seats.driver());
        }
    }

    @Test
    void driftCarHasTwoSeats() {
        assertEquals(2, VehicleSpec.DRIFT.seatCount());
    }

    @Test
    void carHasFourSeats() {
        assertEquals(4, VehicleSpec.CAR.seatCount());
    }

    @Test
    void trophyTruckHasTwoSeats() {
        assertEquals(2, VehicleSpec.TROPHY.seatCount());
    }

    @Test
    void truckHasFourSeats() {
        assertEquals(4, VehicleSpec.TRUCK.seatCount());
    }

    /**
     * Pins the four spawn-item/entity ids old worlds hold saved vehicles and items under
     * ({@code VehicleIds}, consumed by {@code DynamicVehiclesMod}'s own registration calls) &mdash; a rename
     * of any of them would silently orphan old data, so this must fail loudly instead.
     */
    @Test
    void theFourMultiSeatVehicleIdsAreUnchanged() {
        assertEquals("drift_car", VehicleIds.DRIFT_CAR);
        assertEquals("car", VehicleIds.CAR);
        assertEquals("trophy_truck", VehicleIds.TROPHY_TRUCK);
        assertEquals("truck", VehicleIds.TRUCK);
    }
}
