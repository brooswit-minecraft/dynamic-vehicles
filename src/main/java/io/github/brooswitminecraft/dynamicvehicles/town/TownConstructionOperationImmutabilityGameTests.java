package io.github.brooswitminecraft.dynamicvehicles.town;

import io.github.brooswitminecraft.dynamicvehicles.delivery.RegionCoord;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * MINECRAFT-454 [coordinator review 51335] Focused coverage for the mutable-input defect found in
 * {@code TownConstructionOperation.CellEffect}/{@code BlockEntitySnapshot.Captured}: {@code
 * List.copyOf}/{@code Map.copyOf} freeze the outer collections but never the nested {@code
 * BlockPos}/{@code CompoundTag} values themselves. Proves a caller mutating either the ORIGINAL
 * value handed in, or a value RETURNED by an accessor, can never retroactively alter this
 * operation's own durable identity, effects, or derived chunks.
 */
@GameTestHolder(io.github.brooswitminecraft.dynamicvehicles.DynamicVehiclesMod.MODID)
@PrefixGameTestTemplate(false)
public final class TownConstructionOperationImmutabilityGameTests {
    private TownConstructionOperationImmutabilityGameTests() {}

    /** A caller passing a live, reused {@code MutableBlockPos} (e.g. a construction loop's own
     *  cursor) and then continuing to move it must never alter an already-constructed {@link
     *  TownConstructionOperation.CellEffect}'s own position, nor the owning operation's {@link
     *  TownConstructionOperation#affectedChunks()}. */
    @GameTest(template = "settlement_test")
    public static void mutatingTheOriginalMutableBlockPosAfterConstructionNeverAltersTheEffectOrItsChunk(GameTestHelper helper) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(10, 2, 10);
        TownConstructionOperation.CellEffect effect = new TownConstructionOperation.CellEffect(cursor,
                Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState(),
                new TownConstructionOperation.BlockEntitySnapshot.None(), new TownConstructionOperation.BlockEntitySnapshot.None(), "sig");
        BlockPos expected = new BlockPos(10, 2, 10);
        helper.assertTrue(effect.pos().equals(expected), "test setup: the effect must start at the cursor's original position");

        cursor.set(999, 999, 999);
        helper.assertTrue(effect.pos().equals(expected),
                "moving the original MutableBlockPos after construction must never alter the stored effect's own position - found " + effect.pos());

        ResourceKey<Level> dimension = helper.getLevel().dimension();
        RegionCoord region = new RegionCoord(20, 20);
        String townKey = TownKeys.keyFor(dimension, region);
        TownStorage storage = new TownStorage();
        storage.storeFor(townKey).credit(TownResourceCategory.STONE, 100);
        Optional<TownConstructionOperation> operation = storage.reserveConstructionOperation(UUID.randomUUID(), dimension, townKey,
                "site:immutable-pos", UUID.randomUUID(), UUID.randomUUID(), Optional.empty(), List.of(effect),
                Map.of(TownResourceCategory.STONE, 10));
        helper.assertTrue(operation.isPresent(), "test setup: reservation with the already-constructed effect must succeed");

        cursor.set(-500, -500, -500);
        Set<ChunkPos> chunks = operation.get().affectedChunks();
        helper.assertTrue(chunks.equals(Set.of(new ChunkPos(expected))),
                "a later mutation of the original cursor must never alter the operation's own derived affectedChunks - found " + chunks);
        helper.succeed();
    }

    /** A caller mutating the ORIGINAL {@code CompoundTag} handed into {@code Captured} after
     *  construction must never alter the stored snapshot. */
    @GameTest(template = "settlement_test")
    public static void mutatingTheOriginalNbtAfterConstructionNeverAltersTheCapturedSnapshot(GameTestHelper helper) {
        CompoundTag original = new CompoundTag();
        original.putString("marker", "before");
        TownConstructionOperation.BlockEntitySnapshot.Captured captured = new TownConstructionOperation.BlockEntitySnapshot.Captured(original);

        original.putString("marker", "mutated-after-construction");
        original.putInt("extra", 1);

        CompoundTag stored = captured.nbt();
        helper.assertTrue(stored.getString("marker").equals("before") && !stored.contains("extra"),
                "mutating the original CompoundTag after construction must never alter the stored snapshot - found " + stored);
        helper.succeed();
    }

    /** A caller mutating the TAG RETURNED by {@link TownConstructionOperation.BlockEntitySnapshot.Captured#nbt()}
     *  must never alter the internally-stored snapshot either - {@code nbt()} must hand back a
     *  fresh defensive copy on EVERY call, not merely once at construction. */
    @GameTest(template = "settlement_test")
    public static void mutatingTheReturnedNbtNeverAltersTheStoredSnapshot(GameTestHelper helper) {
        CompoundTag original = new CompoundTag();
        original.putString("marker", "original");
        TownConstructionOperation.BlockEntitySnapshot.Captured captured = new TownConstructionOperation.BlockEntitySnapshot.Captured(original);

        CompoundTag firstReturned = captured.nbt();
        firstReturned.putString("marker", "mutated-via-returned-reference");

        CompoundTag secondReturned = captured.nbt();
        helper.assertTrue(secondReturned.getString("marker").equals("original"),
                "mutating a tag returned by nbt() must never alter the internally-stored snapshot observed by a later call - found " + secondReturned);
        helper.succeed();
    }

    /** "Reject null captured payload" - {@code Captured} must fail closed on a null tag rather
     *  than silently storing/propagating one. */
    @GameTest(template = "settlement_test")
    public static void nullCapturedPayloadIsRejected(GameTestHelper helper) {
        boolean threw = false;
        try {
            new TownConstructionOperation.BlockEntitySnapshot.Captured(null);
        } catch (NullPointerException expected) {
            threw = true;
        }
        helper.assertTrue(threw, "Captured must reject a null NBT payload rather than silently storing one");
        helper.succeed();
    }
}
