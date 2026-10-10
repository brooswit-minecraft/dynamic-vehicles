package io.github.brooswitminecraft.dynamicvehicles;

import java.util.List;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Draws every alive {@link SkidMark} {@link SkidMarksClient} is tracking, client-side only
 * (MINECRAFT-225). A flat quad per mark, laid on the ground and oriented along the car's heading when
 * the mark was spawned, textured with a soft dark smudge ({@code skid_mark.png}, see
 * {@code tools/gen_skid_mark_texture.py}) and faded via {@link SkidMarkMath#alpha}. Purely visual: no
 * server involvement, no persistence, nothing synced over the network beyond the slip signal
 * {@link CarEntity} already publishes for the skid sound.
 */
@EventBusSubscriber(modid = DynamicVehiclesMod.MODID, value = Dist.CLIENT)
public final class SkidMarkRenderer {
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(DynamicVehiclesMod.MODID, "textures/misc/skid_mark.png");
    /** Half-length (metres) of a mark quad along the direction of travel. */
    private static final float HALF_LENGTH = 0.3f;
    /** Half-width (metres) of a mark quad, roughly a tire's width. */
    private static final float HALF_WIDTH = 0.15f;

    private SkidMarkRenderer() {}

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        List<SkidMark> marks = SkidMarksClient.aliveMarks(System.currentTimeMillis());
        if (marks.isEmpty()) {
            return;
        }
        // event.getPoseStack() at AFTER_PARTICLES is a fresh identity PoseStack (confirmed against the
        // decompiled NeoForge 21.1.250 LevelRenderer#renderLevel: it allocates `new PoseStack()`, and
        // only the per-entity renders translate it by that entity's own camera-relative offset before
        // popping back to identity — the stack itself never carries the camera's world-position
        // translation, only the separate model-view matrix does). So we push our own camera-relative
        // translation here before drawing world-space mark coordinates, the same way entity rendering
        // offsets by `blockpos - cameraPos` instead of using absolute world coordinates directly.
        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        Vec3 camPos = event.getCamera().getPosition();
        poseStack.translate(-camPos.x(), -camPos.y(), -camPos.z());
        Matrix4f matrix = poseStack.last().pose();
        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        VertexConsumer consumer = buffers.getBuffer(RenderType.entityTranslucent(TEXTURE));
        long now = System.currentTimeMillis();
        for (SkidMark mark : marks) {
            int alpha = (int) Math.round(255.0 * SkidMarkMath.alpha(now - mark.spawnTimeMs(), SkidMarkMath.LIFETIME_MS));
            if (alpha <= 0) {
                continue;
            }
            quad(consumer, matrix, mark, alpha);
        }
        buffers.endBatch(RenderType.entityTranslucent(TEXTURE));
        poseStack.popPose();
    }

    /** One mark's quad, long-ways along the heading it was laid at (see {@link CarEntity#WHEELS}'s own
     * right/forward convention, which this mirrors via {@link SkidMarksClient}). */
    private static void quad(VertexConsumer consumer, Matrix4f matrix, SkidMark mark, int alpha) {
        double yaw = mark.yawRadians();
        float fx = (float) -Math.sin(yaw) * HALF_LENGTH;
        float fz = (float) Math.cos(yaw) * HALF_LENGTH;
        float rx = (float) Math.cos(yaw) * HALF_WIDTH;
        float rz = (float) Math.sin(yaw) * HALF_WIDTH;
        float x = (float) mark.x();
        float y = (float) mark.y();
        float z = (float) mark.z();
        vertex(consumer, matrix, x - fx - rx, y, z - fz - rz, 0, 1, alpha);
        vertex(consumer, matrix, x - fx + rx, y, z - fz + rz, 1, 1, alpha);
        vertex(consumer, matrix, x + fx + rx, y, z + fz + rz, 1, 0, alpha);
        vertex(consumer, matrix, x + fx - rx, y, z + fz - rz, 0, 0, alpha);
    }

    private static void vertex(VertexConsumer consumer, Matrix4f matrix, float x, float y, float z, float u, float v, int alpha) {
        consumer.addVertex(matrix, x, y, z)
                .setColor(255, 255, 255, alpha)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(0xF000F0)
                .setNormal(0, 1, 0);
    }
}
