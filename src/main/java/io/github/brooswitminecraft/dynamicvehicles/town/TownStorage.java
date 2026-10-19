package io.github.brooswitminecraft.dynamicvehicles.town;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Server-wide persistence for Dynamic Towns: one {@link TownResourceStore} per town, plus the
 * one-way "bootstrap already happened" claim per town, keyed identically to
 * {@code delivery.SettlementFounder#settlementKey} ({@code dimension:regionX:regionZ}). Attached to
 * the overworld's own data storage, the same place {@code delivery.DeliveryContractStorage} keeps
 * its cross-dimension, per-server data — this is server-wide state, not tied to whichever
 * dimension happens to be loaded.
 *
 * <p>[Review round 4, finding #2 / round 5, finding #2] Also persists exactly which world cells
 * Dynamic Towns itself has actually placed-and-paid-for ({@link #placedCells}) — the real
 * "construction progress" state the ticket's acceptance criteria require, and the single source of
 * truth {@link TownConstructionService} and {@link TownRoadConstructionService} now use to decide
 * "is this cell already done" instead of diffing the live world against the plan. A foreign block
 * that merely happens to match the planned material (player-placed, naturally generated, or left
 * over from some other mod) is never recorded here, so it can never be silently adopted as "already
 * paid for" the way live-world diffing used to let it.
 *
 * <p>Each recorded cell is scoped to the exact plan that claims it (a building site's own campfire
 * anchor, or a region's road plan) via an opaque {@code owner} key, never merely dimension+position.
 * Two different plans can legitimately claim the same coordinate over a town's lifetime (a road
 * cell later re-planned as part of a building's footprint, or vice versa); without an owner, the
 * SECOND plan would read the FIRST plan's already-paid progress as its own free, pre-paid cell the
 * instant the live block also happened to match its own planned material.
 */
public final class TownStorage extends SavedData {
    private static final String NAME = "dynamicvehicles_towns";

    private final Set<String> foundedTowns = new HashSet<>();
    private final Map<String, TownResourceStore> stores = new HashMap<>();
    private final Map<String, String> placedCells = new HashMap<>();
    private final Map<String, BlockPos> pendingCampfires = new HashMap<>();
    private final Map<String, Integer> siteFloors = new HashMap<>();

    public static TownStorage of(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(new SavedData.Factory<>(TownStorage::new, TownStorage::load, null), NAME);
    }

    public boolean hasFoundedTown(String key) {
        return foundedTowns.contains(key);
    }

    public void markTownFounded(String key) {
        foundedTowns.add(key);
    }

    public TownResourceStore storeFor(String townKey) {
        return stores.computeIfAbsent(townKey, k -> new TownResourceStore());
    }

    /** {@code true} iff Dynamic Towns' plan identified by {@code owner} (e.g.
     *  {@link #siteOwner(BlockPos)} or {@link #roadOwner(ResourceKey, io.github.brooswitminecraft.dynamicvehicles.delivery.RegionCoord)})
     *  has already placed-and-paid-for {@code pos} in {@code dimension} — never merely "the live
     *  block happens to match the plan", and never merely "SOME plan already claimed this cell": a
     *  different plan's own progress at the same coordinate never counts as this plan's progress. */
    public boolean isCellPlaced(ResourceKey<Level> dimension, BlockPos pos, String owner) {
        return owner.equals(placedCells.get(cellKey(dimension, pos)));
    }

    /** Records that {@code pos} in {@code dimension} has now actually been placed and paid for by
     *  the plan identified by {@code owner} — the durable "construction progress" record a reload
     *  must not lose. Overwrites whichever plan (if any) previously claimed this exact cell: the
     *  world itself is the final arbiter of "what is here now", and only one plan can own a given
     *  cell's paid progress at a time. */
    public void markCellPlaced(ResourceKey<Level> dimension, BlockPos pos, String owner) {
        placedCells.put(cellKey(dimension, pos), owner);
    }

    /**
     * The floor Y a building site's own {@code owner} (see {@link #siteOwner(BlockPos)}) committed
     * to the FIRST time it was ever estimated, reused on every later tick rather than re-derived from
     * the live world each time. Without this, once terrain prep clears a trunk the campfire was
     * standing on, the NEXT tick's fresh estimate would no longer see that (now-removed) trunk
     * material to walk down through, snapping the estimate straight back up to the campfire's own
     * elevated Y mid-build and silently replanning the whole site around an inconsistent floor.
     */
    public Optional<Integer> floorFor(String owner) {
        return Optional.ofNullable(siteFloors.get(owner));
    }

    public void setFloor(String owner, int floorY) {
        siteFloors.put(owner, floorY);
    }

    /** Called once a site is fully built and its claimant released - the floor commitment has
     *  served its purpose and need not be retained forever. */
    public void clearFloor(String owner) {
        siteFloors.remove(owner);
    }

    /**
     * [Review round 5, finding #8] The exact campfire position a PRIOR {@code TownFounder} bootstrap
     * attempt for {@code key} already placed, whose companion villager spawn (or whose own orphan
     * cleanup) did not confirm. A retry must reuse this exact persisted position rather than
     * re-deriving a fresh one via the heightmap: the still-standing orphan campfire itself now reads
     * as "the ground" there, so a fresh heightmap query would land one block ABOVE it and place a
     * second, duplicate campfire.
     */
    public Optional<BlockPos> pendingCampfireFor(String key) {
        return Optional.ofNullable(pendingCampfires.get(key));
    }

    public void setPendingCampfire(String key, BlockPos pos) {
        pendingCampfires.put(key, pos);
    }

    public void clearPendingCampfire(String key) {
        pendingCampfires.remove(key);
    }

    /** The stable owner key for a building site, anchored on its own original campfire position -
     *  stable across the whole build even once that cell's own block is no longer a campfire. */
    public static String siteOwner(BlockPos campfire) {
        return "site:" + campfire.getX() + "," + campfire.getY() + "," + campfire.getZ();
    }

    /** The stable owner key for a region's road plan. */
    public static String roadOwner(ResourceKey<Level> dimension, io.github.brooswitminecraft.dynamicvehicles.delivery.RegionCoord region) {
        return "road:" + TownKeys.keyFor(dimension, region);
    }

    private static String cellKey(ResourceKey<Level> dimension, BlockPos pos) {
        return dimension.location() + "@" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag founded = new ListTag();
        for (String key : foundedTowns) {
            CompoundTag entry = new CompoundTag();
            entry.putString("Key", key);
            founded.add(entry);
        }
        tag.put("FoundedTowns", founded);

        ListTag storeList = new ListTag();
        for (Map.Entry<String, TownResourceStore> entry : stores.entrySet()) {
            CompoundTag storeTag = new CompoundTag();
            storeTag.putString("Key", entry.getKey());
            for (Map.Entry<TownResourceCategory, Integer> balance : entry.getValue().snapshot().entrySet()) {
                storeTag.putInt(balance.getKey().name(), balance.getValue());
            }
            storeList.add(storeTag);
        }
        tag.put("Stores", storeList);

        ListTag placedList = new ListTag();
        for (Map.Entry<String, String> entry : placedCells.entrySet()) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putString("Key", entry.getKey());
            entryTag.putString("Owner", entry.getValue());
            placedList.add(entryTag);
        }
        tag.put("PlacedCells", placedList);

        ListTag pendingList = new ListTag();
        for (Map.Entry<String, BlockPos> entry : pendingCampfires.entrySet()) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putString("Key", entry.getKey());
            entryTag.putInt("X", entry.getValue().getX());
            entryTag.putInt("Y", entry.getValue().getY());
            entryTag.putInt("Z", entry.getValue().getZ());
            pendingList.add(entryTag);
        }
        tag.put("PendingCampfires", pendingList);

        ListTag floorList = new ListTag();
        for (Map.Entry<String, Integer> entry : siteFloors.entrySet()) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putString("Key", entry.getKey());
            entryTag.putInt("FloorY", entry.getValue());
            floorList.add(entryTag);
        }
        tag.put("SiteFloors", floorList);
        return tag;
    }

    private static TownStorage load(CompoundTag tag, HolderLookup.Provider registries) {
        TownStorage storage = new TownStorage();
        ListTag founded = tag.getList("FoundedTowns", Tag.TAG_COMPOUND);
        for (int i = 0; i < founded.size(); i++) {
            storage.foundedTowns.add(founded.getCompound(i).getString("Key"));
        }
        ListTag storeList = tag.getList("Stores", Tag.TAG_COMPOUND);
        for (int i = 0; i < storeList.size(); i++) {
            CompoundTag storeTag = storeList.getCompound(i);
            TownResourceStore store = new TownResourceStore();
            Map<TownResourceCategory, Integer> snapshot = new HashMap<>();
            for (TownResourceCategory category : TownResourceCategory.values()) {
                if (storeTag.contains(category.name())) {
                    snapshot.put(category, storeTag.getInt(category.name()));
                }
            }
            store.loadFrom(snapshot);
            storage.stores.put(storeTag.getString("Key"), store);
        }
        ListTag placedList = tag.getList("PlacedCells", Tag.TAG_COMPOUND);
        for (int i = 0; i < placedList.size(); i++) {
            CompoundTag entryTag = placedList.getCompound(i);
            storage.placedCells.put(entryTag.getString("Key"), entryTag.getString("Owner"));
        }
        ListTag pendingList = tag.getList("PendingCampfires", Tag.TAG_COMPOUND);
        for (int i = 0; i < pendingList.size(); i++) {
            CompoundTag entryTag = pendingList.getCompound(i);
            storage.pendingCampfires.put(entryTag.getString("Key"),
                    new BlockPos(entryTag.getInt("X"), entryTag.getInt("Y"), entryTag.getInt("Z")));
        }
        ListTag floorList = tag.getList("SiteFloors", Tag.TAG_COMPOUND);
        for (int i = 0; i < floorList.size(); i++) {
            CompoundTag entryTag = floorList.getCompound(i);
            storage.siteFloors.put(entryTag.getString("Key"), entryTag.getInt("FloorY"));
        }
        return storage;
    }
}
