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
import java.util.Optional;

/**
 * Server-wide cache of generated {@link PathfinderRoute}s, keyed by
 * dimension + origin region + level. "One deterministic route per origin
 * region per level, shared by all Pathfinder villagers in that region" is
 * easy while {@link PathfinderRouteGenerator} is a pure function of
 * {@code (origin, level)} — {@link PathfinderTerrainPlacer} resolves
 * against a real dimension id, so caching by dimension too, and generating
 * only once, is what keeps a route's identity stable across retries and
 * reloads (MINECRAFT-379's review follow-up) — and, per MINECRAFT-403,
 * also keeps a checkpoint's deterministically selected chunk fixed even if
 * the terrain underneath it changes later.
 *
 * <p>{@code PathfinderTerrainPlacer} never fails any more (MINECRAFT-403:
 * no terrain-accessibility check gates placement), so {@link #routeFor}'s
 * {@code Optional} return is never empty for a real caller in practice;
 * the type is kept for interface stability with {@link
 * PathfinderRouteGenerator}.
 */
public final class PathfinderRouteStorage extends SavedData {
    private static final String NAME = "dynamicvehicles_pathfinder_routes";

    private final Map<String, PathfinderRoute> routes = new HashMap<>();

    public static PathfinderRouteStorage of(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(new SavedData.Factory<>(PathfinderRouteStorage::new, PathfinderRouteStorage::load, null), NAME);
    }

    /** Returns the cached route for this key, generating and persisting one via {@code dimension} if absent. */
    public Optional<PathfinderRoute> routeFor(ServerLevel dimension, PathfinderRegion origin, PathfinderLevel level) {
        String key = key(dimension, origin, level);
        PathfinderRoute cached = routes.get(key);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<PathfinderRoute> generated = PathfinderRouteGenerator.generate(origin, level, new PathfinderTerrainPlacer(dimension));
        generated.ifPresent(route -> {
            routes.put(key, route);
            setDirty();
        });
        return generated;
    }

    /**
     * Test-only seam: seeds the cache directly with an already-built route, bypassing the real
     * placer. For GameTests exercising attempt lifecycle behavior (start/death/completion/expiry)
     * rather than placement itself — a real region-center candidate for a villager spawned in the
     * GameTest server's own small "Test Level" structure is routinely hundreds of blocks away
     * from anywhere the test can conveniently teleport a player, even though {@link
     * PathfinderTerrainPlacer} would resolve and return it without issue (MINECRAFT-403: it never
     * fails).
     */
    void seedForTest(ServerLevel dimension, PathfinderRegion origin, PathfinderLevel level, PathfinderRoute route) {
        routes.put(key(dimension, origin, level), route);
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
                checkpoints.add(new PathfinderCheckpoint(region, position));
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
                checkpointList.add(checkpointTag);
            }
            routeTag.put("Checkpoints", checkpointList);
            list.add(routeTag);
        }
        tag.put("Routes", list);
        return tag;
    }
}
