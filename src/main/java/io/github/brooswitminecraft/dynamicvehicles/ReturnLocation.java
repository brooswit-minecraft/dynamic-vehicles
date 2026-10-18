package io.github.brooswitminecraft.dynamicvehicles;

import java.util.Objects;

/**
 * The plain, per-player "where to go back to" record for the flat-dimension
 * there-and-back command (MINECRAFT-397): a departure dimension and exact
 * position/facing, with no Minecraft type anywhere in it so the persistence
 * round trip is directly unit-testable, following
 * {@code DeliveryContract}/{@code DeliveryContractCodec}'s pattern.
 * {@link ReturnLocationStorage} mirrors this same field list and order
 * against real {@code CompoundTag} for the actual saved-data path.
 *
 * @param dimension the departure {@code ResourceKey<Level>} location, as a plain string (e.g. {@code "minecraft:overworld"})
 */
public record ReturnLocation(String dimension, double x, double y, double z, float yaw, float pitch) {
    public ReturnLocation {
        Objects.requireNonNull(dimension, "dimension");
    }
}
