package io.github.brooswitminecraft.dynamicvehicles;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** MINECRAFT-380: a low hull, a short windscreen and a dark engine block at the stern, built from vanilla
 * blocks the same way {@link CarRenderer} builds every car's silhouette. */
public class MotorboatRenderer extends EntityRenderer<MotorboatEntity> {
    private static final BlockState HULL = Blocks.OAK_PLANKS.defaultBlockState();
    private static final BlockState TRIM = Blocks.WHITE_CONCRETE.defaultBlockState();
    private static final BlockState WINDSCREEN = Blocks.GLASS.defaultBlockState();
    private static final BlockState ENGINE = Blocks.BLACK_CONCRETE.defaultBlockState();

    public MotorboatRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(MotorboatEntity boat, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        pose.pushPose();
        pose.translate(0.0, MotorboatEntity.halfHeight(), 0.0);
        // MINECRAFT-410: once the rigid body is driving this boat, draw its full orientation (pitch/roll
        // included, interpolated the same way CarRenderer does for the car) instead of yaw-only -- so the
        // rider/observer rendering agrees with what the 4-point hull buoyancy actually computed.
        if (boat.isRigidBody()) {
            pose.mulPose(boat.renderOrientation(partialTick));
        } else {
            pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-yaw));
        }
        float hx = (float) MotorboatEntity.halfWidth();
        float hy = (float) MotorboatEntity.halfHeight();
        float hz = (float) MotorboatEntity.halfLength();
        VehicleBoxRenderer.block(pose, buffers, light, HULL, -hx, -hy, -hz, 2 * hx, hy, 2 * hz);
        VehicleBoxRenderer.block(pose, buffers, light, TRIM, -hx, 0.0f, -hz, 2 * hx, 0.15f, 2 * hz);
        VehicleBoxRenderer.block(pose, buffers, light, WINDSCREEN, -hx * 0.6f, 0.1f, hz * 0.1f, hx * 1.2f, hy * 0.8f, 0.35f);
        VehicleBoxRenderer.block(pose, buffers, light, ENGINE, -hx * 0.45f, -hy * 0.2f, -hz - 0.1f, hx * 0.9f, hy * 0.9f, 0.3f);
        pose.popPose();
    }

    @Override
    public ResourceLocation getTextureLocation(MotorboatEntity boat) {
        return ResourceLocation.withDefaultNamespace("textures/block/oak_planks.png");
    }
}
