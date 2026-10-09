package io.github.brooswitminecraft.dynamicvehicles.delivery;

/**
 * How a contract's danger value is rolled (MINECRAFT-131, inherited item 2).
 * {@code OfferGenerator.rollDanger} deliberately only implements
 * {@link #PER_REGION}, and takes no distance/ring parameter by design - see
 * its javadoc. {@link #PER_OFFER} is a documented placeholder for a
 * not-yet-built alternative; selecting it has no effect today.
 */
public enum DangerRollMode {
    /**
     * Danger is a pure function of (world seed, destination region): the same
     * village is always equally dangerous in a given world. Today's actual,
     * only-implemented behaviour.
     */
    PER_REGION,

    /**
     * NOT YET IMPLEMENTED. Would roll danger fresh for each offer instead of
     * once per region, trading durable per-village world knowledge for
     * reroll-shopping risk. Needs play feedback before it is built, per
     * MINECRAFT-131/MINECRAFT-113.
     */
    PER_OFFER
}
