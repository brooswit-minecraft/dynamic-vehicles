package io.github.brooswitminecraft.dynamicvehicles;

import java.util.Arrays;

/**
 * Test-observation-only sum of the impulses actually handed to the native body during one production
 * tick.  Coordinates are body-local: impulse is N*s and angular impulse is r x J in N*m*s.  This class
 * deliberately receives values immediately beside existing native applications; it never participates in
 * force selection, sub-step duration, or native call scheduling.
 */
final class ProductionImpulseReceipts {
    private final double[] totalImpulse = new double[3];
    private final double[] totalAngularImpulse = new double[3];
    private final double[] lateralImpulse = new double[3];
    private final double[] lateralAngularImpulse = new double[3];
    private final double[] nonLateralImpulse = new double[3];
    private final double[] nonLateralAngularImpulse = new double[3];
    private int substeps;
    private int nativeApplications;
    private int lateralApplications;

    void reset() {
        Arrays.fill(totalImpulse, 0.0);
        Arrays.fill(totalAngularImpulse, 0.0);
        Arrays.fill(lateralImpulse, 0.0);
        Arrays.fill(lateralAngularImpulse, 0.0);
        Arrays.fill(nonLateralImpulse, 0.0);
        Arrays.fill(nonLateralAngularImpulse, 0.0);
        substeps = nativeApplications = lateralApplications = 0;
    }

    void beginSubstep() { substeps++; }

    /** Records one existing native application and its observational lateral component, if any. */
    void applied(double px, double py, double pz, double ix, double iy, double iz,
                 double lateralX, double lateralY, double lateralZ, boolean hasLateral) {
        add(totalImpulse, ix, iy, iz);
        crossAdd(totalAngularImpulse, px, py, pz, ix, iy, iz);
        nativeApplications++;
        double nx = ix, ny = iy, nz = iz;
        if (hasLateral) {
            add(lateralImpulse, lateralX, lateralY, lateralZ);
            crossAdd(lateralAngularImpulse, px, py, pz, lateralX, lateralY, lateralZ);
            nx -= lateralX;
            ny -= lateralY;
            nz -= lateralZ;
            lateralApplications++;
        }
        add(nonLateralImpulse, nx, ny, nz);
        crossAdd(nonLateralAngularImpulse, px, py, pz, nx, ny, nz);
    }

    /** Records an existing direct native angular impulse: it has no application lever or linear impulse. */
    void directAngular(double x, double y, double z) {
        add(totalAngularImpulse, x, y, z);
        add(nonLateralAngularImpulse, x, y, z);
        nativeApplications++;
    }

    Snapshot snapshot() {
        return new Snapshot(substeps, nativeApplications, lateralApplications, totalImpulse,
                totalAngularImpulse, lateralImpulse, lateralAngularImpulse, nonLateralImpulse,
                nonLateralAngularImpulse);
    }

    private static void add(double[] target, double x, double y, double z) {
        target[0] += x; target[1] += y; target[2] += z;
    }

    private static void crossAdd(double[] target, double px, double py, double pz,
                                 double ix, double iy, double iz) {
        target[0] += py * iz - pz * iy;
        target[1] += pz * ix - px * iz;
        target[2] += px * iy - py * ix;
    }

    /** Immutable-by-copy test snapshot; arrays are copied at construction and at every read. */
    static final class Snapshot {
        private final int substeps, nativeApplications, lateralApplications;
        private final double[] totalImpulse, totalAngularImpulse, lateralImpulse, lateralAngularImpulse,
                nonLateralImpulse, nonLateralAngularImpulse;

        private Snapshot(int substeps, int nativeApplications, int lateralApplications, double[] totalImpulse,
                         double[] totalAngularImpulse, double[] lateralImpulse, double[] lateralAngularImpulse,
                         double[] nonLateralImpulse, double[] nonLateralAngularImpulse) {
            this.substeps = substeps;
            this.nativeApplications = nativeApplications;
            this.lateralApplications = lateralApplications;
            this.totalImpulse = totalImpulse.clone();
            this.totalAngularImpulse = totalAngularImpulse.clone();
            this.lateralImpulse = lateralImpulse.clone();
            this.lateralAngularImpulse = lateralAngularImpulse.clone();
            this.nonLateralImpulse = nonLateralImpulse.clone();
            this.nonLateralAngularImpulse = nonLateralAngularImpulse.clone();
        }

        int substeps() { return substeps; }
        int nativeApplications() { return nativeApplications; }
        int lateralApplications() { return lateralApplications; }
        double[] totalImpulse() { return totalImpulse.clone(); }
        double[] totalAngularImpulse() { return totalAngularImpulse.clone(); }
        double[] lateralImpulse() { return lateralImpulse.clone(); }
        double[] lateralAngularImpulse() { return lateralAngularImpulse.clone(); }
        double[] nonLateralImpulse() { return nonLateralImpulse.clone(); }
        double[] nonLateralAngularImpulse() { return nonLateralAngularImpulse.clone(); }
    }
}
