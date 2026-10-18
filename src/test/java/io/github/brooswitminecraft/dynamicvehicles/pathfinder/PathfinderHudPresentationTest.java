package io.github.brooswitminecraft.dynamicvehicles.pathfinder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PathfinderHudPresentationTest {

    @Test
    void sameDimensionShowsTheArrow() {
        assertEquals(PathfinderHudPresentation.Mode.ARROW,
                PathfinderHudPresentation.decide("minecraft:overworld", "minecraft:overworld"));
    }

    @Test
    void differentDimensionShowsCrossDimensionGuidanceInstead() {
        assertEquals(PathfinderHudPresentation.Mode.WRONG_DIMENSION,
                PathfinderHudPresentation.decide("minecraft:overworld", "minecraft:the_nether"));
    }

    /**
     * MINECRAFT-409 review follow-up: the arrow must rotate relative to the actual rendered
     * camera yaw, not the player entity's own facing - the two only ever diverge under a
     * fixed-angle camera mode a different mod can install, but {@link
     * PathfinderHudOverlay} reads the real render camera unconditionally, so this is testable
     * purely through the yaw argument without needing that other mod present.
     */
    @Test
    void relativeBearingIsZeroWhenTheTargetIsStraightAheadOfTheCamera() {
        assertEquals(0.0, PathfinderHudPresentation.relativeBearing(0, 10, 0), 1e-9);
    }

    @Test
    void relativeBearingFollowsTheCameraYawNotThePlayerYaw() {
        // Same target delta in both calls - the ONLY thing that changes is which yaw is passed.
        // If this used the player's own getYRot() internally (the bug under review) these two
        // calls would be indistinguishable; here the caller controls that yaw directly, so a
        // fixed-angle camera (e.g. a shipped isometric view holding yaw at 225 regardless of the
        // player's own facing) and the player's own facing must be free to disagree.
        double dx = 0;
        double dz = 10;
        double bearingAtPlayerYaw90 = PathfinderHudPresentation.relativeBearing(dx, dz, 90.0);
        double bearingAtFixedCameraYaw225 = PathfinderHudPresentation.relativeBearing(dx, dz, 225.0);
        assertEquals(-90.0, bearingAtPlayerYaw90, 1e-9);
        assertEquals(135.0, bearingAtFixedCameraYaw225, 1e-9);
    }

    @Test
    void relativeBearingWrapsConsistentlyAcrossThePlusMinus180Boundary() {
        // Camera yaw 0 and camera yaw 360 are the same real orientation - the wrapped result for
        // a target directly behind the camera must agree exactly, not fall on opposite sides of
        // the +-180 seam depending on which equivalent yaw value happened to be reported.
        assertEquals(-180.0, PathfinderHudPresentation.relativeBearing(0, -10, 0), 1e-9);
        assertEquals(-180.0, PathfinderHudPresentation.relativeBearing(0, -10, 360.0), 1e-9);
    }
}
