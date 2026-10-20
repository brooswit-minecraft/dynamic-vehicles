package io.github.brooswitminecraft.dynamicvehicles.town;

/** One of a town region's four edges, each carrying exactly one road connection point. */
public enum TownEdge {
    NORTH, SOUTH, EAST, WEST;

    public TownEdge opposite() {
        return switch (this) {
            case NORTH -> SOUTH;
            case SOUTH -> NORTH;
            case EAST -> WEST;
            case WEST -> EAST;
        };
    }
}
