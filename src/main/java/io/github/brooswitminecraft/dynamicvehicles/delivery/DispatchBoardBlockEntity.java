package io.github.brooswitminecraft.dynamicvehicles.delivery;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.UUID;

/**
 * MINECRAFT-323: persists a per-placed-board {@link UUID}, generated once on first use and
 * saved/loaded with the block entity, so the board's deterministic daily jobs
 * ({@link DispatcherOfferSchedule#orderSeed}) stay stable across save/reload for the same
 * placed board, exactly like a Dispatcher villager's own UUID does for its jobs.
 */
public final class DispatchBoardBlockEntity extends BlockEntity {
    private static final String BOARD_ID_KEY = "BoardId";

    private UUID boardId;

    public DispatchBoardBlockEntity(BlockPos pos, BlockState state) {
        super(DispatchBoardBlockEntities.DISPATCH_BOARD.get(), pos, state);
    }

    /** Lazily assigns a UUID on first interaction if this board (e.g. just placed) does not have one yet. */
    public UUID getOrCreateBoardId() {
        if (boardId == null) {
            boardId = UUID.randomUUID();
            setChanged();
        }
        return boardId;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (boardId != null) {
            tag.putUUID(BOARD_ID_KEY, boardId);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        boardId = tag.hasUUID(BOARD_ID_KEY) ? tag.getUUID(BOARD_ID_KEY) : null;
    }
}
