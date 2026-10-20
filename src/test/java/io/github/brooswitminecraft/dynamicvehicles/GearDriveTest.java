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

    // --- Clutch-transmitted wheel command (MINECRAFT-444) -------------------------------------------

    private static final double WHEEL_RADIUS = CarGeometry.WHEEL_RADIUS;
    /** All four wheels are driven -- see {@code SableCarBody.DRIVE_FORCE}'s own "all-wheel drive" note. */
    private static final int DRIVEN_WHEELS = 4;


    private static double clutchForce(double clutchTorque, double ratio, CarPhysics.Gear gear) {
        return GearDrive.clutchWheelForce(clutchTorque, ratio, WHEEL_RADIUS, DRIVEN_WHEELS, gear, 1.0);
    }

    private static double driveThroughClutch(double throttle, double forwardSpeed, CarPhysics.Gear gear,
            double forceMultiplier, double clutchWheelForce) {
        return GearDrive.driveThroughClutch(throttle, forwardSpeed, gear, forceMultiplier, DRIVE_FORCE,
                REVERSE_FORCE, 1.0, MAX_SPEED, clutchWheelForce);
    }

    @Test
    void clutchWheelForceScalesTorqueUpThroughTheRatioAndFinalDriveAndDownByRadiusAndWheelCount() {
        double torque = 100.0;
        double ratio = DrivetrainMath.ratioFor(0);
        double expected = torque * ratio * DrivetrainMath.FINAL_DRIVE / WHEEL_RADIUS / DRIVEN_WHEELS;
        assertEquals(expected, clutchForce(torque, ratio, CarPhysics.Gear.D1), 1e-9);
        assertTrue(clutchForce(torque, DrivetrainMath.ratioFor(0), CarPhysics.Gear.D1)
                > clutchForce(torque, DrivetrainMath.ratioFor(4), CarPhysics.Gear.D5),
                "the same engine torque must reach the wheel as more force in a lower (higher-ratio) gear");
    }

    @Test
    void clutchWheelForcePointsBackwardInReverseAndForwardInAForwardGear() {
        assertTrue(clutchForce(100.0, DrivetrainMath.ratioFor(0), CarPhysics.Gear.D1) > 0.0);
        assertTrue(clutchForce(100.0, DrivetrainMath.REVERSE_RATIO, CarPhysics.Gear.REVERSE) < 0.0,
                "driving torque in REVERSE must push the car backward");
    }

    @Test
    void clutchWheelForceTransmitsNothingInNeutral() {
        assertEquals(0.0, clutchForce(500.0, 0.0, CarPhysics.Gear.NEUTRAL), 0.0);
        assertEquals(0.0, clutchForce(-500.0, DrivetrainMath.ratioFor(0), CarPhysics.Gear.NEUTRAL), 0.0,
                "a disengaged clutch transmits no engine braking either");
    }

    @Test
    void engineBrakingReachesTheTireAtZeroThrottleInAForwardGear() {
        // Review (round 1)'s concrete case: coasting in gear, the clutch torque is negative (wheels turning
        // the engine against its drag). GearDrive.drive alone returns exactly 0 here; the clutch path must
        // return a real retarding force instead, or engine braking never propagates to the drivetrain.
        double engineBrakingForce = clutchForce(-DrivetrainMath.ENGINE_BRAKING_TORQUE, DrivetrainMath.ratioFor(0), CarPhysics.Gear.D1);
        assertEquals(0.0, GearDrive.drive(0.0, 10.0, CarPhysics.Gear.D1, 1.0, DRIVE_FORCE, REVERSE_FORCE, 1.0, MAX_SPEED), 0.0,
                "test premise: the throttle-only path contributes nothing while coasting");
        double command = driveThroughClutch(0.0, 10.0, CarPhysics.Gear.D1, 1.0, engineBrakingForce);
        assertTrue(command < 0.0, "coasting in a forward gear must retard the car through the drivetrain");
        assertEquals(engineBrakingForce, command, 1e-9, "the retarding force must be exactly what the clutch transmits");
    }

    @Test
    void engineBrakingRetardsBackwardMotionInReverseToo() {
        // In REVERSE the car moves backward, so engine braking must push FORWARD (positive) to retard it.
        double engineBrakingForce = clutchForce(-DrivetrainMath.ENGINE_BRAKING_TORQUE, DrivetrainMath.REVERSE_RATIO, CarPhysics.Gear.REVERSE);
        assertTrue(engineBrakingForce > 0.0);
        assertEquals(engineBrakingForce, driveThroughClutch(0.0, -5.0, CarPhysics.Gear.REVERSE, 1.0, engineBrakingForce), 1e-9);
    }

    @Test
    void aSlippingClutchBoundsTheWheelCommandBelowTheThrottlesOwnDemand() {
        // A clutch transmitting only a little torque must cap what the wheels get, even at full throttle.
        double weakClutchForce = 500.0;
        double demand = GearDrive.drive(1.0, 0.0, CarPhysics.Gear.D1, 1.0, DRIVE_FORCE, REVERSE_FORCE, 1.0, MAX_SPEED);
        assertTrue(demand > weakClutchForce, "test premise: the throttle alone would command more than the clutch can pass");
        assertEquals(weakClutchForce, driveThroughClutch(1.0, 0.0, CarPhysics.Gear.D1, 1.0, weakClutchForce), 1e-9);
    }

    @Test
    void anAmpleClutchNeverRaisesTheWheelCommandAboveTheThrottlesOwnGovernedDemand() {
        // The clutch path must not become a second propulsion source: with plenty of clutch torque
        // available, the existing governed/ratio-scaled demand is still the ceiling.
        double hugeClutchForce = 1_000_000.0;
        double demand = GearDrive.drive(1.0, 12.0, CarPhysics.Gear.D1, 4.0, DRIVE_FORCE, REVERSE_FORCE, 1.0, MAX_SPEED);
        assertEquals(demand, driveThroughClutch(1.0, 12.0, CarPhysics.Gear.D1, 4.0, hugeClutchForce), 1e-9);
    }

    @Test
    void aDrivingClutchCannotPropelTheCarWhileTheDriverHasLiftedOff() {
        // Engine spun up above the wheel side with the throttle released: the clutch IS transmitting
        // forward torque, but acceptance requires no unintended propulsion -- the demand is 0, so is this.
        assertEquals(0.0, driveThroughClutch(0.0, 10.0, CarPhysics.Gear.D1, 1.0, 5_000.0), 0.0);
        assertEquals(0.0, driveThroughClutch(-1.0, 10.0, CarPhysics.Gear.D1, 1.0, 5_000.0), 0.0,
                "braking must not be turned into propulsion by a clutch that happens to be driving");
    }

    @Test
    void neutralTransmitsNoCommandInEitherDirection() {
        assertEquals(0.0, driveThroughClutch(1.0, 10.0, CarPhysics.Gear.NEUTRAL, 1.0, 5_000.0), 0.0);
        assertEquals(0.0, driveThroughClutch(0.0, 10.0, CarPhysics.Gear.NEUTRAL, 1.0, -5_000.0), 0.0,
                "neutral must not engine-brake either -- it is disengaged");
    }

    @Test
    void theWheelForceIsTheOneExchangedClutchTorqueThroughThisGearsGearing() {
        // Review round 2: there is one exchange, and the wheel side's share of it is exactly this
        // conversion -- so whatever torque the engine lost, the drivetrain gained.
        double exchange = 300.0;
        double ratio = DrivetrainMath.ratioFor(0);
        assertEquals(exchange * ratio * DrivetrainMath.FINAL_DRIVE / WHEEL_RADIUS / DRIVEN_WHEELS,
                clutchForce(exchange, ratio, CarPhysics.Gear.D1), 1e-9);
    }

    @Test
    void aLowCapacityClutchBoundsTheExchangeAndSoTheWheelForce() {
        VehicleSpec.DrivetrainCoupling weakClutch = new VehicleSpec.DrivetrainCoupling(
                VehicleSpec.DrivetrainCoupling.STOCK.engineInertia(),
                VehicleSpec.DrivetrainCoupling.STOCK.enginePeakTorque(),
                VehicleSpec.DrivetrainCoupling.STOCK.engineBrakingTorque(),
                100.0);
        double ratio = DrivetrainMath.ratioFor(0);
        double omega = DrivetrainMath.rpmToOmega(3000.0);
        double engineTorque = DrivetrainMath.engineOutputTorque(1.0, 3000.0, omega, weakClutch);
        double reflected = DrivetrainMath.reflectedDrivetrainInertia(1200.0, WHEEL_RADIUS, DRIVEN_WHEELS,
                WheelMath.WHEEL_INERTIA, ratio);
        double exchange = DrivetrainMath.clutchExchangeTorque(omega, omega, engineTorque, reflected, 1.0 / 20.0, weakClutch);
        assertEquals(100.0, exchange, 1e-9,
                "a clutch whose capacity is below the engine's output must bound the exchange");
        assertEquals(clutchForce(100.0, ratio, CarPhysics.Gear.D1), clutchForce(exchange, ratio, CarPhysics.Gear.D1), 1e-9,
                "and the wheel force must follow that bounded exchange, not the engine's unbounded output");
    }

    @Test
    void stockFullThrottleClutchForceMatchesTheTunedDriveForceIdentityAtTopGear() {
        // The calibration ENGINE_PEAK_TORQUE's own javadoc claims: at full throttle in top gear, the
        // clutch-transmitted force must land on the pre-existing tuned DRIVE_FORCE (2300 N/wheel), so stock
        // driving feel is preserved rather than silently re-tuned by this ticket.
        double netTorque = DrivetrainMath.ENGINE_PEAK_TORQUE - DrivetrainMath.ENGINE_BRAKING_TORQUE;
        double topRatio = DrivetrainMath.ratioFor(DrivetrainMath.GEAR_RATIOS.length - 1);
        // Through the real locked exchange in top gear, where the car's own reflected inertia dominates
        // so nearly all of the engine's output reaches the road.
        double reflected = DrivetrainMath.reflectedDrivetrainInertia(1200.0, WHEEL_RADIUS, DRIVEN_WHEELS,
                WheelMath.WHEEL_INERTIA, topRatio);
        double exchange = DrivetrainMath.clutchExchangeTorque(500.0, 500.0, netTorque, reflected, 1.0 / 20.0,
                VehicleSpec.DrivetrainCoupling.STOCK);
        double force = clutchForce(exchange, topRatio, CarPhysics.Gear.D5);
        assertEquals(DRIVE_FORCE, force, DRIVE_FORCE * 0.02,
                "stock full-throttle top-gear clutch force must stay within 2% of the tuned DRIVE_FORCE identity");
    }
}
