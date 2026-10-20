package io.github.brooswitminecraft.dynamicvehicles.town;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Pure, Minecraft-free construction ordering and gating for a single planned building site.
 * Covers three MINECRAFT-388 requirements directly:
 * <ul>
 *   <li>terrain prep builds bottom-to-top ({@link #bottomUpOrder});</li>
 *   <li>the claimed campfire stays in its original block position until everything else at the
 *   site is built, replaced only then ({@link #bottomUpOrder} always defers the campfire's own
 *   cell to the very end, regardless of its height);</li>
 *   <li>a site requires a villager-claimed campfire, never an unclaimed one, to make any progress
 *   at all ({@link #nextBlockToPlace}).</li>
 * </ul>
 */
public final class ConstructionSitePlan {
    private ConstructionSitePlan() {}

    public record PlannedBlock(int x, int y, int z, TownResourceCategory cost) {}

    /**
     * Sorts {@code blocks} by ascending Y (bottom-to-top), then moves whichever block (if any)
     * occupies the campfire's own cell to the very end of the list, preserving the bottom-up order
     * of everything else. A plan with no block at the campfire's cell is returned sorted only.
     */
    public static List<PlannedBlock> bottomUpOrder(List<PlannedBlock> blocks, int campfireX, int campfireY, int campfireZ) {
        List<PlannedBlock> sorted = new ArrayList<>(blocks);
        sorted.sort(Comparator.comparingInt(PlannedBlock::y));

        List<PlannedBlock> ordered = new ArrayList<>(sorted.size());
        PlannedBlock campfireCell = null;
        for (PlannedBlock block : sorted) {
            if (block.x() == campfireX && block.y() == campfireY && block.z() == campfireZ) {
                campfireCell = block;
                continue;
            }
            ordered.add(block);
        }
        if (campfireCell != null) {
            ordered.add(campfireCell);
        }
        return ordered;
    }

    /**
     * The next block construction should attempt to place, or empty when there is nothing left, OR
     * when the site's campfire is not currently villager-claimed. An unclaimed campfire alone must
     * never trigger construction (MINECRAFT-388) — this is the single gate every construction tick
     * must check before placing anything, not just before starting a fresh site.
     */
    public static Optional<PlannedBlock> nextBlockToPlace(List<PlannedBlock> orderedRemaining, boolean campfireClaimed) {
        if (!campfireClaimed || orderedRemaining.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(orderedRemaining.get(0));
    }

    /**
     * Attempts to pay for placing {@code block} out of {@code store}. No partial charge and no
     * mutation at all when the town cannot afford it — the caller must not place the block in that
     * case (construction simply pauses until the category has enough credit).
     */
    public static boolean tryPayForBlock(TownResourceStore store, PlannedBlock block, int costPerBlock) {
        return store.tryDebit(block.cost(), costPerBlock);
    }
}
