package io.github.brooswitminecraft.dynamicvehicles;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * Identity and spawn point of the separate superflat dimension the
 * there-and-back command (MINECRAFT-397) travels to. The dimension itself is
 * NOT registered in Java: it is a built-in datapack dimension loaded from
 * {@code data/dynamicvehicles/dimension/flat_garage.json}, exactly like a
 * server-pack-defined custom dimension, reusing {@code minecraft:overworld}'s
 * dimension type (normal day/night cycle, normal build height) so no new
 * dimension type needs documenting - only the flat generator's own layers do.
 */
final class FlatGarageDimension {
    private FlatGarageDimension() {
    }

    static final String ID = "flat_garage";

    static final ResourceKey<Level> LEVEL_KEY =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath(DynamicVehiclesMod.MODID, ID));

    /**
     * Total block height of {@code flat_garage.json}'s layer list (1 bedrock + 2 dirt + 1 grass_block).
     * Kept as a named constant, rather than re-deriving it from the heightmap on first visit, so the
     * very first teleport - before any chunk of this dimension has ever generated - lands exactly on
     * top of the grass layer instead of guessing.
     */
    private static final int SURFACE_LAYER_HEIGHT = 4;

    /** The fixed spawn point: centered on block (0, 0), standing on top of the grass layer. */
    static double spawnX() {
        return 0.5;
    }

    static double spawnY(ServerLevel level) {
        return level.getMinBuildHeight() + SURFACE_LAYER_HEIGHT;
    }

    static double spawnZ() {
        return 0.5;
    }
}
