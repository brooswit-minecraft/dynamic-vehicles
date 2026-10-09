package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.brooswitminecraft.dynamicvehicles.CarPhysics.Gear;
import io.github.brooswitminecraft.dynamicvehicles.CarPhysics.State;
import io.github.brooswitminecraft.dynamicvehicles.CarPhysics.Step;

class CarPhysicsTest {
    private static final double DT = 0.05;
    private static final Gear[] FORWARD_GEARS = {Gear.D1, Gear.D2, Gear.D3, Gear.D4, Gear.D5, Gear.D6};

    private static Step run(State start, double throttle, double steer, boolean handbrake, Gear gear, int ticks) {
        Step step = new Step(start, 0, 0, 0);
        for (int i = 0; i < ticks; i++) {
            step = CarPhysics.step(step.state(), throttle, steer, handbrake, gear, DT);
        }
        return step;
    }

    private static Step run(State start, double throttle, double steer, boolean handbrake, int ticks) {
        return run(start, throttle, steer, handbrake, Gear.DEFAULT_FORWARD, ticks);
    }

    @Test
    void throttleAcceleratesAndTopSpeedIsBounded() {
        Step early = run(new State(0, 0), 1, 0, false, 20);
        assertTrue(early.state().speed() > 3);
        Step late = run(new State(0, 0), 1, 0, false, 2000);
        assertTrue(late.state().speed() <= CarPhysics.MAX_SPEED + 1e-9);
        assertTrue(late.state().speed() > 15, "should reach a sensible cruising speed");
    }

    @Test
    void coastingStopsTheCarWithoutReversingIt() {
        Step step = run(new State(10, 0), 0, 0, false, 2000);
        assertEquals(0.0, step.state().speed(), 1e-9);
    }

    @Test
    void brakingAtStandstillInForwardGearNeverReverses() {
        // MINECRAFT-228 hard acceptance criterion: holding brake at zero speed brakes -- full stop, nothing
        // more -- in the default forward gear and in every one of the six named forward gears.
        for (Gear gear : FORWARD_GEARS) {
            Step braked = run(new State(0, 0), -1, 0, false, gear, 200);
            assertEquals(0.0, braked.state().speed(), 1e-9, gear + " must never reverse under brake alone");
        }
    }

    @Test
    void brakingWhileRollingForwardJustStopsInForwardGear() {
        // Braking from real forward speed must stop the car, not carry it past zero into reverse, in any
        // forward gear.
        for (Gear gear : FORWARD_GEARS) {
            Step braked = run(new State(10, 0), -1, 0, false, gear, 200);
            assertEquals(0.0, braked.state().speed(), 1e-9, gear + " must stop at zero, not reverse past it");
        }
    }

    @Test
    void brakingAtStandstillInNeutralNeverReverses() {
        Step braked = run(new State(0, 0), -1, 0, false, Gear.NEUTRAL, 200);
        assertEquals(0.0, braked.state().speed(), 1e-9, "neutral must never reverse under brake alone");
    }

    @Test
    void neutralCoastsAndIgnoresThrottleInEitherDirection() {
        Step forward = run(new State(10, 0), 1, 0, false, Gear.NEUTRAL, 20);
        assertTrue(forward.state().speed() <= 10 + 1e-9, "neutral must not let throttle accelerate the car");
        Step reverse = run(new State(0, 0), -1, 0, false, Gear.NEUTRAL, 20);
        assertEquals(0.0, reverse.state().speed(), 1e-9, "neutral must not let brake drive the car backward");
    }

    @Test
    void explicitReverseGearDrivesBackwardFromAStandstill() {
        Step reversed = run(new State(0, 0), -1, 0, false, Gear.REVERSE, 200);
        assertTrue(reversed.state().speed() < 0, "explicit REVERSE gear must let throttle<0 drive backward");
        assertTrue(reversed.state().speed() >= -CarPhysics.REVERSE_MAX - 1e-9);
    }

