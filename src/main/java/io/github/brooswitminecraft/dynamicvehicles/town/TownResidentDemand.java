package io.github.brooswitminecraft.dynamicvehicles.town;

import net.minecraft.world.entity.npc.VillagerProfession;

import java.util.Map;

/**
 * An immutable observation of housing and workplace capacity missing from one founded town.  It is
 * deliberately only a resident/planning value: observing a shortage never grants a POI ticket,
 * mutates storage, places a block, or schedules construction.
 *
 * <p>{@code missingWorkplaces} is retained-profession demand: a resident that already holds a real
 * profession but lost its workstation. {@code needsAnyWorkplace} [MINECRAFT-455 unassigned-
 * workplace demand] is deliberately separate and profession-independent, exactly mirroring {@code
 * missingHomes} - a genuinely unemployed resident has no specific profession to key a map entry by,
 * so this is a plain boolean flag, never a new {@link VillagerProfession}: "needs some workplace;
 * whichever real profession the next built site resolves to." Existing-vacant-workstation
 * allocation ({@code TownResidentAllocationHandler#allocateWorkplaceIfNeeded}) already finds and
 * claims an already-built vacancy before this is ever considered - this flag exists only for the
 * case that path already found nothing.
 */
record TownResidentDemand(int missingHomes, Map<VillagerProfession, Integer> missingWorkplaces, boolean needsAnyWorkplace) {
    static final TownResidentDemand NONE = new TownResidentDemand(0, Map.of(), false);

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
