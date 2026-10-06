package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.brooswitminecraft.dynamicvehicles.CarPhysics.State;
import io.github.brooswitminecraft.dynamicvehicles.CarPhysics.Step;

class CarPhysicsTest {
    private static final double DT = 0.05;

    private static Step run(State start, double throttle, double steer, boolean handbrake, int ticks) {
        Step step = new Step(start, 0, 0, 0);
        for (int i = 0; i < ticks; i++) {
            step = CarPhysics.step(step.state(), throttle, steer, handbrake, DT);
        }
        return step;
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
    void brakingStopsAndThenReverses() {
        Step braked = run(new State(10, 0), -1, 0, false, 200);
        assertTrue(braked.state().speed() < 0, "holding brake after stopping reverses");
        assertTrue(braked.state().speed() >= -CarPhysics.REVERSE_MAX - 1e-9);
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
        Step launch = CarPhysics.step(new State(0, 0), 1.0, 0, false, DT);
        assertEquals(0.0, launch.wheelSpin(), 1e-9, "ACCEL is within longitudinal grip in this tune");
        Step fromReverse = CarPhysics.step(new State(-5, 0), 1.0, 0, false, DT);
        assertTrue(fromReverse.wheelSpin() > 0, "slamming forward out of a reverse is brutal on the tires");
    }

    @Test
    void handbrakeLocksUpAndStops() {
        Step step = CarPhysics.step(new State(15, 0), 0, 0, true, DT);
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
            step = CarPhysics.step(step.state(), 1.0, 0.0, false, DT);
            double yaw = step.state().heading();
            x += -Math.sin(yaw) * step.state().speed() * DT;
            z += Math.cos(yaw) * step.state().speed() * DT;
        }
        assertTrue(step.state().speed() > 4.0, "speed after one second: " + step.state().speed());
        assertTrue(Math.hypot(x, z) > 2.0, "distance after one second: " + Math.hypot(x, z));
        assertTrue(x < -2.0 && Math.abs(z) < 0.1, "yaw 90 degrees faces -X in Minecraft");
    }
}
