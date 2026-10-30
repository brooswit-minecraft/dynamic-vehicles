package io.github.brooswitminecraft.dynamicvehicles.town;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public final class TownDonationBoxBlock extends Block implements EntityBlock {
    public TownDonationBoxBlock(Properties properties) { super(properties); }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new TownDonationBoxEntity(pos, state); }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        return level.isClientSide() ? InteractionResult.SUCCESS : InteractionResult.PASS;
    }
    @Override protected net.minecraft.world.ItemInteractionResult useItemOn(net.minecraft.world.item.ItemStack held, BlockState state, Level level, BlockPos pos,
            Player player, net.minecraft.world.InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide()) return net.minecraft.world.ItemInteractionResult.SUCCESS;
        if (!(level instanceof ServerLevel server) || !(level.getBlockEntity(pos) instanceof TownDonationBoxEntity box)) return net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        return box.accept(server, held) ? net.minecraft.world.ItemInteractionResult.CONSUME : net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }
    @Override public void setPlacedBy(Level level, BlockPos pos, BlockState state, net.minecraft.world.entity.LivingEntity placer, net.minecraft.world.item.ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level instanceof ServerLevel server && level.getBlockEntity(pos) instanceof TownDonationBoxEntity box) box.bind(server);
    }
    @Override public void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moved) {
        if (!state.is(replacement.getBlock()) && level instanceof ServerLevel server
                && level.getBlockEntity(pos) instanceof TownDonationBoxEntity box && !box.retireAfterRemoval(server)) {
            // Do not silently discard an escrow/pending receipt.  The vanilla replacement has
            // already begun, so leave the persisted binding active: any copied/replaced BE is
            // quarantined and the operation remains recoverable for explicit repair.
        }
        super.onRemove(state, level, pos, replacement, moved);
    }
}