    @Test
    void reverseGearNeverDrivesForwardOnItsOwn() {
        // Positive throttle while in REVERSE and already stopped must not drive the car forward; only a
        // gear change (or braking an existing forward roll) does that.
        Step step = run(new State(0, 0), 1, 0, false, Gear.REVERSE, 20);
        assertEquals(0.0, step.state().speed(), 1e-9);
    }

    @Test
    void forwardGearsAllDriveForwardIdenticallyToday() {
        // MINECRAFT-228: nothing yet gives the six forward gears different ratios (see the PR), so every
        // one of them must drive exactly like today's single forward gear -- no regression for drivers who
        // never touch the new reverse input.
        for (Gear gear : FORWARD_GEARS) {
            Step step = run(new State(0, 0), 1, 0, false, gear, 20);
            assertTrue(step.state().speed() > 3, gear + " should accelerate like forward always has");
        }
    }

    @Test
    void steeringChangesHeadingOnlyWhenMoving() {
        assertEquals(0.0, run(new State(0, 0), 0, 1, false, 20).state().heading(), 1e-9);
        assertTrue(run(new State(10, 0), 0.3, 1, false, 20).state().heading() > 0.05);
        assertTrue(run(new State(10, 0), 0.3, -1, false, 20).state().heading() < -0.05);
    }

    @Test
    void gentleDrivingDoesNotSlip() {
        Step step = run(new State(10, 0), 0.3, 0.2, false, 40);
        assertEquals(0.0, step.slipSpeed(), 1e-9);
    }

    @Test
    void hardCorneringAtSpeedSlipsAndCannotTurnTighterThanGripAllows() {
        Step step = run(new State(30, 0), 0.6, 1, false, 5);
        assertTrue(step.lateralSlip() > 0, "expected lateral slip");
        double yawRate = (step.state().heading()) / (5 * DT);
        // Cornering force is capped by grip: v * yawRate <= grip (small tolerance for speed change).
        assertTrue(Math.abs(step.state().speed() * yawRate) <= CarPhysics.LATERAL_GRIP * 1.05);
    }

    @Test
    void launchWithFullThrottleSpinsTheWheelsOnlyWhenPowerExceedsGrip() {
        Step launch = CarPhysics.step(new State(0, 0), 1.0, 0, false, Gear.DEFAULT_FORWARD, DT);
        assertEquals(0.0, launch.wheelSpin(), 1e-9, "ACCEL is within longitudinal grip in this tune");
        Step fromReverse = CarPhysics.step(new State(-5, 0), 1.0, 0, false, Gear.DEFAULT_FORWARD, DT);
        assertTrue(fromReverse.wheelSpin() > 0, "slamming forward out of a reverse is brutal on the tires");
    }

    @Test
    void handbrakeLocksUpAndStops() {
        Step step = CarPhysics.step(new State(15, 0), 0, 0, true, Gear.DEFAULT_FORWARD, DT);
        assertTrue(step.lockedBraking() > 0);
        assertTrue(step.state().speed() < 15);
        assertEquals(0.0, run(new State(15, 0), 0, 0, true, 100).state().speed(), 1e-9);
    }

    @Test
    void oneSecondOfForwardInputMovesTheCarForwardAlongItsHeading() {
        // What the entity does each tick for a rider pressing W: zza = 1.
        double x = 0;
        double z = 0;
        Step step = new Step(new State(0, Math.toRadians(90)), 0, 0, 0);
        for (int tick = 0; tick < 20; tick++) {
            step = CarPhysics.step(step.state(), 1.0, 0.0, false, Gear.DEFAULT_FORWARD, DT);
            double yaw = step.state().heading();
            x += -Math.sin(yaw) * step.state().speed() * DT;
            z += Math.cos(yaw) * step.state().speed() * DT;
        }
        assertTrue(step.state().speed() > 4.0, "speed after one second: " + step.state().speed());
        assertTrue(Math.hypot(x, z) > 2.0, "distance after one second: " + Math.hypot(x, z));
        assertTrue(x < -2.0 && Math.abs(z) < 0.1, "yaw 90 degrees faces -X in Minecraft");
    }
}
