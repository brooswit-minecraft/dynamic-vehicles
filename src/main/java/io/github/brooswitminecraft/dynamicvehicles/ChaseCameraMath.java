package io.github.brooswitminecraft.dynamicvehicles;

/** Minecraft camera angles represented by a vehicle body's full orientation. */
public final class ChaseCameraMath {
    private ChaseCameraMath() {}

    /**
     * Converts the vehicle's +Z-forward, +Y-up body orientation to Minecraft camera angles.
     * Minecraft's pitch grows while looking down, hence the negated forward Y component.
     */
    public static Angles angles(float x, float y, float z, float w) {
        // The forward (+Z), right (+X), and up (+Y) axes transformed by this quaternion.
        float forwardX = 2.0f * (x * z + w * y);
        float forwardY = 2.0f * (y * z - w * x);
        float forwardZ = 1.0f - 2.0f * (x * x + y * y);
        float rightY = 2.0f * (x * y + w * z);
        float upY = 1.0f - 2.0f * (x * x + z * z);
        float yaw = (float) Math.toDegrees(Math.atan2(-forwardX, forwardZ));
        float pitch = (float) -Math.toDegrees(Math.asin(clamp(forwardY, -1.0f, 1.0f)));
        float roll = (float) Math.toDegrees(Math.atan2(rightY, upY));
        return new Angles(yaw, pitch, roll);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    public record Angles(float yaw, float pitch, float roll) {}
}
