package io.github.brooswitminecraft.dynamicvehicles.hud;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DrivingHudLayoutTest {
    @Test void barsAndLongItemNameClearHotbarAndEachOtherAcrossGuiScalesAndModes() {
        // Scaled dimensions include odd sizes and the usual minimum GUI viewport.
        for (int[] viewport : new int[][] {{1920,1080},{1280,720},{960,540},{640,360},{480,270},{427,240},{321,241}}) {
            int width = viewport[0], height = viewport[1];
            var layout = DrivingHudLayout.at(width, height);
            var bars = new DrivingHudLayout.Rect[] {layout.health(), layout.speed(), layout.slip(), layout.rpm()};
            var hotbar = new DrivingHudLayout.Rect(width / 2 - 91, height - 22, 182, 22);
            for (int i = 0; i < bars.length; i++) {
                assertFalse(bars[i].intersects(hotbar), "hotbar overlap at " + width);
                assertTrue(bars[i].bottom() <= height - 24, "retain at least 2 pixels of hotbar clearance");
                for (int k = i + 1; k < bars.length; k++) assertFalse(bars[i].intersects(bars[k]));
            }
            for (boolean survival : new boolean[] {true, false}) {
                // Vanilla Gui.renderSelectedItemName's formula, including creative's +14.
                int nameY = height - Math.max(DrivingHudLayout.itemNameOffset(survival), 59) + (survival ? 0 : 14);
                var longName = new DrivingHudLayout.Rect(0, nameY, width, 9);
                for (var bar : bars) assertFalse(longName.intersects(bar), "item name overlap in survival=" + survival);
            }
            var air = new DrivingHudLayout.Rect(width / 2 + 10, height - DrivingHudLayout.AIR_OFFSET, 81, 9);
            assertFalse(air.intersects(layout.speed()));
        }
    }
}
