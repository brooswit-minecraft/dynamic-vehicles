package io.github.brooswitminecraft.dynamicvehicles.town;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.ArrayList;
import java.util.List;

/**
 * The exact template/variation scheme MINECRAFT-388 leaves open: a single simple rectangular
 * footprint per {@link Kind} (floor, walls, flat roof), varied only by
 * {@link TownPlan#storehouseVariant()} picking the storehouse's roof height — plus, per review
 * round 2 finding #7, exactly one functional interior fixture matching the zone's purpose: a bed
 * for {@link Kind#RESIDENTIAL} (every villager needs accommodation) and the claimant's own
 * profession-appropriate work-site block (see {@link TownProfessionWorkstations}) for
 * {@link Kind#COMMERCIAL}/{@link Kind#INDUSTRIAL}. {@code TownConstructionService} is what
 * actually turns the planned list into incremental, resource-gated placement.
 */
public final class TownBuildingTemplates {
    private TownBuildingTemplates() {}

    public enum Kind { STOREHOUSE, RESIDENTIAL, COMMERCIAL, INDUSTRIAL }

    /**
     * @param overrideState the exact {@link BlockState} to place, when a plain
     *        {@code block.defaultBlockState()} is not enough (e.g. a bed's HEAD/FOOT half);
     *        {@code null} (the common case) means "use {@code block}'s own default state".
     */
    public record Placement(int x, int y, int z, Block block, TownResourceCategory cost, BlockState overrideState) {
        public Placement(int x, int y, int z, Block block, TownResourceCategory cost) {
            this(x, y, z, block, cost, null);
        }

        /** The actual state construction should place at this cell. */
        public BlockState state() {
            return overrideState != null ? overrideState : block.defaultBlockState();
        }

        public ConstructionSitePlan.PlannedBlock toPlan() {
            return new ConstructionSitePlan.PlannedBlock(x, y, z, cost);
        }
    }

