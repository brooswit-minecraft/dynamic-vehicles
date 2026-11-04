package io.github.brooswitminecraft.dynamicvehicles.town;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.neoforged.neoforge.attachment.AttachmentHolder;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkDataEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

/**
 * [coordinator design 51302/51303, stage 1B - corrected per interim reviews 51306/51308] A narrow,
 * explicit, server-thread-only checkpoint operation for exactly one currently loaded FULL chunk,
 * using a genuine persistent NeoForge chunk data attachment as its witness (never a static {@code
 * ChunkPos}-only map or a one-shot root NBT key - see {@code TownStorageCheckpointWitness}, removed
 * in an earlier round, for why that design was unsound: a plain root-tag key does not survive a
 * later ordinary save, since {@link ChunkSerializer#write} rebuilds the chunk's own compound from
 * scratch every time. A real attachment, by contrast, lives on the {@code ChunkAccess} object
 * itself (see {@link net.minecraft.world.level.chunk.ChunkAccess#setData}, which marks the chunk
 * unsaved - exactly the normal engine dirty mechanism, not a side channel) and {@link
 * ChunkSerializer} folds it into every chunk save and reads it back on every chunk load
 * automatically, through {@code net.neoforged.neoforge.attachment.AttachmentHolder.ATTACHMENTS_NBT_KEY}.
 *
 * <p><b>Fail-closed by construction, not by discipline alone:</b> {@link IAttachmentHolder#getData}
 * silently MATERIALIZES AND STORES a default value the first time it is called on a holder that
 * has none - exactly the "reconstructed as proof" failure mode this design must avoid. This
 * class's own default value supplier therefore throws unconditionally; the only way to ever
 * observe this attachment is {@link IAttachmentHolder#hasData}/{@link
 * IAttachmentHolder#getExistingDataOrNull}, which never materialize anything. {@link
 * AttachmentHolder}'s own (de)serialization also silently SWALLOWS a per-attachment exception
 * (catches, logs, skips) rather than ever throwing it back to a caller - confirmed by reading
 * {@code AttachmentHolder#serializeAttachments}/{@code #deserializeAttachments} - so "missing or
 * malformed" can never be detected by catching an exception; it is detected by explicitly checking
 * presence/content after the fact, which is exactly what {@link #checkpointLoadedChunk} does both
 * immediately after serialization and again after the real disk readback.
 *
 * <p><b>Reentrancy, confirmed and closed (review 51306):</b> nothing stops a {@code
 * ChunkDataEvent.Save} listener - ours or another mod's - from synchronously triggering a SECOND
 * checkpoint of the SAME chunk while the first is still mid-flight. Without a guard, the outer
 * call's own (now-stale) already-serialized snapshot would still pass its own validation (it was
 * valid when captured) and get written AFTER the nested, newer call's snapshot, silently
 * resurrecting older state even though both calls report success. A transient, per-(dimension,
 * chunk) in-progress guard - held only for the duration of one call, released in a {@code finally}
 * on every exit path including a thrown exception - closes this: a nested call for the exact same
 * chunk is rejected immediately, before touching anything, rather than racing the outer one. This
 * is NOT the same shape as the removed Part B adapter's unsound persistent {@code ChunkPos}-only
 * map: it holds no witness state at all and never outlives one call.
 *
 * <p>Independently of reentrancy, a listener could also mutate the chunk's LIVE attachment
 * directly (via {@link IAttachmentHolder#setData}) during event handling without going through
 * this class at all - the already-serialized {@code data} tag would not reflect that (it was built
 * before the event), so serialized-witness validation alone would not catch it. {@link
 * #checkpointLoadedChunk} therefore separately re-reads the LIVE attachment after event dispatch
 * and rejects if it no longer matches what was just set - independent of, and in addition to, the
 * serialized-tag check.
 *
 * <p>Also independently, a listener could alter the serialized chunk's OWN recorded coordinates
 * ({@code xPos}/{@code zPos}) without touching the witness key at all; {@link
 * #checkpointLoadedChunk} validates those explicitly against the expected {@link ChunkPos}, both
 * right after save-event processing and again on readback.
 *
 * <p><b>What this proves, precisely, and what it does not:</b> a successful {@link
 * CheckpointResult.Checkpointed} means the exact {@code checkpointId} minted for this call was
 * independently read back from disk (through the SAME {@link ChunkMap}/{@code ChunkStorage}
 * writer the engine itself uses, after a real {@link ChunkMap#flushWorker()}) - not merely that a
 * write future completed, since {@code IOWorker#store} COALESCES same-chunk pending writes and
 * shares ONE future between them (confirmed by reading {@code IOWorker#store}) - a future
 * resolving successfully therefore proves only that SOME write for this chunk landed, never
 * specifically THIS call's own bytes. This does NOT prove cross-file atomicity with {@code
 * TownStorage}'s own file, nor universal power-loss durability of the underlying filesystem/region
 * file - only that THIS chunk's own disk snapshot, at the moment of successful readback, really
 * did carry this checkpoint's own identity.
 *
 * <p><b>Known, reported, not closed this stage:</b> {@link ChunkMap#flushWorker} drains that
 * dimension's ENTIRE IO worker queue (every chunk, not just this one) and blocks the calling
 * thread until it does - there is no per-chunk fsync in the public API. A caller invoking this
 * repeatedly on a tick-bound thread risks real, measurable tick stalls; this is a genuine
 * limitation of the shared pipeline, not something this adapter invents a workaround for.
 */
