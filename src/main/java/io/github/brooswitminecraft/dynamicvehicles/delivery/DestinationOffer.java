package io.github.brooswitminecraft.dynamicvehicles.delivery;

/**
 * A complete destination offer (AC2): a confirmed village (from
 * {@link VillagePlacementService}) combined with its rolled
 * {@link OfferTerms}. Unlike {@link OfferTerms}, this record can only be
 * built by a caller that already has a {@link ConfirmedVillage}, so it is
 * assembled by the thin Minecraft layer ({@link DispatcherOfferService}),
 * not unit-tested directly — the decisions it carries are all made, and all
 * tested, in {@link OfferGenerator} before this record exists.
 *
 * @param village destination identity, ring, approximate distance, and recoverable location
 * @param terms   rolled danger, time allowance, and reward for this contract
 */
public record DestinationOffer(ConfirmedVillage village, OfferTerms terms) {
}
