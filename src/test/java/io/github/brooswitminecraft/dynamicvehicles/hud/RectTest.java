package io.github.brooswitminecraft.dynamicvehicles.hud;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RectTest {

    @Test
    void rejectsAnInvertedRect() {
        assertThrows(IllegalArgumentException.class, () -> new Rect(10, 0, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> new Rect(0, 10, 10, 0));
    }

    @Test
    void overlappingRectsOverlap() {
        assertTrue(new Rect(0, 0, 10, 10).overlaps(new Rect(5, 5, 15, 15)));
    }

    @Test
    void touchingEdgesDoNotCountAsOverlapping() {
        assertFalse(new Rect(0, 0, 10, 10).overlaps(new Rect(10, 0, 20, 10)));
        assertFalse(new Rect(0, 0, 10, 10).overlaps(new Rect(0, 10, 10, 20)));
    }

    @Test
    void disjointRectsDoNotOverlap() {
        assertFalse(new Rect(0, 0, 10, 10).overlaps(new Rect(20, 20, 30, 30)));
    }

    @Test
    void translateMovesAllFourSides() {
        assertEquals(new Rect(5, 15, 15, 25), new Rect(0, 10, 10, 20).translate(5, 5));
    }

    @Test
    void widthAndHeight() {
        Rect rect = new Rect(0, 0, 10, 25);
        assertEquals(10, rect.width());
        assertEquals(25, rect.height());
    }
}
