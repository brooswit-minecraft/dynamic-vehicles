package io.github.brooswitminecraft.dynamicvehicles.delivery;

/**
 * The plain field list a client needs to render one offer row (MINECRAFT-109
 * AC2-5): everything {@link OfferDistanceFormat} and {@link DangerGauge} need,
 * extracted into a Minecraft-free record so the menu/packet payload's field
 * list has a round-trip-testable shape (AC8) independent of
 * {@code RegistryFriendlyByteBuf}. {@link DispatcherOfferMenu} is the only
 * class that builds these from a real {@link DestinationOffer}.
 *
 * @param approxDistanceBlocks {@link ConfirmedVillage#approxDistance()}, in blocks
 * @param danger                {@link OfferTerms#danger()}
 * @param timeAllowanceTicks    {@link OfferTerms#timeAllowanceTicks()}
 * @param reward                {@link OfferTerms#reward()}
 */
public record DispatcherOfferRow(double approxDistanceBlocks, double danger, long timeAllowanceTicks, double reward) {
}