public final class TownStorageChunkCheckpoint {
    private TownStorageChunkCheckpoint() {}

    /** [coordinator design 51303] Identifies the checkpoint/generation and is dimension-scoped
     *  (the {@code dimension} field) and world/session-scoped (a freshly minted {@code UUID} per
     *  checkpoint call, rather than a small counter that could coincidentally collide across
     *  separate server sessions or dimensions sharing the same chunk coordinates). Chunk scoping
     *  itself is structural: this value only ever exists attached to the one specific {@code
     *  ChunkAccess} object it was set on. */
    public record Witness(ResourceKey<Level> dimension, UUID checkpointId) {
        public static final Codec<Witness> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ResourceKey.codec(Registries.DIMENSION).fieldOf("dimension").forGetter(Witness::dimension),
                UUIDUtil.CODEC.fieldOf("checkpoint_id").forGetter(Witness::checkpointId)
        ).apply(instance, Witness::new));
    }

    private static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, io.github.brooswitminecraft.dynamicvehicles.DynamicVehiclesMod.MODID);

    /** [coordinator design 51303] "never reconstructed as proof": the default value supplier
     *  throws rather than returning anything a caller could mistake for real-or-absent data - the
     *  ONLY sanctioned way to observe this attachment is {@link IAttachmentHolder#hasData}/{@link
     *  IAttachmentHolder#getExistingDataOrNull}, used exclusively throughout this class, neither of
     *  which ever invokes this supplier. */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Witness>> WITNESS_ATTACHMENT = ATTACHMENT_TYPES.register(
            "town_storage_chunk_checkpoint_witness",
            () -> AttachmentType.<Witness>builder(holder -> {
                throw new IllegalStateException(
                        "TownStorageChunkCheckpoint's witness attachment must never be read via getData() - "
                                + "use hasData()/getExistingDataOrNull(), which never materialize a default");
            }).serialize(Witness.CODEC).build());

    public static void register(net.neoforged.bus.api.IEventBus modEventBus) {
        ATTACHMENT_TYPES.register(modEventBus);
    }

    /** [coordinator review 51306, re-scoped per review 51311] Transient, per-(level, chunk)
     *  reentrancy guard - see this class's own doc for the exact bug this closes. Held only for
     *  the duration of one call. Keyed by the actual {@code ServerLevel} instance itself (plain
     *  reference identity - {@code Level}/{@code ServerLevel} do not override {@code equals}/
     *  {@code hashCode}, confirmed by reading both), not merely its dimension registry key: two
     *  different world/level instances could otherwise share the same {@code ResourceKey<Level>}
     *  and be wrongly treated as the same guard scope. */
    private record InProgressKey(ServerLevel level, ChunkPos chunkPos) {}

    private static final Set<InProgressKey> IN_PROGRESS = ConcurrentHashMap.newKeySet();

    public sealed interface CheckpointResult {
        /** The checkpoint genuinely succeeded: written, flushed, and independently read back with
         *  the exact {@code checkpointId} minted for this call. */
        record Checkpointed(UUID checkpointId) implements CheckpointResult {}

        /** No currently loaded FULL chunk exists at the requested position (never loaded, already
         *  unloaded, or this was not called from the server's own main thread - {@link
         *  ServerChunkCache#getChunkNow} itself returns {@code null} off-thread). */
        record RejectedNoLoadedChunk() implements CheckpointResult {}

        /** [coordinator review 51306] A nested checkpoint call for the exact same (dimension,
         *  chunk) was already in progress - rejected immediately, before touching anything, so it
         *  can never race the outer call's own write. */
        record RejectedReentrantCheckpoint() implements CheckpointResult {}

        /** The live chunk object/level identity changed between before and after {@code
         *  ChunkDataEvent.Save} was posted - e.g. the chunk was unloaded and a different instance
         *  loaded back in during event handling. Never trusted as the same checkpoint. */
        record RejectedStaleChunkIdentity() implements CheckpointResult {}

        /** [coordinator review 51306] The LIVE attachment on the chunk no longer matches what was
         *  just set, observed after event dispatch - a listener mutated it directly, independent
         *  of (and not necessarily visible in) the already-serialized tag. */
        record RejectedLiveWitnessChangedDuringEventHandling() implements CheckpointResult {}

        /** [coordinator review 51306] The serialized chunk's own recorded {@code xPos}/{@code
         *  zPos} do not match the expected {@link ChunkPos} - checked independently of the witness
         *  itself, both after save-event processing and again on readback. */
        record RejectedCoordinateMismatch(String detail) implements CheckpointResult {}

        /** [coordinator review 51311] The serialized chunk's own recorded {@code Status} is not
         *  {@link net.minecraft.world.level.chunk.status.ChunkStatus#FULL} - a listener could
         *  retain correct coordinates and witness while changing only this, and without this
         *  check the snapshot would be written/read back and reported {@code Checkpointed} even
         *  though it no longer represents the FULL chunk this operation claims to checkpoint.
         *  Checked both after save-event processing and again on readback, using the engine's own
         *  registry representation of {@code ChunkStatus.FULL} rather than a guessed literal. */
        record RejectedStatusMismatch(String detail) implements CheckpointResult {}

        /** The witness was missing or malformed in the SERIALIZED tag immediately after {@code
         *  ChunkDataEvent.Save} finished (a serialization exception inside the attachment system
         *  is swallowed there, not thrown - see this class's own doc) - rejected before any write
         *  is ever submitted. */
        record RejectedAttachmentOmittedAfterSave(String detail) implements CheckpointResult {}

        /** Submission to, or completion of, the real {@link ChunkMap#write} future failed. */
        record RejectedWriteFailed(IOException cause) implements CheckpointResult {}

        /** {@link ChunkMap#flushWorker()} itself failed. */
        record RejectedFlushFailed(RuntimeException cause) implements CheckpointResult {}

        /** The write+flush reported success, but an independent readback through the same worker
         *  either found nothing, or found a witness/coordinates that do not match this call's own
         *  - e.g. a coalesced LATER write (from this same call or an ordinary concurrent save)
         *  superseded it, or an EARLIER write coalesced ahead of it and this call's own bytes were
         *  never the ones actually persisted. Never silently retried with the same stale bytes -
         *  the next real save attempt (ordinary or another explicit checkpoint) captures whatever
         *  is then current. */
        record RejectedReadbackMismatch(String detail) implements CheckpointResult {}
    }

    /** [coordinator review 51308] The real write/flush/read operations this class performs,
     *  factored behind a minimal seam purely so tests can inject deterministic failing/pending
     *  outcomes without touching this GameTest world's own real, shared region storage. {@link
     *  #real()} - used by every actual production call - delegates directly to {@link ChunkMap},
     *  with no behavior difference from calling it inline. */
    interface ChunkCheckpointSeam {
        LevelChunk currentChunk(ServerLevel level, ChunkPos pos);

        CompletableFuture<Void> write(ChunkMap chunkMap, ChunkPos pos, CompoundTag data);

        void flush(ChunkMap chunkMap);

        CompletableFuture<Optional<CompoundTag>> read(ChunkMap chunkMap, ChunkPos pos);

        static ChunkCheckpointSeam real() {
            return new ChunkCheckpointSeam() {
                @Override
                public LevelChunk currentChunk(ServerLevel level, ChunkPos pos) {
                    return level.getChunkSource().getChunkNow(pos.x, pos.z);
                }

                @Override
                public CompletableFuture<Void> write(ChunkMap chunkMap, ChunkPos pos, CompoundTag data) {
                    return chunkMap.write(pos, data);
                }

                @Override
                public void flush(ChunkMap chunkMap) {
                    chunkMap.flushWorker();
                }

                @Override
                public CompletableFuture<Optional<CompoundTag>> read(ChunkMap chunkMap, ChunkPos pos) {
                    return chunkMap.read(pos);
                }
            };
        }
    }

    /** Performs ONE explicit, synchronous, server-thread-only checkpoint of exactly the chunk
     *  currently loaded (at {@link net.minecraft.world.level.chunk.status.ChunkStatus#FULL}) at
     *  {@code chunkPos} in {@code level}. Blocks the calling thread until the real write, a real
     *  {@link ChunkMap#flushWorker()}, and an independent readback all complete or fail - see this
     *  class's own doc for exactly what that does and does not prove. Never touches construction,
     *  debit, settlement, or recovery state; this is bounded to the checkpoint operation alone. */
    public static CheckpointResult checkpointLoadedChunk(ServerLevel level, ChunkPos chunkPos) {
        return checkpointLoadedChunk(level, chunkPos, UUID.randomUUID(), ChunkCheckpointSeam.real());
    }

    /** [stage 2, coordinator design 51327] Same operation, using {@code operationId} - already
     *  durable in {@code TownStorage}'s own construction operation ledger (see {@link
     *  TownConstructionOperation}) BEFORE this call - as this checkpoint's own witness {@code
     *  checkpointId}, instead of minting a fresh one. This is what lets a chunk's own disk
     *  snapshot be linked back to the EXACT operation that produced it after a restart, rather
     *  than an unlinked UUID minted after the fact. Every existing no-arg-identity caller/test is
     *  completely unaffected - this is strictly an additional entry point, sharing the exact same
     *  validated sequence below. */
    public static CheckpointResult checkpointLoadedChunkForOperation(ServerLevel level, ChunkPos chunkPos, UUID operationId) {
        return checkpointLoadedChunk(level, chunkPos, operationId, ChunkCheckpointSeam.real());
    }

    /** [coordinator review 51308, test seam] Same operation, with the actual write/flush/read
     *  (and current-chunk lookup, for deterministic stale-identity testing) routed through {@code
     *  seam} instead of directly through {@link ChunkMap}. Package-private; every production
     *  caller uses one of the public overloads above, which always pass {@link
     *  ChunkCheckpointSeam#real()}. */
    static CheckpointResult checkpointLoadedChunk(ServerLevel level, ChunkPos chunkPos, ChunkCheckpointSeam seam) {
        return checkpointLoadedChunk(level, chunkPos, UUID.randomUUID(), seam);
    }

    /** [stage 2, test seam] Same operation-linked checkpoint as {@link
     *  #checkpointLoadedChunkForOperation}, with the actual write/flush/read routed through
     *  {@code seam} - package-private, for deterministic testing of the operation-identity path
     *  the same way {@link #checkpointLoadedChunk(ServerLevel, ChunkPos, ChunkCheckpointSeam)}
     *  already lets tests exercise the ordinary path. */
    static CheckpointResult checkpointLoadedChunk(ServerLevel level, ChunkPos chunkPos, UUID checkpointId, ChunkCheckpointSeam seam) {
        InProgressKey key = new InProgressKey(level, chunkPos);
        if (!IN_PROGRESS.add(key)) {
            // [coordinator review 51306] A nested checkpoint for the exact same chunk is already
            // running further up this same call stack - reject immediately, before touching
            // anything, rather than letting it race the outer call's own in-flight write.
            return new CheckpointResult.RejectedReentrantCheckpoint();
        }
        try {
            LevelChunk before = seam.currentChunk(level, chunkPos);
            // [coordinator review 51311] "validate the captured LevelChunk belongs to that level
            // before effects": LevelChunk#getLevel() returns the real, actual Level instance the
            // engine itself set on it - compared here by reference, consistent with this guard's
            // own level-identity scoping above.
            if (before == null || before.getLevel() != level) {
                return new CheckpointResult.RejectedNoLoadedChunk();
            }

            Witness witness = new Witness(level.dimension(), checkpointId);
            // ChunkAccess#setData marks the chunk unsaved itself - the NORMAL engine dirty
            // mechanism, not a side channel - so this witness also rides along on any ordinary
            // later autosave.
            before.setData(WITNESS_ATTACHMENT.get(), witness);

            // Preserve normal POI flush semantics - the exact first step ChunkMap's own private
            // save() performs, per the matched source, before it ever serializes anything.
            level.getPoiManager().flush(chunkPos);

            CompoundTag data = ChunkSerializer.write(level, before);
            NeoForge.EVENT_BUS.post(new ChunkDataEvent.Save(before, level, data));

            // [coordinator design 51303] "validate expected witness AFTER all save listeners":
            // this event dispatch is synchronous - NeoForge.EVENT_BUS.post(...) does not return
            // until every registered listener (including any future third-party one) has run - so
            // checking `data` here really is after all of them, unlike checking from inside one's
            // own listener (which can only ever observe what IT itself just wrote).
            CheckpointResult.RejectedCoordinateMismatch coordinateRejection = validateChunkCoordinates(data, chunkPos);
            if (coordinateRejection != null) {
                return coordinateRejection;
            }
            CheckpointResult.RejectedStatusMismatch statusRejection = validateChunkIsFull(data);
            if (statusRejection != null) {
                return statusRejection;
            }
            CheckpointResult.RejectedAttachmentOmittedAfterSave afterSaveRejection = validateSerializedWitness(data, witness);
            if (afterSaveRejection != null) {
                return afterSaveRejection;
            }

            LevelChunk after = seam.currentChunk(level, chunkPos);
            if (after != before) {
                return new CheckpointResult.RejectedStaleChunkIdentity();
            }

            // [coordinator review 51306] Independent of the serialized-tag check above: a listener
            // could have mutated the LIVE attachment directly (never touching the already-built
            // `data` tag at all). Re-read it from the live chunk, not the serialized bytes.
            Witness liveAfterEvent = before.getExistingDataOrNull(WITNESS_ATTACHMENT.get());
            if (!witness.equals(liveAfterEvent)) {
                return new CheckpointResult.RejectedLiveWitnessChangedDuringEventHandling();
            }

            // Private snapshot copy, exactly as vanilla's own SavedData#save(File, Provider) and
            // this mod's TownStorageDurableWriter both already do ("to handle mods that just
            // append an existing Tag to the Compound") - taken only now, after validation, before
            // handing it to the writer.
            CompoundTag snapshot = data.copy();

            ChunkMap chunkMap = level.getChunkSource().chunkMap;
            CompletableFuture<Void> writeFuture;
            try {
                writeFuture = seam.write(chunkMap, chunkPos, snapshot);
            } catch (RuntimeException submissionRejected) {
                return new CheckpointResult.RejectedWriteFailed(new IOException("submission to the chunk writer was rejected", submissionRejected));
            }

            // [coordinator design 51303] "do not pump managed tasks while waiting or wait on an
            // executor that needs the blocked thread": IOWorker's own mailbox runs on
            // Util.ioPool(), a separate thread pool from the server's main thread - blocking THIS
            // thread on that future cannot self-deadlock.
            try {
                writeFuture.join();
            } catch (CompletionException joinFailure) {
                return new CheckpointResult.RejectedWriteFailed(toIoException(joinFailure));
            }

            try {
                seam.flush(chunkMap);
            } catch (RuntimeException flushFailure) {
                return new CheckpointResult.RejectedFlushFailed(flushFailure);
            }

            // [coordinator design 51303] "Acknowledge only after write AND flush AND validated
            // readback, not future alone": IOWorker#store coalesces same-chunk pending writes and
            // shares one future between them - a successfully completed future proves only that
            // SOME write for this chunk landed, never specifically this call's own bytes. Only an
            // independent readback, through the same worker, after the flush, actually proves it.
            CompoundTag readBack;
            try {
                Optional<CompoundTag> read = seam.read(chunkMap, chunkPos).get();
                if (read.isEmpty()) {
                    return new CheckpointResult.RejectedReadbackMismatch("no chunk data found on readback");
                }
                readBack = read.get();
            } catch (InterruptedException | ExecutionException readFailure) {
                return new CheckpointResult.RejectedReadbackMismatch("readback itself failed: " + readFailure);
            }

            CheckpointResult.RejectedCoordinateMismatch readbackCoordinateRejection = validateChunkCoordinates(readBack, chunkPos);
            if (readbackCoordinateRejection != null) {
                return new CheckpointResult.RejectedReadbackMismatch(readbackCoordinateRejection.detail());
            }
            CheckpointResult.RejectedStatusMismatch readbackStatusRejection = validateChunkIsFull(readBack);
            if (readbackStatusRejection != null) {
                return new CheckpointResult.RejectedReadbackMismatch(readbackStatusRejection.detail());
            }
            CheckpointResult.RejectedAttachmentOmittedAfterSave readbackWitnessRejection = validateSerializedWitness(readBack, witness);
            if (readbackWitnessRejection != null) {
                return new CheckpointResult.RejectedReadbackMismatch(readbackWitnessRejection.detail());
            }

            return new CheckpointResult.Checkpointed(checkpointId);
        } finally {
            // [coordinator review 51306] Released on EVERY exit path, including an uncaught
            // exception - a stuck guard would permanently block all future checkpoints of this
            // exact chunk, which would itself be a correctness bug.
            IN_PROGRESS.remove(key);
        }
    }

    /** [coordinator review 51306] Validates the serialized chunk's own recorded coordinates
     *  against {@code expected}, independent of the witness itself - a listener could alter
     *  {@code xPos}/{@code zPos} without ever touching the witness key. */
    private static CheckpointResult.RejectedCoordinateMismatch validateChunkCoordinates(CompoundTag serializedChunk, ChunkPos expected) {
        if (!serializedChunk.contains("xPos", Tag.TAG_INT) || !serializedChunk.contains("zPos", Tag.TAG_INT)) {
            return new CheckpointResult.RejectedCoordinateMismatch("serialized chunk is missing its own xPos/zPos");
        }
        int x = serializedChunk.getInt("xPos");
        int z = serializedChunk.getInt("zPos");
        if (x != expected.x || z != expected.z) {
            return new CheckpointResult.RejectedCoordinateMismatch(
                    "serialized chunk coordinates (" + x + ", " + z + ") do not match the expected " + expected);
        }
        return null;
    }

    /** [coordinator review 51311] Validates the serialized chunk's own recorded {@code Status}
     *  is exactly {@code ChunkStatus.FULL}, independent of coordinates and witness - a listener
     *  could retain correct coordinates and witness while changing only this. Uses the engine's
     *  own registry representation ({@code BuiltInRegistries.CHUNK_STATUS.getKey(ChunkStatus.FULL)})
     *  rather than a guessed literal string, matching {@link ChunkSerializer#write}'s own
     *  serialization of this exact field. */
    private static CheckpointResult.RejectedStatusMismatch validateChunkIsFull(CompoundTag serializedChunk) {
        if (!serializedChunk.contains("Status", Tag.TAG_STRING)) {
            return new CheckpointResult.RejectedStatusMismatch("serialized chunk is missing its own Status");
        }
        String expected = net.minecraft.core.registries.BuiltInRegistries.CHUNK_STATUS
                .getKey(net.minecraft.world.level.chunk.status.ChunkStatus.FULL).toString();
        String actual = serializedChunk.getString("Status");
        if (!expected.equals(actual)) {
            return new CheckpointResult.RejectedStatusMismatch("serialized chunk status is \"" + actual + "\", expected \"" + expected + "\"");
        }
        return null;
    }

    /** Decodes whatever {@code serializedChunk} ACTUALLY contains under the attachment system's
     *  own NBT key - never trusting it before checking, and never going through {@link
     *  IAttachmentHolder#getData} (which would materialize a default, and which this standalone
     *  CompoundTag does not even have - there is no live holder here at all, by design, so the
     *  check is purely over the serialized bytes). Empty when missing or malformed; the specific
     *  reason is logged, never thrown (matching {@link AttachmentHolder}'s own swallow-and-skip
     *  behavior for a single attachment's own (de)serialization). Package-private so tests can
     *  independently verify real on-disk content the same way this class itself does, without
     *  duplicating the decode logic. */
    static Optional<Witness> decodeWitness(CompoundTag serializedChunk) {
        if (!serializedChunk.contains(AttachmentHolder.ATTACHMENTS_NBT_KEY, Tag.TAG_COMPOUND)) {
            return Optional.empty();
        }
        CompoundTag attachments = serializedChunk.getCompound(AttachmentHolder.ATTACHMENTS_NBT_KEY);
        String key = NeoForgeRegistries.ATTACHMENT_TYPES.getKey(WITNESS_ATTACHMENT.get()).toString();
        if (!attachments.contains(key, Tag.TAG_COMPOUND)) {
            return Optional.empty();
        }
        var decoded = Witness.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE, attachments.get(key));
        if (decoded.isError()) {
            io.github.brooswitminecraft.dynamicvehicles.DynamicVehiclesMod.LOGGER.error(
                    "TownStorageChunkCheckpoint witness present but failed to decode: {}", decoded.error().orElseThrow().message());
            return Optional.empty();
        }
        return Optional.of(decoded.getOrThrow());
    }

    /** Checks whatever {@code serializedChunk} ACTUALLY contains against {@code expected}, via
     *  {@link #decodeWitness}. Returns {@code null} when it matches exactly; otherwise an
     *  explicit rejection record carrying what was actually found (or not found). */
    private static CheckpointResult.RejectedAttachmentOmittedAfterSave validateSerializedWitness(CompoundTag serializedChunk, Witness expected) {
        Optional<Witness> actual = decodeWitness(serializedChunk);
        if (actual.isEmpty()) {
            return new CheckpointResult.RejectedAttachmentOmittedAfterSave("witness absent or malformed - serialization was likely swallowed or never happened");
        }
        if (!actual.get().equals(expected)) {
            return new CheckpointResult.RejectedAttachmentOmittedAfterSave("witness present but does not match - expected " + expected + ", found " + actual.get());
        }
        return null;
    }

    private static IOException toIoException(Throwable t) {
        Throwable cause = t instanceof CompletionException && t.getCause() != null ? t.getCause() : t;
        if (cause instanceof IOException io) {
            return io;
        }
        return new IOException(cause);
    }
}
