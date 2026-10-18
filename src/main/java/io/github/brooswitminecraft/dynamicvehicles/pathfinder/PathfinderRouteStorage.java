package io.github.brooswitminecraft.dynamicvehicles.pathfinder;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Server-wide cache of generated {@link PathfinderRoute}s, keyed by
 * dimension + origin region + level. "One deterministic route per origin
 * region per level, shared by all Pathfinder villagers in that region" is
 * easy while {@link PathfinderRouteGenerator} is a pure function of
 * {@code (origin, level)} — but {@link PathfinderTerrainPlacer} is NOT
 * pure (it reads the live heightmap), so without this cache, two players
 * starting attempts at different times — one before and one after the
 * first player bridges/terraforms the area — could resolve different
 * checkpoint coordinates for the "same" route. Generating once and
 * persisting the result is what keeps a route stable across retries,
 * reloads and later infrastructure edits, per MINECRAFT-379's review
 * follow-up.
 */
public final class PathfinderRouteStorage extends SavedData {
    private static final String NAME = "dynamicvehicles_pathfinder_routes";

    private final Map<String, PathfinderRoute> routes = new HashMap<>();

    public static PathfinderRouteStorage of(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(new SavedData.Factory<>(PathfinderRouteStorage::new, PathfinderRouteStorage::load, null), NAME);
    }

    /** Returns the cached route for this key, generating and persisting one via {@code dimension} if absent. */
    public PathfinderRoute routeFor(ServerLevel dimension, PathfinderRegion origin, PathfinderLevel level) {
        String key = key(dimension, origin, level);
        PathfinderRoute cached = routes.get(key);
        if (cached != null) {
            return cached;
        }
        PathfinderRoute generated = PathfinderRouteGenerator.generate(origin, level, new PathfinderTerrainPlacer(dimension));
        routes.put(key, generated);
        setDirty();
        return generated;
    }

    private static String key(ServerLevel dimension, PathfinderRegion origin, PathfinderLevel level) {
        return dimension.dimension().location() + ":" + origin.x() + ":" + origin.z() + ":" + level.level();
    }

    private static PathfinderRouteStorage load(CompoundTag tag, HolderLookup.Provider registries) {
        PathfinderRouteStorage storage = new PathfinderRouteStorage();
        ListTag list = tag.getList("Routes", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            String key = entry.getString("Key");
            PathfinderLevel level = PathfinderLevel.values()[entry.getInt("Level") - 1];
            PathfinderRegion origin = new PathfinderRegion(entry.getInt("OriginX"), entry.getInt("OriginZ"));
            List<PathfinderCheckpoint> checkpoints = new ArrayList<>();
            ListTag checkpointList = entry.getList("Checkpoints", Tag.TAG_COMPOUND);
            for (int c = 0; c < checkpointList.size(); c++) {
                CompoundTag checkpointTag = checkpointList.getCompound(c);
                PathfinderRegion region = new PathfinderRegion(checkpointTag.getInt("RegionX"), checkpointTag.getInt("RegionZ"));
                PathfinderPosition position = new PathfinderPosition(
                        checkpointTag.getString("Dimension"),
                        checkpointTag.getDouble("X"), checkpointTag.getDouble("Y"), checkpointTag.getDouble("Z"));
                checkpoints.add(new PathfinderCheckpoint(region, position, checkpointTag.getDouble("ReachRadius")));
            }
            storage.routes.put(key, new PathfinderRoute(origin, level, checkpoints));
        }
        return storage;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<String, PathfinderRoute> entry : routes.entrySet()) {
            CompoundTag routeTag = new CompoundTag();
            routeTag.putString("Key", entry.getKey());
            PathfinderRoute route = entry.getValue();
            routeTag.putInt("Level", route.level().level());
            routeTag.putInt("OriginX", route.origin().x());
            routeTag.putInt("OriginZ", route.origin().z());
            ListTag checkpointList = new ListTag();
            for (PathfinderCheckpoint checkpoint : route.checkpoints()) {
                CompoundTag checkpointTag = new CompoundTag();
                checkpointTag.putInt("RegionX", checkpoint.region().x());
                checkpointTag.putInt("RegionZ", checkpoint.region().z());
                checkpointTag.putString("Dimension", checkpoint.position().dimensionId());
                checkpointTag.putDouble("X", checkpoint.position().x());
                checkpointTag.putDouble("Y", checkpoint.position().y());
                checkpointTag.putDouble("Z", checkpoint.position().z());
                checkpointTag.putDouble("ReachRadius", checkpoint.reachRadius());
                checkpointList.add(checkpointTag);
            }
            routeTag.put("Checkpoints", checkpointList);
            list.add(routeTag);
        }
        tag.put("Routes", list);
        return tag;
    }
}
