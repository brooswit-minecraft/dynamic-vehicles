package io.github.brooswitminecraft.dynamicvehicles;

/**
 * Pure seat math for multi-seat vehicles (MINECRAFT-172), free of Minecraft types so it can be unit tested.
 * Covers which seat a boarding rider takes, whether boarding is allowed right now, and where a seat's own
 * dismount point sits relative to the body &mdash; the three things {@code CarEntity} needs per seat beyond
 * the attachment point {@link VehicleSpec.Seat} already carries.
 */
public final class VehicleSeating {

    /**
     * Horizontal speed, blocks/tick, above which a MULTI-seat vehicle (seat count &gt; 1) refuses to let a
     * new rider board. A one-seat vehicle never consults this constant: {@link #canBoard} always allows
     * boarding its one seat regardless of speed, exactly as every vehicle behaved before this ticket.
     * Exiting is never gated by speed, on any vehicle, so a rider is never trapped.
     */
    public static final double BOARDING_SPEED_LIMIT = 0.1;

    /** Ground clearance, in blocks, a multi-seat dismount steps out past the body's side. */
    public static final double DISMOUNT_CLEARANCE = 0.5;

    private VehicleSeating() {}

    /** Index of the lowest-numbered unoccupied seat, or -1 if every seat is in use. */
    public static int firstFreeSeat(boolean[] occupied) {
        for (int i = 0; i < occupied.length; i++) {
            if (!occupied[i]) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Index of the lowest-numbered unoccupied seat, EXCLUDING seat 0 (MINECRAFT-211): for an auto-boarding
     * mob, which must never take the driver's seat even when it is empty. Returns -1 if every non-driver
     * seat is in use, or if {@code occupied} has no non-driver seats at all (a one-seat vehicle).
     */
    public static int firstFreeNonDriverSeat(boolean[] occupied) {
        for (int i = 1; i < occupied.length; i++) {
            if (!occupied[i]) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Whether a new rider may board right now. {@code occupied.length} is the vehicle's seat count: at most
     * one entry for a one-seat vehicle, which is never speed-gated (today's behavior, unchanged). A
     * multi-seat vehicle additionally refuses while {@code horizontalSpeedBlocksPerTick} exceeds
     * {@link #BOARDING_SPEED_LIMIT}, so no one hops onto a moving bus.
     */
    public static boolean canBoard(boolean[] occupied, double horizontalSpeedBlocksPerTick) {
        if (firstFreeSeat(occupied) < 0) {
            return false;
        }
        return occupied.length <= 1 || horizontalSpeedBlocksPerTick <= BOARDING_SPEED_LIMIT;
    }

    /**
     * Local (entity-frame) ground point a rider leaving {@code seat} lands at: pushed past the body's side
     * so a multi-seat exit never drops a rider inside the chassis or on top of a seatmate. A seat centred on
     * or right of the car (x &gt;= 0, the driver's included) steps out to the right; a seat left of centre
     * steps out to the left.
     */
    public static double[] dismountOffset(VehicleSpec.Seat seat, double halfX) {
        double x = seat.x() >= 0 ? halfX + DISMOUNT_CLEARANCE : -(halfX + DISMOUNT_CLEARANCE);
        return new double[] {x, 0.0, seat.z()};
    }
}
