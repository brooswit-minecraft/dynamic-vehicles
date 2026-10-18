package io.github.brooswitminecraft.dynamicvehicles;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.level.block.state.BlockState;

/** Shared cuboid-from-vanilla-blocks drawing helper, split out of {@link CarRenderer}'s own private
 * {@code block} method so {@link MotorboatRenderer}, {@link ChopperRenderer} and {@link BiplaneRenderer}
 * can build a recognizable silhouette the same way every car does, with no new texture assets. */
final class VehicleBoxRenderer {
    private VehicleBoxRenderer() {}

    static void block(PoseStack pose, MultiBufferSource buffers, int light, BlockState state,
            float x, float y, float z, float sx, float sy, float sz) {
        pose.pushPose();
        pose.translate(x, y, z);
        pose.scale(sx, sy, sz);
        Minecraft.getInstance().getBlockRenderer().renderSingleBlock(state, pose, buffers, light, OverlayTexture.NO_OVERLAY);
        pose.popPose();
    }
}
