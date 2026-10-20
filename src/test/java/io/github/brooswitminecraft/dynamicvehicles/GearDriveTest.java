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
    private static final double TICK_DT = 1.0 / 20.0;

    private static double clutchForce(double clutchTorque, double ratio, CarPhysics.Gear gear) {
        return GearDrive.clutchWheelForce(clutchTorque, ratio, WHEEL_RADIUS, DRIVEN_WHEELS, gear, 1.0);
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
    void clutchWheelForceIsTheExactInverseOfTheTorqueTheEngineIsChargedFor() {
        // Review round 3: the engine is charged for what the wheels took by taking it back through the
        // gearing, so the two transforms must be exact inverses -- every gear, both directions, any scale.
        for (CarPhysics.Gear gear : new CarPhysics.Gear[] {CarPhysics.Gear.D1, CarPhysics.Gear.D3, CarPhysics.Gear.D5,
                CarPhysics.Gear.REVERSE}) {
            double ratio = gear == CarPhysics.Gear.REVERSE ? DrivetrainMath.REVERSE_RATIO : DrivetrainMath.ratioFor(gear.manualIndex());
            double direction = gear == CarPhysics.Gear.REVERSE ? -1.0 : 1.0;
            for (double torque : new double[] {-300.0, -1.0, 0.0, 42.0, 875.0}) {
                for (double forceScale : new double[] {1.0, 2.1}) {
                    double perWheel = GearDrive.clutchWheelForce(torque, ratio, WHEEL_RADIUS, DRIVEN_WHEELS, gear, forceScale);
                    double back = DrivetrainMath.wheelForceToClutchTorque(perWheel * DRIVEN_WHEELS, WHEEL_RADIUS, ratio,
                            direction, forceScale);
                    assertEquals(torque, back, 1e-9, gear + " torque " + torque + " forceScale " + forceScale);
                }
            }
        }
    }

    @Test
    void engineBrakingReachesTheTireAtZeroThrottleInAForwardGear() {
        // Review (round 1)'s concrete case: coasting in gear the clutch's offer is negative (wheels turning
        // the engine against its drag). GearDrive.drive alone returns exactly 0 here; the clutch path must
        // pass the real retarding force instead, or engine braking never propagates to the drivetrain.
        double engineBrakingForce = clutchForce(-DrivetrainMath.ENGINE_BRAKING_TORQUE, DrivetrainMath.ratioFor(0), CarPhysics.Gear.D1);
        assertEquals(0.0, GearDrive.drive(0.0, 10.0, CarPhysics.Gear.D1, 1.0, DRIVE_FORCE, REVERSE_FORCE, 1.0, MAX_SPEED), 0.0,
                "test premise: the throttle-only path contributes nothing while coasting");
        double command = GearDrive.driveThroughClutch(0.0, 10.0, CarPhysics.Gear.D1, engineBrakingForce);
        assertTrue(command < 0.0, "coasting in a forward gear must retard the car through the drivetrain");
        assertEquals(engineBrakingForce, command, 1e-9, "the retarding force must be exactly what the clutch offered");
    }

    @Test
    void engineBrakingRetardsBackwardMotionInReverseToo() {
        // In REVERSE the car moves backward, so engine braking must push FORWARD (positive) to retard it.
        double engineBrakingForce = clutchForce(-DrivetrainMath.ENGINE_BRAKING_TORQUE, DrivetrainMath.REVERSE_RATIO, CarPhysics.Gear.REVERSE);
        assertTrue(engineBrakingForce > 0.0);
        assertEquals(engineBrakingForce, GearDrive.driveThroughClutch(0.0, -5.0, CarPhysics.Gear.REVERSE, engineBrakingForce), 1e-9);
    }

    @Test
    void anOpenThrottlePassesTheOfferUnchangedNeverCappedOrReSignedHere() {
        // Review round 3: every bound on the wheel half now lives in DrivetrainMath.realizableClutchTorque,
        // ahead of the engine's integration -- this method may only pass the offer on or withhold it.
        double offer = 1234.5;
        assertEquals(offer, GearDrive.driveThroughClutch(1.0, 0.0, CarPhysics.Gear.D1, offer), 0.0);
        assertEquals(offer, GearDrive.driveThroughClutch(0.3, 12.0, CarPhysics.Gear.D1, offer), 0.0);
        assertEquals(-offer, GearDrive.driveThroughClutch(1.0, 5.0, CarPhysics.Gear.REVERSE, -offer), 0.0,
                "a rearward offer in REVERSE is the driver's own request, even while still rolling forward");
    }

    @Test
    void anOpenThrottleNeverTurnsAnOfferAgainstTheSelectedGearIntoDrive() {
        // Round 2's sign flip, with the review's own numbers: a negative exchange under full throttle
        // (-649.611249 N per wheel in D1) was handed to the wheels as +649.611249. An offer pointing against
        // the gear is withheld here -- never re-signed.
        assertEquals(0.0, GearDrive.driveThroughClutch(1.0, 0.0, CarPhysics.Gear.D1, -649.611249), 0.0);
        assertEquals(0.0, GearDrive.driveThroughClutch(1.0, 0.0, CarPhysics.Gear.REVERSE, 649.611249), 0.0);
    }

    @Test
    void aDrivingClutchCannotPropelTheCarWhileTheDriverHasLiftedOff() {
        // Engine spun up above the wheel side with the throttle released: the clutch IS offering forward
        // torque, but acceptance requires no unintended propulsion.
        assertEquals(0.0, GearDrive.driveThroughClutch(0.0, 10.0, CarPhysics.Gear.D1, 5_000.0), 0.0);
        assertEquals(0.0, GearDrive.driveThroughClutch(-1.0, 10.0, CarPhysics.Gear.D1, 5_000.0), 0.0,
                "braking must not be turned into propulsion by a clutch that happens to be driving");
    }

    @Test
    void aClosedThrottleOnlyEverRetardsNeverDrives() {
        // Both directions, and both signs of offer: with the throttle shut the drivetrain may slow the car
        // or do nothing, never push it.
        assertTrue(GearDrive.driveThroughClutch(0.0, 5.0, CarPhysics.Gear.D1, -600.0) < 0.0,
                "rolling forward with a retarding offer must decelerate");
        assertEquals(0.0, GearDrive.driveThroughClutch(0.0, 5.0, CarPhysics.Gear.D1, 600.0), 0.0,
                "rolling forward with a driving offer and no throttle must contribute nothing");
        assertTrue(GearDrive.driveThroughClutch(0.0, -5.0, CarPhysics.Gear.REVERSE, 600.0) > 0.0,
                "rolling backward with a retarding offer must decelerate");
        assertEquals(0.0, GearDrive.driveThroughClutch(0.0, -5.0, CarPhysics.Gear.REVERSE, -600.0), 0.0,
                "rolling backward with a driving offer and no throttle must contribute nothing");
        assertEquals(0.0, GearDrive.driveThroughClutch(0.0, 0.1, CarPhysics.Gear.D1, -600.0), 0.0,
                "at a standstill the parked-hold brake owns the car, not engine drag");
    }

    @Test
    void neutralTransmitsNoCommandInEitherDirection() {
        assertEquals(0.0, GearDrive.driveThroughClutch(1.0, 10.0, CarPhysics.Gear.NEUTRAL, 5_000.0), 0.0);
        assertEquals(0.0, GearDrive.driveThroughClutch(0.0, 10.0, CarPhysics.Gear.NEUTRAL, -5_000.0), 0.0,
                "neutral must not engine-brake either -- it is disengaged");
    }

    private static double fullThrottleDemandTorque(double ratio) {
        double demandForce = GearDrive.drive(1.0, 0.0, CarPhysics.Gear.D1, DrivetrainMath.forceMultiplier(ratio),
                DRIVE_FORCE, REVERSE_FORCE, 1.0, MAX_SPEED);
        return DrivetrainMath.wheelForceToClutchTorque(demandForce * DRIVEN_WHEELS, WHEEL_RADIUS, ratio, 1.0, 1.0);
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
        double demand = fullThrottleDemandTorque(ratio);
        double engineTorque = DrivetrainMath.engagedEngineTorque(1.0, 3000.0, omega, demand, omega, TICK_DT, weakClutch);
        // Gripping, so the car's mass is on the shaft -- the case where the engine's full output would
        // otherwise come through and the capacity is what has to bound it.
        double reflected = DrivetrainMath.clutchLoadInertia(1200.0, WHEEL_RADIUS, DRIVEN_WHEELS,
                WheelMath.WHEEL_INERTIA, ratio, true, 1.0);
        double lock = DrivetrainMath.clutchExchangeTorque(omega, omega, engineTorque, 0.0, reflected, TICK_DT, weakClutch);
        assertEquals(100.0, lock, 1e-9, "a clutch whose capacity is below the engine's output must bound the lock torque");
        double offered = DrivetrainMath.realizableClutchTorque(lock, omega, omega, engineTorque, demand, true, TICK_DT, weakClutch);
        assertEquals(100.0, offered, 1e-9, "and nothing downstream of the solve may exceed that bound");
        assertEquals(clutchForce(100.0, ratio, CarPhysics.Gear.D1), clutchForce(offered, ratio, CarPhysics.Gear.D1), 1e-9,
                "so the wheel force follows the bounded exchange, not the engine's unbounded output");
    }

    @Test
    void stockFullThrottleSteadyStateLandsOnTheTunedDriveForceInEveryGear() {
        // Calibration: the engine is asked for exactly the governed demand (plus its own drag), and a locked
        // clutch then passes it -- so full throttle with the tire already delivering puts the same force on
        // the road, gear for gear, that the pre-existing tuned DRIVE_FORCE * forceMultiplier always did.
        for (int index = 0; index < DrivetrainMath.GEAR_RATIOS.length; index++) {
            double ratio = DrivetrainMath.ratioFor(index);
            double demandForce = GearDrive.drive(1.0, 0.0, CarPhysics.Gear.D1, DrivetrainMath.forceMultiplier(ratio),
                    DRIVE_FORCE, REVERSE_FORCE, 1.0, MAX_SPEED);
            double demand = fullThrottleDemandTorque(ratio);
            double omega = DrivetrainMath.rpmToOmega(3000.0);
            double engineTorque = DrivetrainMath.engagedEngineTorque(1.0, 3000.0, omega, demand, omega, TICK_DT,
                    VehicleSpec.DrivetrainCoupling.STOCK);
            assertEquals(demand, engineTorque, 1e-9, "ratio " + ratio + ": the engine must be asked for exactly the demand");
            double reflected = DrivetrainMath.clutchLoadInertia(1200.0, WHEEL_RADIUS, DRIVEN_WHEELS,
                    WheelMath.WHEEL_INERTIA, ratio, true, 1.0);
            // Steady state: a tire already delivering the demand reacts with exactly -demand on the shaft.
            double tireReaction = DrivetrainMath.wheelSideTorqueAtEngine(demandForce * DRIVEN_WHEELS, WHEEL_RADIUS,
                    ratio, 1.0, 1.0);
            double lock = DrivetrainMath.clutchExchangeTorque(omega, omega, engineTorque, tireReaction, reflected,
                    TICK_DT, VehicleSpec.DrivetrainCoupling.STOCK);
            double offered = DrivetrainMath.realizableClutchTorque(lock, omega, omega, engineTorque, demand, true,
                    TICK_DT, VehicleSpec.DrivetrainCoupling.STOCK);
            assertEquals(demandForce, clutchForce(offered, ratio, CarPhysics.Gear.D1), demandForce * 1e-9,
                    "ratio " + ratio + ": a locked full-throttle clutch must put exactly the tuned demand on each wheel");
        }
    }
}
