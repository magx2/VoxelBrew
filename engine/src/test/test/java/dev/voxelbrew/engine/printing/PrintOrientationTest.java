package dev.voxelbrew.engine.printing;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.mesh.MeshData;
import dev.voxelbrew.engine.model.*;
import org.junit.jupiter.api.Test;

/** Analytic mesh fixtures: no kernel, display, native libraries or external processes. */
class PrintOrientationTest {
    private MeshData box() {
        return MeshGeometry.box(new SupportVolume("box", "part", SupportVolume.Role.SOLID_SUPPORT, 0, 0, 0, 60, 40, 8));
    }

    private BodyInstance source() {
        return new BodyInstance(
                "instance", "body", Transform.translation(20, 30, 7).compose(Transform.rotation(0, 0, 35)));
    }

    @Test
    void zeroRotationRetainsTheOriginalInstance() {
        var source = source();
        assertSame(source, PrintOrientation.rotate(source, box(), 0, 0, false));
    }

    @Test
    void rotationPreservesIdentityCenterAndBedClearanceWithoutMutatingInputs() {
        var source = source();
        var mesh = box();
        var matrix = source.transform().matrix();
        var vertices = mesh.vertices();
        var before = MeshGeometry.bounds(MeshGeometry.transform(mesh, source.transform()));
        var result = PrintOrientation.rotate(source, mesh, 0, 90, false);
        var after = MeshGeometry.bounds(MeshGeometry.transform(mesh, result.transform()));
        assertEquals(source.id(), result.id());
        assertEquals(source.bodyId(), result.bodyId());
        assertEquals(before.minZ(), after.minZ(), 1e-8);
        assertEquals((before.minX() + before.maxX()) / 2, (after.minX() + after.maxX()) / 2, 1e-8);
        assertEquals((before.minY() + before.maxY()) / 2, (after.minY() + after.maxY()) / 2, 1e-8);
        assertArrayEquals(matrix, source.transform().matrix());
        assertArrayEquals(vertices, mesh.vertices());
    }

    @Test
    void localAndWorldAxesProduceDifferentOrientationsForAnAlreadyRotatedObject() {
        var source = source();
        var mesh = box();
        var world = PrintOrientation.rotate(source, mesh, 0, 90, false);
        var local = PrintOrientation.rotate(source, mesh, 0, 90, true);
        var worldBounds = MeshGeometry.bounds(MeshGeometry.transform(mesh, world.transform()));
        var localBounds = MeshGeometry.bounds(MeshGeometry.transform(mesh, local.transform()));
        assertTrue(Math.abs(worldBounds.maxZ() - localBounds.maxZ()) > 1);
        assertEquals(7, localBounds.minZ(), 1e-8);
        assertEquals(7, worldBounds.minZ(), 1e-8);
    }

    @Test
    void invalidAxesAndNonfiniteAnglesAreRejectedBeforeGeometryWork() {
        var source = source();
        var mesh = box();
        for (int axis : new int[] {-1, 3})
            assertThrows(IllegalArgumentException.class, () -> PrintOrientation.rotate(source, mesh, axis, 10, false));
        for (double angle : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
            assertThrows(IllegalArgumentException.class, () -> PrintOrientation.rotate(source, mesh, 0, angle, true));
    }

    @Test
    void automaticOrientationChoosesABroadBedFaceAndPreservesHorizontalCenter() {
        var mesh = box();
        var source = new BodyInstance(
                "instance", "body", Transform.translation(20, 30, 7).compose(Transform.rotation(90, 0, 0)));
        var before = MeshGeometry.bounds(MeshGeometry.transform(mesh, source.transform()));
        var result = PrintOrientation.autoOrient(mesh, source.transform());
        var after = MeshGeometry.bounds(MeshGeometry.transform(mesh, result));
        assertEquals(0, after.minZ(), 1e-8);
        assertEquals(8, after.maxZ(), 1e-8);
        assertEquals((before.minX() + before.maxX()) / 2, (after.minX() + after.maxX()) / 2, 1e-8);
        assertEquals((before.minY() + before.maxY()) / 2, (after.minY() + after.maxY()) / 2, 1e-8);
    }

    @Test
    void automaticOrientationRejectsMeshesWithoutASupportingPlanarFace() {
        var mesh = new MeshData(new double[] {0, 0, 0, 1, 0, 0, 2, 0, 0}, new int[] {0, 1, 2}, new int[] {0});
        assertThrows(IllegalArgumentException.class, () -> PrintOrientation.autoOrient(mesh, Transform.IDENTITY));
    }
}
