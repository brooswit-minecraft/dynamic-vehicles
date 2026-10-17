package io.github.brooswitminecraft.dynamicvehicles.delivery;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

import io.github.brooswitminecraft.dynamicvehicles.DispatcherOfferConfig;

/**
 * MINECRAFT-323: the existing {@link DispatcherBlocks#DISPATCH_BOARD} gains dispatcher
 * interaction. Right-clicking it opens the same offers menu, through the same
 * {@link DispatcherOfferService} the Dispatcher villager interaction ({@link DispatcherInteractionHandler})
 * uses, as a fixed level-one ({@link #FIXED_LEVEL}) dispatch source — the board itself never
 * progresses levels the way a villager does. Each placed board gets its own persisted
 * {@link DispatchBoardBlockEntity#getOrCreateBoardId() UUID}, which together with the current
 * Minecraft day seeds that board's deterministic daily jobs (MINECRAFT-321's model), exactly as
 * a Dispatcher villager's own UUID does for its jobs.
 *
 * <p>Still the Dispatcher's job-site {@code PoiType} block (MINECRAFT-196) — adding
 * {@link EntityBlock}/right-click behavior here does not add any new {@link BlockState}
 * properties, so {@link DispatcherPoiTypes#DISPATCH_BOARD}'s existing state-set registration is
 * unaffected.
 */
public final class DispatchBoardBlock extends Block implements EntityBlock {

    /** "Fixed level-one dispatch source": never a villager's own progressing profession level. */
    public static final int FIXED_LEVEL = 1;

    public DispatchBoardBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new DispatchBoardBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }
        if (!(level.getBlockEntity(pos) instanceof DispatchBoardBlockEntity boardEntity)) {
            return InteractionResult.PASS;
        }

        // MINECRAFT-110 AC2, mirrored from DispatcherInteractionHandler: one contract at a time.
        if (DeliveryContracts.hasActive(serverPlayer)) {
            serverPlayer.sendSystemMessage(Component.translatable("message.dynamicvehicles.dispatcher.already_active"));
            return InteractionResult.CONSUME;
        }

        UUID boardId = boardEntity.getOrCreateBoardId();
        List<DestinationOffer> offers = DispatcherOfferService.generateOffers(
                serverLevel, pos, boardId, serverLevel.getGameTime(), FIXED_LEVEL, DispatcherOfferConfig.toOfferConfig());

        double dangerMin = DispatcherOfferConfig.DANGER_MIN.get();
        double dangerMax = DispatcherOfferConfig.DANGER_MAX.get();
        List<DispatcherOfferRow> rows = DispatcherOfferMenu.toRows(offers);

        DispatcherMenuAnchor anchor = openingPlayer -> {
            boolean sameLevel = openingPlayer.level() == serverLevel;
            boolean boardPresent = sameLevel && serverLevel.getBlockState(pos).is(DispatcherBlocks.DISPATCH_BOARD.get());
            double distanceSquared = sameLevel ? openingPlayer.distanceToSqr(Vec3.atCenterOf(pos)) : Double.MAX_VALUE;
            return DispatcherMenuValidity.isValid(boardPresent, true, sameLevel, distanceSquared);
        };

        serverPlayer.openMenu(
                new DispatcherOfferMenuProvider(
                        offers, getName(), dangerMin, dangerMax, boardId, anchor),
                buf -> DispatcherOfferMenu.writeExtraData(buf, dangerMin, dangerMax, rows));

        return InteractionResult.CONSUME;
    }
}
