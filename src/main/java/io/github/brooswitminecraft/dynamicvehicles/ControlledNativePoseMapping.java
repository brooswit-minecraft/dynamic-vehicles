package io.github.brooswitminecraft.dynamicvehicles;

/** The controlled native fixture's deterministic horizontal binary32 representation boundary. */
final class ControlledNativePoseMapping {
    private ControlledNativePoseMapping() {}

    /** Vertical support geometry is intentionally not mapped here. */
    static double toBinary32(double intendedCoordinate) {
        return (double) (float) intendedCoordinate;
    }
}
