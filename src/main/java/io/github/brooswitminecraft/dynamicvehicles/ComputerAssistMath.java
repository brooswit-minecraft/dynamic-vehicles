package io.github.brooswitminecraft.dynamicvehicles;

/** Bounded, per-wheel drive/brake intervention for the TC/ABS slice. */
final class ComputerAssistMath {
    private static final double SLIP_START_MPS = 0.75;

    private ComputerAssistMath() {}

    static double tractionFactor(double level, double wheelSpeed, double contactSpeed, double drive) {
        if (level <= 0.0 || drive == 0.0 || !sameDirection(drive, wheelSpeed - contactSpeed)) {
            return 1.0;
        }
        return 1.0 - Math.min(1.0, Math.abs(wheelSpeed - contactSpeed) / SLIP_START_MPS) * level;
    }

    static double absFactor(double level, double wheelSpeed, double contactSpeed, double brake) {
        if (level <= 0.0 || brake <= 0.0 || Math.abs(contactSpeed) < 0.25
                || !sameDirection(contactSpeed, contactSpeed - wheelSpeed)) {
            return 1.0;
        }
        return 1.0 - Math.min(1.0, Math.abs(contactSpeed - wheelSpeed) / SLIP_START_MPS) * level;
    }

    private static boolean sameDirection(double a, double b) {
        return Math.copySign(1.0, a) == Math.copySign(1.0, b);
    }
}
