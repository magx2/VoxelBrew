package dev.voxelbrew.gui;

import static org.junit.jupiter.api.Assertions.*;

import javafx.geometry.Rectangle2D;
import org.junit.jupiter.api.Test;

class WindowFrameTest {
    @Test
    void resizingKeepsTheOppositeEdgesAnchoredOnNegativeCoordinateMonitors() {
        var start = new Rectangle2D(-1200, -100, 1000, 700);
        for (int edge : new int[] {1, 2, 4, 8, 5, 6, 9, 10}) {
            var result = WindowFrame.resize(start, edge, 30, 20, 800, 540, 2000, 1200);
            assertEquals((edge & WindowFrame.LEFT) != 0 ? -1170 : -1200, result.getMinX());
            assertEquals((edge & WindowFrame.RIGHT) != 0 ? -170 : -200, result.getMaxX());
            assertEquals((edge & WindowFrame.TOP) != 0 ? -80 : -100, result.getMinY());
            assertEquals((edge & WindowFrame.BOTTOM) != 0 ? 620 : 600, result.getMaxY());
        }
    }

    @Test
    void minimumAndMaximumSizesDoNotMoveTheAnchoredCorner() {
        var start = new Rectangle2D(100, 200, 1000, 700);
        var small =
                WindowFrame.resize(
                        start,
                        WindowFrame.LEFT | WindowFrame.TOP,
                        5000,
                        5000,
                        800,
                        540,
                        2000,
                        1200);
        assertEquals(new Rectangle2D(300, 360, 800, 540), small);
        var large =
                WindowFrame.resize(
                        start,
                        WindowFrame.LEFT | WindowFrame.TOP,
                        -5000,
                        -5000,
                        800,
                        540,
                        2000,
                        1200);
        assertEquals(new Rectangle2D(-900, -300, 2000, 1200), large);
    }
}
