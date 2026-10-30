package io.github.brooswitminecraft.dynamicvehicles.town;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;

/** Cancellable normal-gameplay removal guards: player breaks and explosion destruction. */
public final class TownDonationBoxProtection {
    private TownDonationBoxProtection() {}
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof ServerLevel level && level.getBlockEntity(event.getPos()) instanceof TownDonationBoxEntity box
                && !box.isEmptyAndRetirable(level)) event.setCanceled(true);
    }
    public static void onExplosion(ExplosionEvent.Detonate event) {
        event.getAffectedBlocks().removeIf(pos -> event.getLevel().getBlockEntity(pos) instanceof TownDonationBoxEntity);
    }
}
