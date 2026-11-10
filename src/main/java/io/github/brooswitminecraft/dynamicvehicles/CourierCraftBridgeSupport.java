package io.github.brooswitminecraft.dynamicvehicles;

/**
 * MINECRAFT-493 review (comment 52567, finding 1): this mod's declared version number alone
 * cannot tell Courier Craft whether it is talking to a genuine post-split build. The real
 * released 0.82.1 is pre-split and still registers the same {@code dynamicvehicles} Dispatcher/
 * Dispatch Board/menu/payload registry IDs Courier Craft now also registers -- a real,
 * incompatible pairing a version range alone would silently admit, since the actual first
 * post-split release's version number isn't known until the external release-gate assigns it at
 * merge time. Courier Craft's optional bridge checks for this marker's mere presence via
 * reflection (see {@code DynamicVehiclesBridgeSchema} there) instead of trusting the declared
 * version: it exists at all only once the Dispatcher extraction has actually happened, regardless
 * of what version number this build happens to carry.
 */
public final class CourierCraftBridgeSupport {
    /**
     * Bump only for a breaking change to the bridge contract itself (the reservation/clear
     * methods Courier Craft calls via reflection on {@code hud.MissionWaypointLayout}), never for
     * an ordinary release -- this is a schema version, not this mod's own version.
     */
    public static final int SCHEMA_VERSION = 1;

    private CourierCraftBridgeSupport() {}
}
