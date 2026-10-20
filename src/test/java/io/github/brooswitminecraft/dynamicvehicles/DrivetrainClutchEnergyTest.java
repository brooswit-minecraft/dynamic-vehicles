package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class DrivetrainClutchEnergyTest {
    @Test
    void exchangeCannotCreateEnergyIncludingExternalTorqueReversalsAndPartialFreeResponse() {
        var coupling = VehicleSpec.DrivetrainCoupling.STOCK;
        for (int mobile : new int[] {1, 2, 3, 4}) {
            for (double dt : new double[] {0.05, 0.0125}) {
                for (double engine : new double[] {1, 20, 258.3333333333333, 700}) {
                    for (double wheel : new double[] {-100, 0, 20, 300, 800}) {
                        for (double output : new double[] {-55, 0, 100, 400}) {
                            double iw = 16.0 * WheelMath.WHEEL_INERTIA / (mobile * 9.84 * 9.84);
                            double lock = DrivetrainMath.clutchExchangeTorque(engine, wheel, output, 0, iw, dt, coupling);
                            double transfer = DrivetrainMath.passiveClutchTorque(lock, engine, wheel, output, 0, iw, dt, coupling);
                            double afterEngine = engine + (output - transfer) * dt / coupling.engineInertia();
                            double afterWheel = wheel + transfer * dt / iw;
                            double delta = 0.5 * coupling.engineInertia() * (afterEngine * afterEngine - engine * engine)
                                    + 0.5 * iw * (afterWheel * afterWheel - wheel * wheel);
                            double work = output * dt * (engine + afterEngine) * 0.5;
                            assertTrue(delta - work < 1e-7,
                                    () -> "energy gain " + (delta - work) + " for mobile=" + mobile + ", dt=" + dt
                                            + ", engine=" + engine + ", wheel=" + wheel + ", output=" + output);
                        }
                    }
                }
            }
        }
    }
}
