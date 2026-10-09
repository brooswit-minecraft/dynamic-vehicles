package io.github.brooswitminecraft.dynamicvehicles;

import java.util.Arrays;

/**
 * Tracks which rider occupies which seat of a fixed-size vehicle (MINECRAFT-172). Generic and free of
 * Minecraft types so it can be unit tested directly; {@code CarEntity} is the only caller and plugs
 * {@code Entity} in for {@code T}. A new rider always takes the lowest-numbered free seat (never the end of
 * whatever order the entity's own passenger list happens to be in), so seat 0 &mdash; the driver &mdash;
 * stays stable across any sequence of boarding and dismounting.
 */
public final class SeatAssignment<T> {
    private final Object[] seats;

    public SeatAssignment(int seatCount) {
        this.seats = new Object[seatCount];
    }

    /** Which seats are currently occupied, lowest index first; for {@link VehicleSeating#canBoard}. */
    public boolean[] occupied() {
        boolean[] occupied = new boolean[seats.length];
        for (int i = 0; i < seats.length; i++) {
            occupied[i] = seats[i] != null;
        }
        return occupied;
    }

    /** Seats the rider into the lowest-numbered free seat and returns its index, or -1 if every seat is full. */
    public int add(T rider) {
        int index = VehicleSeating.firstFreeSeat(occupied());
        if (index < 0) {
            return -1;
        }
        seats[index] = rider;
        return index;
    }

    /** Frees whichever seat this rider occupies, if any. */
    public void remove(T rider) {
        for (int i = 0; i < seats.length; i++) {
            if (seats[i] == rider) {
                seats[i] = null;
                return;
            }
        }
    }

    /** The rider in the given seat, or null if it's empty. */
    @SuppressWarnings("unchecked")
    public T at(int index) {
        return (T) seats[index];
    }

    /** Seat 0's rider &mdash; the driver &mdash; or null if no one is driving. */
    public T driver() {
        return at(0);
    }

    /** This rider's seat index, or -1 if they don't occupy one. */
    public int indexOf(T rider) {
        return Arrays.asList(seats).indexOf(rider);
    }

    public int seatCount() {
        return seats.length;
    }
}
