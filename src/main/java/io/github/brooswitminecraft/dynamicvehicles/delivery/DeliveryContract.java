package io.github.brooswitminecraft.dynamicvehicles.delivery;

import java.util.Objects;
import java.util.UUID;

/**
 * The plain, per-player contract record (MINECRAFT-110 AC1): everything
 * {@link ContractBook} needs to enforce one-active-contract (AC2), evaluate
 * expiry and arrival (AC5-7), and round-trip to persistence (AC3), with no
 * Minecraft type anywhere in it. The destination is recorded as the
 * recovered {@link ConfirmedVillage#startBlockPos()} coordinates - the tight
 * AC6 arrival target - never the placement region.
 *
 * @param playerId              the contract holder
 * @param sourceVillagerId      the Dispatcher villager's entity id this contract was accepted from (AC1's "source identity")
 * @param destinationDimension the destination's {@code ResourceKey<Level>} location, as a plain string (e.g. {@code "minecraft:overworld"})
 * @param destinationRegionX   the destination {@link VillageIdentity}'s region X (AC1's "destination identity")
 * @param destinationRegionZ   the destination {@link VillageIdentity}'s region Z
 * @param destinationStartX    the recovered village start position's X (AC6's tight arrival target)
 * @param destinationStartY    the recovered village start position's Y
 * @param destinationStartZ    the recovered village start position's Z
 * @param danger                this contract's rolled danger value
 * @param reward                this contract's reward, paid out on completion
 * @param acceptedAtTick        the server game tick the contract was accepted on
 * @param deadlineTick          the server game tick after which the contract is expired (exclusive: expired when {@code now > deadlineTick})
 */
public record DeliveryContract(
        UUID playerId,
        UUID sourceVillagerId,
        String destinationDimension,
        int destinationRegionX,
        int destinationRegionZ,
        long destinationStartX,
        long destinationStartY,
        long destinationStartZ,
        double danger,
        double reward,
        long acceptedAtTick,
        long deadlineTick
) {
    public DeliveryContract {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(sourceVillagerId, "sourceVillagerId");
        Objects.requireNonNull(destinationDimension, "destinationDimension");
        if (deadlineTick <= acceptedAtTick) {
            throw new IllegalArgumentException(
                    "deadlineTick must be after acceptedAtTick: " + deadlineTick + " <= " + acceptedAtTick);
        }
    }
}
