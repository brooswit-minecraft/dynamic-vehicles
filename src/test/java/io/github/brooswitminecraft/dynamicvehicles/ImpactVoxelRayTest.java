package io.github.brooswitminecraft.dynamicvehicles;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ImpactVoxelRayTest {
    @Test
    void diagonalDoesNotRepeatTheFirstCell() {
        ImpactVoxelRay ray = new ImpactVoxelRay(.1, .1, 1, 1);
        assertCell(ray, 0, 0);
        ray.advance();
        assertCell(ray, 1, 1);
        ray.advance();
        assertCell(ray, 2, 2);
    }

    @Test
    void obliqueRayVisitsEveryCrossedCellInOrder() {
        ImpactVoxelRay ray = new ImpactVoxelRay(.1, .1, 2, 1);
        int[][] cells = {{0, 0}, {1, 0}, {1, 1}, {2, 1}, {3, 1}, {3, 2}};
        for (int[] cell : cells) {
            assertCell(ray, cell[0], cell[1]);
            ray.advance();
        }
    }

    @Test
    void reverseAndAxisAlignedPathsCrossNegativeCoordinates() {
        ImpactVoxelRay ray = new ImpactVoxelRay(.1, .1, -1, -1);
        ray.advance();
        assertCell(ray, -1, -1);
        ray.advance();
        assertCell(ray, -2, -2);
        ImpactVoxelRay axis = new ImpactVoxelRay(0, 0, 0, -1);
        axis.advance();
        assertCell(axis, 0, -1);
        axis.advance();
        assertCell(axis, 0, -2);
    }

    private static void assertCell(ImpactVoxelRay ray, int x, int z) {
        assertEquals(x, ray.x());
        assertEquals(z, ray.z());
    }
}
