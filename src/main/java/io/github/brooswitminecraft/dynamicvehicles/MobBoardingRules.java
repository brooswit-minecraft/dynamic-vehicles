package io.github.brooswitminecraft.dynamicvehicles;

import java.util.Set;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;

/**
 * Single place deciding which mobs may automatically board which vehicles on contact (MINECRAFT-211).
 * Both the contact trigger ({@code CarEntity.push}) and the final boarding gate
 * ({@code CarEntity.canAddPassenger}) call {@link #canAutoBoard(Mob, VehicleSpec)}, so eligibility is
 * never duplicated or allowed to drift between the two call sites. Extending to more mob types or
 * vehicles later means growing {@link #AUTO_BOARDING_MOB_TYPES} or {@link #AUTO_BOARDING_VEHICLES} --
 * nothing else in this class.
 *
 * <p>{@link #isEligible} is the actual filter, pure and Minecraft-entity-free so it can be unit tested
 * directly; {@link #canAutoBoard} is the thin Minecraft-facing shell that reads a live {@code Mob} and
 * delegates to it (not unit tested, same as {@code CarEntity} itself -- no Minecraft/NeoForge bootstrap is
 * available in this test environment).
 */
public final class MobBoardingRules {

    /** Mob types that may auto-board on contact. Only the cow, for this slice. */
    private static final Set<EntityType<?>> AUTO_BOARDING_MOB_TYPES = Set.of(EntityType.COW);

    /** Vehicle specs that accept an auto-boarding mob. Only the bus, for this slice. */
    private static final Set<VehicleSpec> AUTO_BOARDING_VEHICLES = Set.of(VehicleSpec.BUS);

    private MobBoardingRules() {}

    /**
     * Whether {@code mob} may automatically board a vehicle built from {@code spec} right now. Reads the
     * mob's type, vehicle membership, and baby/leashed/hostile state, then defers to {@link #isEligible}
     * for the actual decision.
     */
    public static boolean canAutoBoard(Mob mob, VehicleSpec spec) {
        return isEligible(AUTO_BOARDING_MOB_TYPES.contains(mob.getType()), AUTO_BOARDING_VEHICLES.contains(spec),
                mob.isBaby(), mob.isLeashed(), mob instanceof Enemy);
    }

    /**
     * The actual eligibility filter the ticket calls for, kept in this one spot: the mob's type and the
     * vehicle must both be in the auto-boarding allow-list, and the mob must be an adult, not leashed, and
     * not hostile.
     */
    public static boolean isEligible(boolean isAutoBoardingMobType, boolean isAutoBoardingVehicle,
            boolean isBaby, boolean isLeashed, boolean isHostile) {
        return isAutoBoardingMobType && isAutoBoardingVehicle && !isBaby && !isLeashed && !isHostile;
    }
}
