package dev.voxelbrew.gui;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.model.*;
import org.junit.jupiter.api.Test;

class PrintPlacementTest {
    @Test
    void automaticOrientationUsesBroadBedFaceAndPreservesCenter() {
        var mesh = dev.voxelbrew.engine.printing.MeshGeometry.box(new dev.voxelbrew.engine.printing.SupportVolume(
                "support", "part", dev.voxelbrew.engine.printing.SupportVolume.Role.SOLID_SUPPORT, 0, 0, 0, 60, 40, 5));
        var current = Transform.translation(80, 90, 20).compose(Transform.rotation(90, 0, 30));
        var before = dev.voxelbrew.engine.printing.MeshGeometry.bounds(
                dev.voxelbrew.engine.printing.MeshGeometry.transform(mesh, current));
        var oriented = PrintPlacement.autoOrient(mesh, current);
        var after = dev.voxelbrew.engine.printing.MeshGeometry.bounds(
                dev.voxelbrew.engine.printing.MeshGeometry.transform(mesh, oriented));
        assertEquals(0, after.minZ(), 1e-6);
        assertEquals(5, after.maxZ(), 1e-6);
        assertEquals(before.minX() + before.maxX(), after.minX() + after.maxX(), 1e-6);
        assertEquals(before.minY() + before.maxY(), after.minY() + after.maxY(), 1e-6);
    }

    @Test
    void plateReorderPreservesSelectionContentsAndPreferences() {
        var setup = dev.voxelbrew.engine.printing.PrintSetup.empty()
                .addPlate("second")
                .addPlate("third")
                .withWorkspaceSettings(java.util.Map.of("print-overwrite", "true"));
        var reordered = PrintPlacement.reorder(setup, "plate-1", "third");
        assertEquals(
                java.util.List.of("second", "third", "plate-1"),
                reordered.plates().stream()
                        .map(dev.voxelbrew.engine.printing.PrintPlate::id)
                        .toList());
        assertEquals(setup.activePlateId(), reordered.activePlateId());
        assertEquals(setup.workspaceSettings(), reordered.workspaceSettings());
        assertEquals(setup.plates().getFirst(), reordered.plates().getLast());
        assertEquals(setup, PrintPlacement.reorder(reordered, "plate-1", "second"));
    }

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
