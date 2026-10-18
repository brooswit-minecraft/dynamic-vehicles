package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GearDriveTest {
    private static final double DRIVE_FORCE = 2_300.0;
    private static final double REVERSE_FORCE = 1_400.0;
    private static final double BRAKE_FORCE = 3_000.0;
    private static final double MAX_SPEED = 30.0;

    @Test
    void acceleratorDrivesForwardOnlyInAForwardGear() {
        double drive = GearDrive.drive(1.0, 0.0, CarPhysics.Gear.D1, DRIVE_FORCE, REVERSE_FORCE, 1.0, MAX_SPEED);
        assertTrue(drive > 0, "accelerator in a forward gear drives forward");
    }

    @Test
    void acceleratorNeverPropelsInNeutralRegardlessOfSpeed() {
        assertEquals(0.0, GearDrive.drive(1.0, 0.0, CarPhysics.Gear.NEUTRAL, DRIVE_FORCE, REVERSE_FORCE, 1.0, MAX_SPEED), 0.0,
                "MINECRAFT-382: positive accelerator must not drive the car in neutral");
        assertEquals(0.0, GearDrive.drive(1.0, 12.0, CarPhysics.Gear.NEUTRAL, DRIVE_FORCE, REVERSE_FORCE, 1.0, MAX_SPEED), 0.0);
    }

    @Test
    void acceleratorDrivesBackwardWhenReverseIsSelected() {
        double drive = GearDrive.drive(1.0, 0.0, CarPhysics.Gear.REVERSE, DRIVE_FORCE, REVERSE_FORCE, 1.0, MAX_SPEED);
        assertTrue(drive < 0, "MINECRAFT-382: the accelerator must follow the selected REVERSE gear, not always drive forward");
    }

    @Test
    void acceleratorNeverInstantlyReversesACarStillMovingTheOtherWay() {
        // Still coasting forward at real speed: pressing the accelerator while in REVERSE must not snap
        // the drive force backward in the same tick -- it waits until the car has actually slowed down.
        assertEquals(0.0, GearDrive.drive(1.0, 5.0, CarPhysics.Gear.REVERSE, DRIVE_FORCE, REVERSE_FORCE, 1.0, MAX_SPEED), 0.0);
        // Symmetric case: still rolling backward, accelerator in a forward gear must not snap forward.
        assertEquals(0.0, GearDrive.drive(1.0, -5.0, CarPhysics.Gear.D1, DRIVE_FORCE, REVERSE_FORCE, 1.0, MAX_SPEED), 0.0);
    }

    @Test
    void brakeAlwaysOpposesActualMotionRegardlessOfGear() {
        double brakingForward = GearDrive.brake(-1.0, 10.0, BRAKE_FORCE, 1.0);
        assertTrue(brakingForward > 0, "brake decelerates forward motion");
        double brakingBackwardInReverse = GearDrive.brake(-1.0, -10.0, BRAKE_FORCE, 1.0);
        assertTrue(brakingBackwardInReverse > 0, "MINECRAFT-382: the brake must still brake while reversing, never drive further backward");
    }

    @Test
    void brakeNeverFiresFromTheAccelerator() {
        assertEquals(0.0, GearDrive.brake(1.0, 10.0, BRAKE_FORCE, 1.0), 0.0, "positive throttle is the accelerator, never the brake");
        assertEquals(0.0, GearDrive.brake(0.0, 10.0, BRAKE_FORCE, 1.0), 0.0, "zero throttle is neither pedal");
    }

    @Test
    void brakeHoldsEvenNearAStandstillInsteadOfReleasingTheCar() {
        // MINECRAFT-382: holding the brake at low speed, forward or backward, must still brake/hold the
        // car (e.g. on a slope) rather than silently doing nothing below some speed threshold.
        assertTrue(GearDrive.brake(-1.0, 0.2, BRAKE_FORCE, 1.0) > 0, "low-speed forward brake must still apply force");
        assertTrue(GearDrive.brake(-1.0, -0.2, BRAKE_FORCE, 1.0) > 0, "low-speed reverse brake must still apply force");
        assertTrue(GearDrive.brake(-1.0, 0.0, BRAKE_FORCE, 1.0) > 0, "braking from a dead stop must still hold, not release");
    }

    @Test
    void driveIsZeroAtZeroOrNegativeThrottle() {
        assertEquals(0.0, GearDrive.drive(0.0, 0.0, CarPhysics.Gear.D1, DRIVE_FORCE, REVERSE_FORCE, 1.0, MAX_SPEED), 0.0);
        assertEquals(0.0, GearDrive.drive(-1.0, 0.0, CarPhysics.Gear.D1, DRIVE_FORCE, REVERSE_FORCE, 1.0, MAX_SPEED), 0.0);
    }
}
