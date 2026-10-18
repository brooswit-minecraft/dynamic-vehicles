package io.github.brooswitminecraft.dynamicvehicles;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** MINECRAFT-380: a glazed cabin, a tail boom and a spinning-looking rotor bar (visual only &mdash; no
 * actual spin animation), built from vanilla blocks the same way {@link CarRenderer} builds every car's
 * silhouette. */
public class ChopperRenderer extends EntityRenderer<ChopperEntity> {
    private static final BlockState BODY = Blocks.RED_CONCRETE.defaultBlockState();
    private static final BlockState CABIN = Blocks.GLASS.defaultBlockState();
    private static final BlockState TAIL = Blocks.GRAY_CONCRETE.defaultBlockState();
    private static final BlockState ROTOR = Blocks.BLACK_CONCRETE.defaultBlockState();
    private static final BlockState MAST = Blocks.IRON_BARS.defaultBlockState();

    public ChopperRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(ChopperEntity chopper, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        pose.pushPose();
        pose.translate(0.0, ChopperEntity.halfHeight(), 0.0);
        pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-yaw));
        float hx = (float) ChopperEntity.halfWidth();
        float hy = (float) ChopperEntity.halfHeight();
        float hz = (float) ChopperEntity.halfLength();
        VehicleBoxRenderer.block(pose, buffers, light, BODY, -hx, -hy, -hz * 0.3f, 2 * hx, 2 * hy, hz * 1.3f);
        VehicleBoxRenderer.block(pose, buffers, light, CABIN, -hx * 0.75f, -hy * 0.5f, hz * 0.15f, hx * 1.5f, hy, hz * 0.8f);
        VehicleBoxRenderer.block(pose, buffers, light, TAIL, -hx * 0.18f, -hy * 0.1f, -hz, hx * 0.36f, hy * 0.5f, hz * 0.7f);
        VehicleBoxRenderer.block(pose, buffers, light, MAST, -0.06f, hy, -0.06f, 0.12f, 0.3f, 0.12f);
        VehicleBoxRenderer.block(pose, buffers, light, ROTOR, -hx * 1.6f, hy + 0.3f, -0.08f, hx * 3.2f, 0.08f, 0.16f);
        pose.popPose();
    }

    @Override
    public ResourceLocation getTextureLocation(ChopperEntity chopper) {
        return ResourceLocation.withDefaultNamespace("textures/block/red_concrete.png");
    }
}
