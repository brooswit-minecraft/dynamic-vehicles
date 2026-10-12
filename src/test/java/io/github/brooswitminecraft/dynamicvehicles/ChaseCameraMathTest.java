package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ChaseCameraMathTest {
    @Test
    void levelVehicleProducesLevelCameraAngles() {
        ChaseCameraMath.Angles angles = ChaseCameraMath.angles(0, 0, 0, 1);
        assertEquals(0.0f, angles.yaw(), 1.0e-4f);
        assertEquals(0.0f, angles.pitch(), 1.0e-4f);
        assertEquals(0.0f, angles.roll(), 1.0e-4f);
    }

    @Test
    void noseUpVehicleProducesMinecraftLookUpPitch() {
        ChaseCameraMath.Angles angles = ChaseCameraMath.angles((float) -Math.sin(Math.toRadians(10)), 0, 0,
                (float) Math.cos(Math.toRadians(10)));
        assertEquals(-20.0f, angles.pitch(), 1.0e-4f);
        assertEquals(0.0f, angles.roll(), 1.0e-4f);
    }

    @Test
    void vehicleRollIsPreservedForTheCamera() {
        ChaseCameraMath.Angles angles = ChaseCameraMath.angles(0, 0, (float) Math.sin(Math.toRadians(12.5)),
                (float) Math.cos(Math.toRadians(12.5)));
        assertEquals(0.0f, angles.pitch(), 1.0e-4f);
        assertEquals(25.0f, angles.roll(), 1.0e-4f);
    }
}
