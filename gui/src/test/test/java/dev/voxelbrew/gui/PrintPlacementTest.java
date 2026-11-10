package dev.voxelbrew.gui;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.model.*;
import org.junit.jupiter.api.Test;

class PrintPlacementTest {
    @Test
    void dragInvertsBothProjectionsAndKeepsHeightAndRotation() {
        var source = new BodyInstance(
                "part", "body", Transform.translation(10, 20, 3).compose(Transform.rotation(0, 0, 30)));
        var top = PrintPlacement.move(source, 12, 8, false, false).transform().matrix();
        assertEquals(22, top[3], 1e-9);
        assertEquals(28, top[7], 1e-9);
        assertEquals(3, top[11], 1e-9);
        var iso = PrintPlacement.move(source, 4, 10, true, false).transform().matrix();
        assertEquals(22, iso[3], 1e-9);
        assertEquals(28, iso[7], 1e-9);
        assertEquals(source.transform().matrix()[0], iso[0], 1e-9);
        var snapped =
                PrintPlacement.move(source, 1.2, -2.6, false, true).transform().matrix();
        assertEquals(11, snapped[3], 1e-9);
        assertEquals(17, snapped[7], 1e-9);
    }

    @Test
    void pickingRejectsEmptySpaceAndEdgeOnTriangles() {
        assertTrue(PrintPlacement.contains(2, 2, new double[] {0, 10, 0}, new double[] {0, 0, 10}));
        assertTrue(PrintPlacement.contains(2, 2, new double[] {0, 0, 10}, new double[] {0, 10, 0}));
        assertFalse(PrintPlacement.contains(8, 8, new double[] {0, 10, 0}, new double[] {0, 0, 10}));
        assertFalse(PrintPlacement.contains(2, 0, new double[] {0, 10, 20}, new double[] {0, 0, 0}));
    }
}
