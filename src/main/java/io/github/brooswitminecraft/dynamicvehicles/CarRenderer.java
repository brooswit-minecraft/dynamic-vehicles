package io.github.brooswitminecraft.dynamicvehicles;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Placeholder look for the first car, built from vanilla blocks (a body, a
 * cabin and four wheels) until a real model exists.
 */
public class CarRenderer extends EntityRenderer<CarEntity> {
    private static final BlockState BODY = Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState();
    private static final BlockState CABIN = Blocks.GLASS.defaultBlockState();
    private static final BlockState WHEEL = Blocks.BLACK_CONCRETE.defaultBlockState();

    public CarRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(CarEntity car, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        pose.pushPose();
        pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(180.0f - yaw));
        block(pose, buffers, light, BODY, -0.9f, 0.25f, -1.5f, 1.8f, 0.45f, 3.0f);
        block(pose, buffers, light, CABIN, -0.7f, 0.7f, -0.7f, 1.4f, 0.4f, 1.5f);
        for (double[] wheel : CarEntity.WHEELS) {
            block(pose, buffers, light, WHEEL, (float) wheel[0] - 0.15f, 0.0f, (float) wheel[1] - 0.3f, 0.3f, 0.5f, 0.6f);
        }
        pose.popPose();
        super.render(car, yaw, partialTick, pose, buffers, light);
    }

    private static void block(PoseStack pose, MultiBufferSource buffers, int light, BlockState state,
            float x, float y, float z, float sx, float sy, float sz) {
        pose.pushPose();
        pose.translate(x, y, z);
        pose.scale(sx, sy, sz);
        Minecraft.getInstance().getBlockRenderer().renderSingleBlock(state, pose, buffers, light, OverlayTexture.NO_OVERLAY);
        pose.popPose();
    }

    @Override
    public ResourceLocation getTextureLocation(CarEntity car) {
        return ResourceLocation.withDefaultNamespace("textures/block/white_concrete.png");
    }
}
