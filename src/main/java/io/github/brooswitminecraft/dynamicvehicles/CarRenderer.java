package io.github.brooswitminecraft.dynamicvehicles;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Renders every vehicle, built from vanilla blocks in the body frame of {@link VehicleSpec}: each
 * vehicle type gets its own silhouette (see the branches below, MINECRAFT-174 for the car's), and every
 * one shares four wheels that sit where the suspension rays end, so the wheels touch the ground at rest.
 */
public class CarRenderer extends EntityRenderer<CarEntity> {
    private static final BlockState BODY = Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState();
    private static final BlockState TRUCK_BODY = Blocks.ORANGE_CONCRETE.defaultBlockState();
    private static final BlockState RACE_BODY = Blocks.RED_CONCRETE.defaultBlockState();
    private static final BlockState DRIFT_BODY = Blocks.YELLOW_CONCRETE.defaultBlockState();
    private static final BlockState MUSCLE_BODY = Blocks.BLACK_CONCRETE.defaultBlockState();
    private static final BlockState MUSCLE_STRIPE = Blocks.WHITE_CONCRETE.defaultBlockState();
    private static final BlockState CRAWLER_BODY = Blocks.GREEN_CONCRETE.defaultBlockState();
    private static final BlockState CRAWLER_ROLL_CAGE = Blocks.IRON_BARS.defaultBlockState();
    private static final BlockState MONSTER_BODY = Blocks.PURPLE_CONCRETE.defaultBlockState();
    private static final BlockState MONSTER_ROLL_CAGE = Blocks.IRON_BARS.defaultBlockState();
    private static final BlockState BED = Blocks.GRAY_CONCRETE.defaultBlockState();
    private static final BlockState CABIN = Blocks.GLASS.defaultBlockState();
    private static final BlockState WHEEL = Blocks.BLACK_CONCRETE.defaultBlockState();
    private static final BlockState CAR_ROOF = Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState();
    private static final BlockState CAR_FENDER = Blocks.GRAY_CONCRETE.defaultBlockState();
    private static final BlockState CAR_GRILLE = Blocks.BLACKSTONE.defaultBlockState();
    private static final BlockState CAR_BUMPER = Blocks.SMOOTH_QUARTZ.defaultBlockState();
    private static final BlockState CAR_TAILLIGHT = Blocks.RED_STAINED_GLASS.defaultBlockState();

    public CarRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(CarEntity car, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        pose.pushPose();
        if (car.isRigidBody()) {
            // The entity origin is the bottom centre of the box; the body frame's origin is its centre.
            pose.translate(0.0, car.spec().halfY(), 0.0);
            pose.mulPose(car.renderOrientation(partialTick));
        } else {
            // Simple model: the entity origin is on the ground, the body centre is a ride height above it.
            pose.translate(0.0, car.spec().rideHeight(), 0.0);
            pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-yaw));
        }
        VehicleSpec spec = car.spec();
        float hx = (float) spec.halfX();
        float hy = (float) spec.halfY();
        float hz = (float) spec.halfZ();
        float r = (float) spec.wheelRadius();
        float w = (float) spec.wheelWidth();
        float cy = (float) spec.wheelCentreY();
        if (spec == VehicleSpec.CAR) {
            // A proper sedan silhouette, still exactly filling the fixed CAR body box (1.9 x 1.0 x 3.0,
            // unchanged from CarGeometry/VehicleSpec): a lower chassis, a glazed cabin capped by a
            // body-colored roofline, a dark front grille between chrome bumpers, taillights at the rear
            // corners, and dark fender flares over each wheel so the 4-seat cabin (MINECRAFT-170/182's
            // seats, untouched here) reads as a real car instead of a block and a glass box.
            block(pose, buffers, light, BODY, -hx, -hy, -hz, 2 * hx, 0.5f, 2 * hz);
            block(pose, buffers, light, CABIN, -0.75f, -hy + 0.5f, -0.6f, 1.5f, 2 * hy - 0.55f, 1.5f);
            block(pose, buffers, light, CAR_ROOF, -0.8f, hy - 0.05f, -0.65f, 1.6f, 0.05f, 1.6f);
            block(pose, buffers, light, CAR_BUMPER, -hx + 0.05f, -hy, hz - 0.08f, 2 * hx - 0.1f, 0.15f, 0.08f);
            block(pose, buffers, light, CAR_BUMPER, -hx + 0.05f, -hy, -hz, 2 * hx - 0.1f, 0.15f, 0.08f);
            block(pose, buffers, light, CAR_GRILLE, -0.45f, -hy + 0.15f, hz - 0.04f, 0.9f, 0.27f, 0.04f);
            block(pose, buffers, light, CAR_TAILLIGHT, hx - 0.3f, -hy + 0.15f, -hz, 0.2f, 0.2f, 0.04f);
            block(pose, buffers, light, CAR_TAILLIGHT, -hx + 0.1f, -hy + 0.15f, -hz, 0.2f, 0.2f, 0.04f);
            block(pose, buffers, light, CAR_FENDER, -hx - 0.03f, cy - r - 0.05f, -hz, 0.03f, 2 * r + 0.1f, 2 * hz);
            block(pose, buffers, light, CAR_FENDER, hx, cy - r - 0.05f, -hz, 0.03f, 2 * r + 0.1f, 2 * hz);
        } else if (spec == VehicleSpec.TROPHY) {
            // A low, wide race body with a small glass canopy and a rear wing.
            block(pose, buffers, light, RACE_BODY, -hx, -hy, -hz, 2 * hx, 0.45f, 2 * hz);
            block(pose, buffers, light, CABIN, -0.6f, -hy + 0.45f, -0.2f, 1.2f, 2 * hy - 0.45f, 1.2f);
            block(pose, buffers, light, BED, -hx + 0.1f, hy - 0.1f, -hz, 2 * hx - 0.2f, 0.1f, 0.5f);
        } else if (spec == VehicleSpec.DRIFT) {
            // A low, slim sports body with a small rear spoiler; the cabin sits further back than the car's.
            block(pose, buffers, light, DRIFT_BODY, -hx, -hy, -hz, 2 * hx, 0.4f, 2 * hz);
            block(pose, buffers, light, CABIN, -0.6f, -hy + 0.4f, -0.3f, 1.2f, 2 * hy - 0.4f, 1.3f);
            block(pose, buffers, light, BED, -hx + 0.15f, hy - 0.08f, -hz + 0.1f, 2 * hx - 0.3f, 0.08f, 0.25f);
        } else if (spec == VehicleSpec.MUSCLE) {
            // A long-hooded, short-wheelbase muscle body with a racing stripe down the hood and a low cabin set well back.
            block(pose, buffers, light, MUSCLE_BODY, -hx, -hy, -hz, 2 * hx, 0.55f, 2 * hz);
            block(pose, buffers, light, MUSCLE_STRIPE, -0.15f, -hy + 0.55f, 0.1f, 0.3f, 0.02f, hz - 0.1f);
            block(pose, buffers, light, CABIN, -0.7f, -hy + 0.55f, -0.5f, 1.4f, 2 * hy - 0.55f, 1.3f);
        } else if (spec == VehicleSpec.ROCK_CRAWLER) {
            // A short, boxy body riding high above its wheels, with an open roll cage instead of a closed cabin.
            block(pose, buffers, light, CRAWLER_BODY, -hx, -hy, -hz, 2 * hx, 0.6f, 2 * hz);
            block(pose, buffers, light, CRAWLER_ROLL_CAGE, -hx + 0.1f, -hy + 0.6f, -0.5f, 2 * hx - 0.2f, 2 * hy - 0.6f, 1.0f);
        } else if (spec == VehicleSpec.MONSTER_TRUCK) {
            // A compact cab riding high above its huge wheels, with an open roll cage instead of a closed cabin.
            block(pose, buffers, light, MONSTER_BODY, -hx, -hy, -hz, 2 * hx, 0.65f, 2 * hz);
            block(pose, buffers, light, MONSTER_ROLL_CAGE, -hx + 0.15f, -hy + 0.65f, -0.6f, 2 * hx - 0.3f, 2 * hy - 0.65f, 1.2f);
        } else if (spec == VehicleSpec.TRUCK) {
            // Chassis and cab fill the physics box; an open bed behind the cab.
            block(pose, buffers, light, TRUCK_BODY, -hx, -hy, -hz, 2 * hx, 0.6f, 2 * hz);
            block(pose, buffers, light, TRUCK_BODY, -hx + 0.1f, -hy + 0.6f, 0.3f, 2 * hx - 0.2f, 2 * hy - 0.6f, hz - 0.3f);
            block(pose, buffers, light, CABIN, -hx + 0.2f, -hy + 0.8f, 1.2f, 2 * hx - 0.4f, 0.4f, 0.6f);
            block(pose, buffers, light, BED, -hx, -hy + 0.6f, -hz, 2 * hx, 0.3f, 2 * hz - 2.3f);
        } else {
            // Fallback for any future spec not yet given its own look above: chassis and cabin
            // together fill the physics box exactly, matching the old placeholder car look.
            block(pose, buffers, light, BODY, -hx, -hy, -hz, 2 * hx, 0.55f, 2 * hz);
            block(pose, buffers, light, CABIN, -0.75f, -hy + 0.55f, -0.6f, 1.5f, 2 * hy - 0.55f, 1.5f);
        }
        for (double[] mount : spec.mounts()) {
            block(pose, buffers, light, WHEEL, (float) mount[0] - w / 2, cy - r, (float) mount[2] - r, w, 2 * r, 2 * r);
        }
        if (ClientConfig.HEADLIGHTS.get()) {
            headlights(car.lightsOn(), pose, buffers, light, hx, hy, hz);
        }
        pose.popPose();
        super.render(car, yaw, partialTick, pose, buffers, light);
    }

    /** Two lamps on the nose, glowing when lit, each with a faint fading beam cone (visual only). */
    private static void headlights(boolean on, PoseStack pose, MultiBufferSource buffers, int light, float hx, float hy, float hz) {
        float lampY = -hy + 0.32f;
        float lampZ = hz - 0.04f;
        for (int side = -1; side <= 1; side += 2) {
            float lampX = side * (hx - 0.32f);
            block(pose, buffers, on ? 0xF000F0 : light, on ? Blocks.SEA_LANTERN.defaultBlockState() : Blocks.WHITE_CONCRETE.defaultBlockState(),
                    lampX - 0.14f, lampY - 0.09f, lampZ, 0.28f, 0.18f, 0.08f);
        }
        double length = ClientConfig.HEADLIGHT_BEAM.get();
        if (!on || length <= 0.0) {
            return;
        }
        VertexConsumer beam = buffers.getBuffer(RenderType.lightning());
        Matrix4f m = pose.last().pose();
        float far = (float) length;
        float spreadX = 0.16f * far + 0.2f;
        float spreadY = 0.08f * far + 0.15f;
        for (int side = -1; side <= 1; side += 2) {
            float x0 = side * (hx - 0.32f);
            float z0 = hz + 0.04f;
            // Near end is a small square at the lamp; far end is wider and fully transparent.
            float[][] near = {{x0 - 0.1f, lampY - 0.07f}, {x0 + 0.1f, lampY - 0.07f}, {x0 + 0.1f, lampY + 0.07f}, {x0 - 0.1f, lampY + 0.07f}};
            float[][] end = {{x0 - spreadX, lampY - spreadY}, {x0 + spreadX, lampY - spreadY}, {x0 + spreadX, lampY + spreadY}, {x0 - spreadX, lampY + spreadY}};
            for (int i = 0; i < 4; i++) {
                int j = (i + 1) % 4;
                beamVertex(beam, m, near[i][0], near[i][1], z0, 50);
                beamVertex(beam, m, near[j][0], near[j][1], z0, 50);
                beamVertex(beam, m, end[j][0], end[j][1], z0 + far, 0);
                beamVertex(beam, m, end[i][0], end[i][1], z0 + far, 0);
                // Same quad, reversed, so it shows from either side.
                beamVertex(beam, m, end[i][0], end[i][1], z0 + far, 0);
                beamVertex(beam, m, end[j][0], end[j][1], z0 + far, 0);
                beamVertex(beam, m, near[j][0], near[j][1], z0, 50);
                beamVertex(beam, m, near[i][0], near[i][1], z0, 50);
            }
        }
    }

    private static void beamVertex(VertexConsumer consumer, Matrix4f matrix, float x, float y, float z, int alpha) {
        consumer.addVertex(matrix, x, y, z).setColor(255, 244, 214, alpha);
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
