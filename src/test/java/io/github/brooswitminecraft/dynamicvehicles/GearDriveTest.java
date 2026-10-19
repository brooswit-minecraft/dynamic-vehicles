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
        double drive = GearDrive.drive(1.0, 0.0, CarPhysics.Gear.D1, 1.0, DRIVE_FORCE, REVERSE_FORCE, 1.0, MAX_SPEED);
        assertTrue(drive > 0, "accelerator in a forward gear drives forward");
    }

    @Test
    void acceleratorNeverPropelsInNeutralRegardlessOfSpeed() {
        assertEquals(0.0, GearDrive.drive(1.0, 0.0, CarPhysics.Gear.NEUTRAL, 1.0, DRIVE_FORCE, REVERSE_FORCE, 1.0, MAX_SPEED), 0.0,
                "MINECRAFT-382: positive accelerator must not drive the car in neutral");
        assertEquals(0.0, GearDrive.drive(1.0, 12.0, CarPhysics.Gear.NEUTRAL, 1.0, DRIVE_FORCE, REVERSE_FORCE, 1.0, MAX_SPEED), 0.0);
    }

    @Test
    void acceleratorDrivesBackwardWhenReverseIsSelected() {
        double drive = GearDrive.drive(1.0, 0.0, CarPhysics.Gear.REVERSE, 1.0, DRIVE_FORCE, REVERSE_FORCE, 1.0, MAX_SPEED);
        assertTrue(drive < 0, "MINECRAFT-382: the accelerator must follow the selected REVERSE gear, not always drive forward");
    }

    @Test
    void acceleratorAppliesTractionLimitedForceEvenWhileRollingTheOtherWay() {
        // MINECRAFT-401: the old opposing-direction gate is removed. Forward torque while rolling
        // backward, and reverse torque while rolling forward, both apply in the selected gear's own
        // direction -- exactly what decelerates and eventually reverses the unwanted roll -- rather than
        // doing nothing until the car has coasted to a near-standstill first.
        double forwardWhileRollingBackward = GearDrive.drive(1.0, -5.0, CarPhysics.Gear.D1, 1.0, DRIVE_FORCE, REVERSE_FORCE, 1.0, MAX_SPEED);
        assertTrue(forwardWhileRollingBackward > 0, "a forward gear must still push forward while the car rolls backward");
        double reverseWhileRollingForward = GearDrive.drive(1.0, 5.0, CarPhysics.Gear.REVERSE, 1.0, DRIVE_FORCE, REVERSE_FORCE, 1.0, MAX_SPEED);
        assertTrue(reverseWhileRollingForward < 0, "REVERSE must still push backward while the car rolls forward");
        // Even far into the opposing direction, nowhere near this gear's own governed top speed, the
        // force must not have decayed to zero -- the governor below is keyed to speed in the gear's OWN
        // direction only, never to the magnitude of an opposing speed.
        double farIntoOpposingRoll = GearDrive.drive(1.0, -(MAX_SPEED * 2), CarPhysics.Gear.D1, 1.0, DRIVE_FORCE, REVERSE_FORCE, 1.0, MAX_SPEED);
        assertEquals(DRIVE_FORCE, farIntoOpposingRoll, 1e-9);
    }

    @Test
    void theGovernorOnlySoftensForceInTheGearsOwnDirection() {
        // Approaching this gear's own governed top speed in its own direction still tapers the force off,
        // same as before MINECRAFT-401 -- only the OPPOSING-direction gate was removed, not the governor.
        double nearOwnTopSpeed = GearDrive.drive(1.0, MAX_SPEED * 0.9, CarPhysics.Gear.D1, 1.0, DRIVE_FORCE, REVERSE_FORCE, 1.0, MAX_SPEED);
        assertTrue(nearOwnTopSpeed > 0 && nearOwnTopSpeed < DRIVE_FORCE, "drive force should taper near the gear's own top speed");
        double atOwnTopSpeed = GearDrive.drive(1.0, MAX_SPEED, CarPhysics.Gear.D1, 1.0, DRIVE_FORCE, REVERSE_FORCE, 1.0, MAX_SPEED);
        assertEquals(0.0, atOwnTopSpeed, 1e-9);
    }

    @Test
    void forceMultiplierScalesTheDriveForceForTheEngagedRatio() {
        double low = GearDrive.drive(1.0, 0.0, CarPhysics.Gear.D1, 2.0, DRIVE_FORCE, REVERSE_FORCE, 1.0, MAX_SPEED);
        double high = GearDrive.drive(1.0, 0.0, CarPhysics.Gear.D1, 0.5, DRIVE_FORCE, REVERSE_FORCE, 1.0, MAX_SPEED);
        assertEquals(DRIVE_FORCE * 2.0, low, 1e-9);
        assertEquals(DRIVE_FORCE * 0.5, high, 1e-9);
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
        assertEquals(0.0, GearDrive.drive(0.0, 0.0, CarPhysics.Gear.D1, 1.0, DRIVE_FORCE, REVERSE_FORCE, 1.0, MAX_SPEED), 0.0);
        assertEquals(0.0, GearDrive.drive(-1.0, 0.0, CarPhysics.Gear.D1, 1.0, DRIVE_FORCE, REVERSE_FORCE, 1.0, MAX_SPEED), 0.0);
    }
}
