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
 * Placeholder look for the car, built from vanilla blocks in the body frame of
 * {@link CarGeometry}: a chassis and cabin that exactly fill the physics box and
 * four wheels that sit where the suspension rays end, so the wheels touch the
 * ground at rest.
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
        if (car.isRigidBody()) {
            // The entity origin is the bottom centre of the box; the body frame's origin is its centre.
            pose.translate(0.0, CarGeometry.HALF_Y, 0.0);
            pose.mulPose(car.renderOrientation(partialTick));
        } else {
            // Simple model: the entity origin is on the ground, the body centre is a ride height above it.
            pose.translate(0.0, CarGeometry.RIDE_HEIGHT, 0.0);
            pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-yaw));
        }
        float hx = (float) CarGeometry.HALF_X;
        float hy = (float) CarGeometry.HALF_Y;
        float hz = (float) CarGeometry.HALF_Z;
        // Chassis and cabin together fill the physics box exactly (1.9 x 1.0 x 3.0).
        block(pose, buffers, light, BODY, -hx, -hy, -hz, 2 * hx, 0.55f, 2 * hz);
        block(pose, buffers, light, CABIN, -0.75f, -hy + 0.55f, -0.6f, 1.5f, 2 * hy - 0.55f, 1.5f);
        float r = (float) CarGeometry.WHEEL_RADIUS;
        float w = (float) CarGeometry.WHEEL_WIDTH;
        float cy = (float) CarGeometry.wheelCentreY();
        for (double[] mount : CarGeometry.MOUNTS) {
            block(pose, buffers, light, WHEEL, (float) mount[0] - w / 2, cy - r, (float) mount[2] - r, w, 2 * r, 2 * r);
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
