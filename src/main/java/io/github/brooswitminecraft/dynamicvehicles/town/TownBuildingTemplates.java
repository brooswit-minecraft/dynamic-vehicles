package io.github.brooswitminecraft.dynamicvehicles.town;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

/**
 * The exact template/variation scheme MINECRAFT-388 leaves open: a single simple rectangular
 * footprint per {@link Kind} (floor, walls, flat roof — intentionally no doors/beds/furniture, a
 * documented simplification, since a real template catalog is explicitly out of scope for this
 * ticket), varied only by {@link TownPlan#storehouseVariant()} picking the storehouse's roof
 * height. One planned block list per site; {@code TownConstructionService} is what actually turns
 * it into incremental, resource-gated placement.
 */
public final class TownBuildingTemplates {
    private TownBuildingTemplates() {}

    public enum Kind { STOREHOUSE, RESIDENTIAL, COMMERCIAL, INDUSTRIAL }

    public record Placement(int x, int y, int z, Block block, TownResourceCategory cost) {
        public ConstructionSitePlan.PlannedBlock toPlan() {
            return new ConstructionSitePlan.PlannedBlock(x, y, z, cost);
        }
    }

    /**
     * @param centerX,centerZ the footprint's centre column
     * @param baseY the floor's Y level (the campfire's own Y — construction builds up from there)
     * @param storehouseVariant only consulted for {@link Kind#STOREHOUSE}; see
     *        {@link TownConstants#STOREHOUSE_VARIATIONS}
     */
    public static List<Placement> generate(Kind kind, int centerX, int baseY, int centerZ, int storehouseVariant) {
        int width = kind == Kind.STOREHOUSE ? TownConstants.STOREHOUSE_FOOTPRINT_WIDTH : 5;
        int radius = (width - 1) / 2;
        int height = kind == Kind.STOREHOUSE ? 4 + storehouseVariant : 4;
        Block wallBlock = wallBlockFor(kind);
        TownResourceCategory cost = costFor(kind);

        List<Placement> placements = new ArrayList<>();
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                boolean perimeter = Math.abs(x) == radius || Math.abs(z) == radius;
                // Floor.
                placements.add(new Placement(centerX + x, baseY, centerZ + z, wallBlock, cost));
                // Roof.
                placements.add(new Placement(centerX + x, baseY + height, centerZ + z, wallBlock, cost));
                if (perimeter) {
                    for (int y = 1; y < height; y++) {
                        placements.add(new Placement(centerX + x, baseY + y, centerZ + z, wallBlock, cost));
                    }
                }
            }
        }
        return placements;
    }

    private static Block wallBlockFor(Kind kind) {
        return switch (kind) {
            case STOREHOUSE -> Blocks.STONE_BRICKS;
            case RESIDENTIAL -> Blocks.OAK_PLANKS;
            case COMMERCIAL -> Blocks.BRICKS;
            case INDUSTRIAL -> Blocks.COBBLESTONE;
        };
    }

    private static TownResourceCategory costFor(Kind kind) {
        return switch (kind) {
            case RESIDENTIAL -> TownResourceCategory.WOOD;
            case STOREHOUSE, COMMERCIAL, INDUSTRIAL -> TownResourceCategory.STONE;
        };
    }

    public static Kind kindFor(TownZone zone) {
        return switch (zone) {
            case RESIDENTIAL -> Kind.RESIDENTIAL;
            case COMMERCIAL -> Kind.COMMERCIAL;
            case INDUSTRIAL -> Kind.INDUSTRIAL;
        };
    }
}
