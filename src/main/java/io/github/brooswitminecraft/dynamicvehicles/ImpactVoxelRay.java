package io.github.brooswitminecraft.dynamicvehicles;

/** Bounded horizontal voxel traversal, including the starting cell exactly once. */
final class ImpactVoxelRay {
    private int x;
    private int z;
    private final int stepX;
    private final int stepZ;
    private double nextX;
    private double nextZ;
    private final double deltaX;
    private final double deltaZ;

    ImpactVoxelRay(double startX, double startZ, double dx, double dz) {
        x = (int) Math.floor(startX);
        z = (int) Math.floor(startZ);
        stepX = dx > 0 ? 1 : dx < 0 ? -1 : 0;
        stepZ = dz > 0 ? 1 : dz < 0 ? -1 : 0;
        deltaX = stepX == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dx);
        deltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dz);
        nextX = stepX == 0 ? Double.POSITIVE_INFINITY
                : ((stepX > 0 ? x + 1 : x) - startX) / dx;
        nextZ = stepZ == 0 ? Double.POSITIVE_INFINITY
                : ((stepZ > 0 ? z + 1 : z) - startZ) / dz;
    }

    int x() { return x; }
    int z() { return z; }

    void advance() {
        // A corner crossing enters the diagonal cell, not two merely touched neighbours.
        double crossing = Math.min(nextX, nextZ);
        if (!Double.isFinite(crossing)) return;
        if (nextX == crossing) {
            x += stepX;
            nextX += deltaX;
        }
        if (nextZ == crossing) {
            z += stepZ;
            nextZ += deltaZ;
        }
    }
}
