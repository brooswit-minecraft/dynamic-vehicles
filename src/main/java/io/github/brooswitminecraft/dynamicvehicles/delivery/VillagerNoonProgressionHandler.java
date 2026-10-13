package io.github.brooswitminecraft.dynamicvehicles.delivery;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/** Daily, server-authoritative villager progression and Builder settlement rolls. */
public final class VillagerNoonProgressionHandler {
    static final String LAST_NOON_DAY_TAG = "dynamicvehicles.lastNoonProgressionDay";
    /** Conservative passive progression: one villager-XP per Minecraft day. */
    static final int PASSIVE_XP_PER_NOON = 1;

    private VillagerNoonProgressionHandler() {}

    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !BuilderSettlementPlan.isNoon(level.getDayTime())) return;
        long day = Math.floorDiv(level.getDayTime(), 24000L);
        for (Entity entity : level.getEntities().getAll()) {
            if (entity instanceof Villager villager) progressAtNoon(villager, day);
        }
    }

    private static void progressAtNoon(Villager villager, long day) {
        boolean hasProcessedDay = villager.getPersistentData().contains(LAST_NOON_DAY_TAG);
        long lastDay = villager.getPersistentData().getLong(LAST_NOON_DAY_TAG);
        if (hasProcessedDay && !BuilderSettlementPlan.shouldRunForDay(lastDay, day)) return;
        villager.getPersistentData().putLong(LAST_NOON_DAY_TAG, day);
        villager.setVillagerXp(villager.getVillagerXp() + PASSIVE_XP_PER_NOON);
        if (villager.getVillagerData().getProfession() == BuilderProfession.BUILDER.get()) {
            BuilderSettlementService.attemptSettlement(villager);
        }
    }
}
