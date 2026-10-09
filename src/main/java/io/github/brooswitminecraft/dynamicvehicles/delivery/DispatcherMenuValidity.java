package io.github.brooswitminecraft.dynamicvehicles.delivery;

/**
 * The Minecraft-free part of MINECRAFT-110 AC12's {@code stillValid}
 * predicate: given that the caller has already looked up whether the
 * Dispatcher villager is alive, still carries the {@code dispatcher}
 * profession, and is in the same level as the player, this is just the
 * boolean combination plus the distance check against
 * {@link #INTERACTION_RANGE_BLOCKS} - "still exists" and "still a
 * Dispatcher" themselves require a live {@code Villager} reference and are
 * not expressible without Minecraft types, so {@link DispatcherOfferMenu}
 * resolves those live and passes only plain values in here.
 */
public final class DispatcherMenuValidity {

    /**
     * "Normal interaction range": mirrors vanilla's own default for
     * block-anchored container menus,
     * {@code AbstractContainerMenu#stillValid(ContainerLevelAccess, Player, Block)},
     * which refuses beyond 8 blocks from the anchor.
     */
    public static final double INTERACTION_RANGE_BLOCKS = 8.0;

    private DispatcherMenuValidity() {
    }

    /**
     * @param dispatcherAlive          the Dispatcher villager still exists and is alive
     * @param hasDispatcherProfession  the Dispatcher villager still carries the {@code dispatcher} profession
     * @param sameLevel                 the Dispatcher villager is still in the same level/dimension as the player
     * @param distanceSquaredToPlayer  squared distance, in blocks, between the Dispatcher villager and the player
     */
    public static boolean isValid(boolean dispatcherAlive, boolean hasDispatcherProfession, boolean sameLevel, double distanceSquaredToPlayer) {
        return dispatcherAlive && hasDispatcherProfession && sameLevel
                && distanceSquaredToPlayer <= INTERACTION_RANGE_BLOCKS * INTERACTION_RANGE_BLOCKS;
    }
}
