package io.github.brooswitminecraft.dynamicvehicles;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** MINECRAFT-380: a fuselage, two stacked wings (the "biplane" silhouette), a tail fin and a propeller
 * nose, built from vanilla blocks the same way {@link CarRenderer} builds every car's silhouette. */
public class BiplaneRenderer extends EntityRenderer<BiplaneEntity> {
    private static final BlockState FUSELAGE = Blocks.LIGHT_BLUE_CONCRETE.defaultBlockState();
    private static final BlockState WING = Blocks.WHITE_CONCRETE.defaultBlockState();
    private static final BlockState STRUT = Blocks.BLACK_CONCRETE.defaultBlockState();
    private static final BlockState TAIL = Blocks.WHITE_CONCRETE.defaultBlockState();
    private static final BlockState PROP = Blocks.IRON_BARS.defaultBlockState();

    public BiplaneRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(BiplaneEntity plane, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        pose.pushPose();
        pose.translate(0.0, BiplaneEntity.halfHeight(), 0.0);
        pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-yaw));
        float hx = (float) BiplaneEntity.halfWidth();
        float hy = (float) BiplaneEntity.halfHeight();
        float hz = (float) BiplaneEntity.halfLength();
        VehicleBoxRenderer.block(pose, buffers, light, FUSELAGE, -hx * 0.25f, -hy * 0.6f, -hz, hx * 0.5f, hy * 1.2f, 2 * hz);
        VehicleBoxRenderer.block(pose, buffers, light, WING, -hx, -hy * 0.1f, -hz * 0.15f, 2 * hx, 0.15f, hz * 0.5f);
        VehicleBoxRenderer.block(pose, buffers, light, WING, -hx, hy * 0.5f, -hz * 0.15f, 2 * hx, 0.15f, hz * 0.5f);
        VehicleBoxRenderer.block(pose, buffers, light, STRUT, -hx * 0.4f, -hy * 0.1f, -hz * 0.05f, 0.1f, hy * 0.6f, 0.1f);
        VehicleBoxRenderer.block(pose, buffers, light, STRUT, hx * 0.3f, -hy * 0.1f, -hz * 0.05f, 0.1f, hy * 0.6f, 0.1f);
        VehicleBoxRenderer.block(pose, buffers, light, TAIL, -0.08f, hy * 0.1f, -hz - 0.1f, 0.16f, hy * 0.8f, 0.4f);
        VehicleBoxRenderer.block(pose, buffers, light, PROP, -hx * 0.4f, -hy * 0.1f, hz, hx * 0.8f, hy * 1.0f, 0.08f);
        pose.popPose();
    }

    @Override
    public ResourceLocation getTextureLocation(BiplaneEntity plane) {
        return ResourceLocation.withDefaultNamespace("textures/block/white_concrete.png");
    }
}
