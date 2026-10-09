package io.github.brooswitminecraft.dynamicvehicles;

/**
 * Pure per-wheel client-sync maths: packing each wheel's suspension travel (m), steer angle (rad)
 * and spin rate (rad/s) into one flat {@code float[]}, and interpolating between two received
 * frames. Free of Minecraft types so it is unit tested directly; the CompoundTag/EntityData
 * plumbing that carries this over the network lives in {@link CarEntity}, which is not.
 */
final class WheelSync {
    /** travel, steerAngle, spin - in that order, per wheel. */
    static final int FIELDS_PER_WHEEL = 3;

    private WheelSync() {}

    /** Packs every wheel's travel/steerAngle/spin into one flat frame, wheel 0 first. */
    static float[] encode(double[] travel, double[] steerAngle, double[] spin) {
        int wheels = travel.length;
        float[] packed = new float[wheels * FIELDS_PER_WHEEL];
        for (int wheel = 0; wheel < wheels; wheel++) {
            packed[wheel * FIELDS_PER_WHEEL] = (float) travel[wheel];
            packed[wheel * FIELDS_PER_WHEEL + 1] = (float) steerAngle[wheel];
            packed[wheel * FIELDS_PER_WHEEL + 2] = (float) spin[wheel];
        }
        return packed;
    }

    static int wheelCount(float[] packed) {
        return packed.length / FIELDS_PER_WHEEL;
    }

    static float travel(float[] packed, int wheel) {
        return packed[wheel * FIELDS_PER_WHEEL];
    }

    static float steerAngle(float[] packed, int wheel) {
        return packed[wheel * FIELDS_PER_WHEEL + 1];
    }

    static float spin(float[] packed, int wheel) {
        return packed[wheel * FIELDS_PER_WHEEL + 2];
    }

    /**
     * Per-field linear interpolation between two packed frames received at different times, t in
     * [0,1]. A wheel-count mismatch (e.g. a reload mid-flight swaps the vehicle under the same
     * entity) returns {@code to} untouched rather than guessing how to blend frames of different
     * shapes.
     */
    static float[] lerp(float[] from, float[] to, float t) {
        if (from.length != to.length) {
            return to;
        }
        float[] out = new float[from.length];
        for (int i = 0; i < from.length; i++) {
            out[i] = from[i] + (to[i] - from[i]) * t;
        }
        return out;
    }
}
