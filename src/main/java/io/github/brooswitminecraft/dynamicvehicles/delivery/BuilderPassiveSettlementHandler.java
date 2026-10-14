package io.github.brooswitminecraft.dynamicvehicles.delivery;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/** Elapsed-time passive Builder settlement checks; independent from noon XP and donation trades. */
public final class BuilderPassiveSettlementHandler {
    static final String LAST_PASSIVE_CHECK_GAME_TIME_TAG = "dynamicvehicles.lastBuilderPassiveCheckGameTime";
    private BuilderPassiveSettlementHandler() {}

    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        long gameTime = level.getGameTime();
        for (Entity entity : level.getEntities().getAll()) {
            if (entity instanceof Villager villager) {
                BuilderSettlementService.processPendingDonations(villager);
                attemptPassiveSettlement(villager, gameTime);
            }
        }
    }

    private static void attemptPassiveSettlement(Villager villager, long gameTime) {
        if (villager.getVillagerData().getProfession() != BuilderProfession.BUILDER.get()) return;
        boolean hasLastCheck = villager.getPersistentData().contains(LAST_PASSIVE_CHECK_GAME_TIME_TAG);
        long lastCheck = villager.getPersistentData().getLong(LAST_PASSIVE_CHECK_GAME_TIME_TAG);
        if (!BuilderSettlementPlan.shouldRunPassiveCheck(hasLastCheck, lastCheck, gameTime)) return;

        // Record before attempting: a reload or a very large time jump creates one check, never a retry burst.
        villager.getPersistentData().putLong(LAST_PASSIVE_CHECK_GAME_TIME_TAG, gameTime);
        BuilderSettlementService.attemptPassiveSettlement(villager);
    }
}