    /**
     * @param centerX,centerZ the footprint's centre column
     * @param baseY the floor's Y level (the estimated tree-excluded ground height — see
     *        {@link TownTerrain#estimateGroundHeight} — NOT necessarily the campfire's own
     *        current Y; construction builds up from here)
     * @param storehouseVariant only consulted for {@link Kind#STOREHOUSE}; see
     *        {@link TownConstants#STOREHOUSE_VARIATIONS}
     * @param claimantProfession the claiming villager's profession, consulted only for
     *        {@link Kind#COMMERCIAL}/{@link Kind#INDUSTRIAL} to pick the work-site block; may be
     *        {@code null} (e.g. {@link Kind#STOREHOUSE}, or no mapped workstation for that
     *        profession) in which case no work-site block is added.
     */
    public static List<Placement> generate(Kind kind, int centerX, int baseY, int centerZ, int storehouseVariant,
            VillagerProfession claimantProfession) {
        int width = kind == Kind.STOREHOUSE ? TownConstants.STOREHOUSE_FOOTPRINT_WIDTH : 5;
        int radius = (width - 1) / 2;
        int height = heightFor(kind, storehouseVariant);
        Block wallBlock = wallBlockFor(kind);
        TownResourceCategory cost = costFor(kind);

        // [Review round 3, finding #5] A single south-facing doorway, two cells tall, centred on
        // that wall - without it the generated shell is completely sealed, so whatever fixture
        // (bed/workstation) gets placed inside is never actually reachable. The interior itself
        // has no dividing walls, so an opening here alone is enough to make every interior cell
        // reachable from outside.
        int doorX = centerX;
        int doorZ = centerZ + radius;

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
                        int cellX = centerX + x;
                        int cellZ = centerZ + z;
                        if (cellX == doorX && cellZ == doorZ && (y == 1 || y == 2)) {
                            continue; // doorway opening, placed explicitly below instead
                        }
                        placements.add(new Placement(cellX, y, cellZ, wallBlock, cost));
                    }
                }
            }
        }
        addDoor(placements, doorX, baseY, doorZ, cost);

        if (kind == Kind.RESIDENTIAL) {
            addBed(placements, centerX, baseY, centerZ, cost);
        } else if (kind == Kind.COMMERCIAL || kind == Kind.INDUSTRIAL) {
            addWorkstation(placements, centerX, baseY, centerZ, cost, claimantProfession);
            // [Review round 3, finding #5] This site's own claimant lives here too, not only
            // works here: a workplace zoned commercial/industrial must not leave its claimant
            // without a bed anywhere. Full town-wide multi-villager housing allocation (counting
            // every resident across every building) is out of this bounded fix's scope - each
            // construction site here is still driven by exactly one known claimant - but that
            // one claimant is never left without accommodation just because their workplace
            // happens to be zoned commercial/industrial rather than residential.
            addBed(placements, centerX, baseY, centerZ, cost);
        }
        return placements;
    }

    /** A single, functional (both halves) south-facing door, so the sealed shell above actually
     *  has an entrance. */
    private static void addDoor(List<Placement> placements, int x, int baseY, int z, TownResourceCategory cost) {
        BlockState lower = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.FACING, Direction.SOUTH)
                .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
        BlockState upper = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.FACING, Direction.SOUTH)
                .setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER);
        placements.add(new Placement(x, baseY + 1, z, Blocks.OAK_DOOR, cost, lower));
        placements.add(new Placement(x, baseY + 2, z, Blocks.OAK_DOOR, cost, upper));
    }

    /** One interior, functional bed (HEAD + FOOT, correctly paired) so a residential building
     *  actually satisfies "every villager needs accommodation" rather than being a sealed shell -
     *  placed well inside the walls, never on the perimeter, regardless of footprint width. */
    private static void addBed(List<Placement> placements, int centerX, int baseY, int centerZ, TownResourceCategory cost) {
        BlockState foot = Blocks.RED_BED.defaultBlockState()
                .setValue(BedBlock.FACING, Direction.SOUTH)
                .setValue(BedBlock.PART, BedPart.FOOT);
        BlockState head = Blocks.RED_BED.defaultBlockState()
                .setValue(BedBlock.FACING, Direction.SOUTH)
                .setValue(BedBlock.PART, BedPart.HEAD);
        placements.add(new Placement(centerX - 1, baseY + 1, centerZ - 1, Blocks.RED_BED, cost, foot));
        placements.add(new Placement(centerX - 1, baseY + 1, centerZ, Blocks.RED_BED, cost, head));
    }

    /** The claimant's profession-appropriate vanilla work-site block, placed one interior cell
     *  away from the bed's own interior cell so the two never collide. Omitted entirely when the
     *  claimant has no mapped workstation (e.g. unemployed, or a profession this mapping does not
     *  recognise) - never substituted with an invented block. */
    private static void addWorkstation(List<Placement> placements, int centerX, int baseY, int centerZ,
            TownResourceCategory cost, VillagerProfession claimantProfession) {
        if (claimantProfession == null) {
            return;
        }
        TownProfessionWorkstations.blockFor(claimantProfession).ifPresent(workstation ->
                placements.add(new Placement(centerX + 1, baseY + 1, centerZ + 1, workstation, cost)));
    }

    /** The planned height (floor to roof, inclusive span) a {@link #generate} call for this
     *  kind/variant will produce - exposed so callers (preflight) can bound their own scan by the
     *  WHOLE planned volume, not just today's terrain top. */
    public static int heightFor(Kind kind, int storehouseVariant) {
        return kind == Kind.STOREHOUSE ? 4 + storehouseVariant : 4;
    }

    static Block wallBlockFor(Kind kind) {
        return switch (kind) {
            case STOREHOUSE -> Blocks.STONE_BRICKS;
            case RESIDENTIAL -> Blocks.OAK_PLANKS;
            case COMMERCIAL -> Blocks.BRICKS;
            case INDUSTRIAL -> Blocks.COBBLESTONE;
        };
    }

    static TownResourceCategory costFor(Kind kind) {
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
