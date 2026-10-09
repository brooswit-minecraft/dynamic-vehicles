package io.github.brooswitminecraft.dynamicvehicles;

import java.util.Set;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;

/**
 * The Minecraft-facing half of mob auto-boarding eligibility (MINECRAFT-211): which mob types and which
 * vehicles are on the allow-list, read off a live {@code Mob}, and handed to {@link MobBoardingEligibility}
 * (pure, separately unit tested) for the actual decision. Both the contact trigger
 * ({@code CarEntity.push}) and the final boarding gate ({@code CarEntity.canAddPassenger}) call
 * {@link #canAutoBoard(Mob, VehicleSpec)}, so eligibility is never duplicated or allowed to drift between
 * the two call sites. Extending to more mob types or vehicles later means growing
 * {@link #AUTO_BOARDING_MOB_TYPES} or {@link #AUTO_BOARDING_VEHICLES} -- nothing else in this class.
 *
 * <p>Not unit tested itself: {@code EntityType.COW} needs the game's registry bootstrap just to load this
 * class, which this test environment does not have (same as {@code CarEntity}, never unit tested either)
 * -- see {@link MobBoardingEligibility}'s javadoc for why the actual filter lives there instead.
 */
public final class MobBoardingRules {

    /** Mob types that may auto-board on contact. Only the cow, for this slice. */
    private static final Set<EntityType<?>> AUTO_BOARDING_MOB_TYPES = Set.of(EntityType.COW);

    /** Vehicle specs that accept an auto-boarding mob. Only the bus, for this slice. */
    private static final Set<VehicleSpec> AUTO_BOARDING_VEHICLES = Set.of(VehicleSpec.BUS);

    private MobBoardingRules() {}

    /** Whether {@code mob} may automatically board a vehicle built from {@code spec} right now. */
    public static boolean canAutoBoard(Mob mob, VehicleSpec spec) {
        return MobBoardingEligibility.isEligible(AUTO_BOARDING_MOB_TYPES.contains(mob.getType()),
                AUTO_BOARDING_VEHICLES.contains(spec), mob.isBaby(), mob.isLeashed(), mob instanceof Enemy);
    }
}
