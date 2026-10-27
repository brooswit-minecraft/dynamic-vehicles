package io.github.brooswitminecraft.dynamicvehicles.town;

import io.github.brooswitminecraft.dynamicvehicles.delivery.RegionCoord;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.UUID;

/**
 * A durable construction admission for capacity requested on behalf of one resident.
 *
 * <p>This is deliberately an intent, not a POI or construction operation.  In particular, creating
 * one neither places an anchor nor reserves a bed/workstation nor debits a town.  Those effects
 * remain behind the existing construction transaction and its ownership-proven handoff.
 */
record ResidentCapacityAdmission(ResourceKey<Level> dimension, String townKey, RegionCoord region,
        UUID beneficiaryId, int requestedHomes, Map<VillagerProfession, Integer> requestedWorkplaces,
        String siteOwner, String planSignature, State state) {
    enum State {
        AWAITING_CLAIM,
        BUILDING,
        COMPLETED,
        CANCELLED;

        boolean terminal() {
            return this == COMPLETED || this == CANCELLED;
        }
    }

    ResidentCapacityAdmission {
        if (requestedHomes < 0) {
            throw new IllegalArgumentException("requestedHomes must not be negative");
        }
        requestedWorkplaces = Map.copyOf(requestedWorkplaces);
        for (Map.Entry<VillagerProfession, Integer> entry : requestedWorkplaces.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0) {
                throw new IllegalArgumentException("requested workplaces must have a profession and positive count");
            }
        }
        if (requestedHomes == 0 && requestedWorkplaces.isEmpty()) {
            throw new IllegalArgumentException("admission must request homes or workplaces");
        }
    }
}
