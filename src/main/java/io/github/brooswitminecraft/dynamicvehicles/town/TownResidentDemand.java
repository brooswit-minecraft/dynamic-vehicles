package io.github.brooswitminecraft.dynamicvehicles.town;

import net.minecraft.world.entity.npc.VillagerProfession;

import java.util.Map;

/**
 * An immutable observation of housing and retained-profession workplace capacity missing from one
 * founded town.  It is deliberately only a resident/planning value: observing a shortage never
 * grants a POI ticket, mutates storage, places a block, or schedules construction.
 */
record TownResidentDemand(int missingHomes, Map<VillagerProfession, Integer> missingWorkplaces) {
    static final TownResidentDemand NONE = new TownResidentDemand(0, Map.of());

    TownResidentDemand {
        if (missingHomes < 0) {
            throw new IllegalArgumentException("missingHomes must not be negative");
        }
        missingWorkplaces = Map.copyOf(missingWorkplaces);
        for (Map.Entry<VillagerProfession, Integer> entry : missingWorkplaces.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0) {
                throw new IllegalArgumentException("workplace demand must have a profession and positive count");
            }
        }
    }
}
