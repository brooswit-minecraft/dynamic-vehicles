package io.github.brooswitminecraft.dynamicvehicles;

/** Server-wide switch for the car debug overlay (/dtcar debug on|off). */
final class CarDebug {
    static volatile boolean enabled;

    private CarDebug() {}
}
