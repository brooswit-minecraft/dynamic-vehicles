package io.github.brooswitminecraft.dynamicvehicles.town;

/** A planned parcel's intended use. Every villager needs {@link #RESIDENTIAL}; professions map
 *  additionally onto {@link #COMMERCIAL} or {@link #INDUSTRIAL} parcels (reusing/extending the
 *  existing Builder/profession POI mapping is left to the profession-specific placement code that
 *  consumes a plan, not to the plan itself). */
public enum TownZone {
    RESIDENTIAL, COMMERCIAL, INDUSTRIAL
}
