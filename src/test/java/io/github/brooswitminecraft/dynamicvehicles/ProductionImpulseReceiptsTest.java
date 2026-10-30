package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ProductionImpulseReceiptsTest {
    @Test void resetRejectsPriorTickAndAccumulatesEverySubstep() {
        var receipts = new ProductionImpulseReceipts();
        receipts.reset();
        receipts.beginSubstep();
        receipts.applied(0, 2, 0, 3, 0, 0, 0, 0, 0, false);
        receipts.beginSubstep();
        receipts.applied(0, 1, 0, 0, 0, 4, 0, 0, 4, true);
        var snapshot = receipts.snapshot();
        assertEquals(2, snapshot.substeps());
        assertEquals(2, snapshot.nativeApplications());
        assertEquals(1, snapshot.lateralApplications());
        assertArrayEquals(new double[] {3, 0, 4}, snapshot.totalImpulse(), 1e-12);
        assertArrayEquals(new double[] {0, 0, 4}, snapshot.lateralImpulse(), 1e-12);
        assertArrayEquals(new double[] {4, 0, 0}, snapshot.lateralAngularImpulse(), 1e-12);
        assertArrayEquals(new double[] {0, 0, -6}, snapshot.nonLateralAngularImpulse(), 1e-12);
        receipts.reset();
        var reset = receipts.snapshot();
        assertEquals(0, reset.substeps());
        assertEquals(0, reset.nativeApplications());
        assertArrayEquals(new double[] {0, 0, 0}, reset.totalImpulse(), 1e-12);
    }

    @Test void snapshotsCannotAliasLaterApplicationsOrCallerArrays() {
        var receipts = new ProductionImpulseReceipts();
        receipts.reset();
        receipts.beginSubstep();
        receipts.applied(0, 1, 0, 0, 0, 2, 0, 0, 2, true);
        var first = receipts.snapshot();
        double[] escaped = first.lateralImpulse();
        escaped[2] = 999;
        receipts.applied(0, 1, 0, 0, 0, 3, 0, 0, 3, true);
        assertArrayEquals(new double[] {0, 0, 2}, first.lateralImpulse(), 1e-12);
        assertArrayEquals(new double[] {0, 0, 5}, receipts.snapshot().lateralImpulse(), 1e-12);
    }

    @Test void directAngularImpulseIsNonLateralWithoutInventingALeverOrLinearImpulse() {
        var receipts = new ProductionImpulseReceipts();
        receipts.reset();
        receipts.directAngular(1, -2, 3);
        var snapshot = receipts.snapshot();
        assertEquals(1, snapshot.nativeApplications());
        assertEquals(0, snapshot.lateralApplications());
        assertArrayEquals(new double[] {0, 0, 0}, snapshot.totalImpulse(), 1e-12);
        assertArrayEquals(new double[] {1, -2, 3}, snapshot.totalAngularImpulse(), 1e-12);
        assertArrayEquals(new double[] {1, -2, 3}, snapshot.nonLateralAngularImpulse(), 1e-12);
    }

    @Test void combinedCallSeparatesLateralAndNonLateralAtTheSameActualPoint() {
        var receipts = new ProductionImpulseReceipts();
        receipts.reset();
        receipts.beginSubstep();
        receipts.applied(0, 2, 0, 3, 0, 4, 0, 0, 4, true);
        var snapshot = receipts.snapshot();
        assertArrayEquals(new double[] {3, 0, 4}, snapshot.totalImpulse(), 1e-12);
        assertArrayEquals(new double[] {0, 0, 4}, snapshot.lateralImpulse(), 1e-12);
        assertArrayEquals(new double[] {3, 0, 0}, snapshot.nonLateralImpulse(), 1e-12);
        assertArrayEquals(new double[] {8, 0, -6}, snapshot.totalAngularImpulse(), 1e-12);
        assertArrayEquals(new double[] {8, 0, 0}, snapshot.lateralAngularImpulse(), 1e-12);
        assertArrayEquals(new double[] {0, 0, -6}, snapshot.nonLateralAngularImpulse(), 1e-12);
    }
}
