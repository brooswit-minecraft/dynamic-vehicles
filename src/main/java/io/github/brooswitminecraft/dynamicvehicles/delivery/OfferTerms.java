package io.github.brooswitminecraft.dynamicvehicles.delivery;

/**
 * The plain, per-contract values {@link OfferGenerator} rolls/derives for a
 * single destination offer, before a Minecraft-facing caller attaches the
 * destination's identity and location.
 *
 * @param danger             rolled danger value, {@code dangerMin}..{@code dangerMax} on the config's 0..1 scale
 * @param timeAllowanceTicks the deadline duration, in game ticks
 * @param reward             the reward value, scaled from both distance and danger
 */
public record OfferTerms(double danger, long timeAllowanceTicks, double reward) {
}
