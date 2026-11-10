package dev.voxelbrew.gui;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.engine.printing.*;
import javafx.geometry.Point2D;
import org.junit.jupiter.api.Test;

class PrintRotationGizmoTest {
    @Test
    void localRotationUsesObjectAxesWhileWorldUsesPlateAxes() {
        var mesh = MeshGeometry.box(
                new SupportVolume("box", "part", SupportVolume.Role.SOLID_SUPPORT, 0, 0, 0, 60, 40, 5));
        var source = new BodyInstance(
                "part", "body", Transform.translation(50, 50, 0).compose(Transform.rotation(0, 0, 90)));
        var local =
                PrintRotationGizmo.rotate(source, mesh, PrintRotationGizmo.Axis.X, 30, PrintRotationGizmo.Space.LOCAL);
        var world = PrintRotationGizmo.rotate(source, mesh, PrintRotationGizmo.Axis.Y, 30);
        assertArrayEquals(world.transform().matrix(), local.transform().matrix(), 1e-8);
        assertNotEquals(PrintRotationGizmo.rotate(source, mesh, PrintRotationGizmo.Axis.X, 30), local);
        var frame = PrintRotationGizmo.frame(source.transform(), PrintRotationGizmo.Space.LOCAL);
        var ring = PrintRotationGizmo.ring(
                PrintRotationGizmo.Axis.X, javafx.geometry.Point3D.ZERO, 1, new PrintCamera(false), 0, 0, 1, frame);
        assertEquals(-1, ring.u().getX(), 1e-8);
        assertEquals(0, ring.u().getY(), 1e-8);
        assertEquals(PrintRotationGizmo.Axis.X, PrintRotationGizmo.topAxis(Transform.rotation(0, 90, 0)));
        assertEquals(PrintRotationGizmo.Axis.Z, PrintRotationGizmo.topAxis(Transform.IDENTITY));
    }

    @Test
    void rotatesEveryAxisAroundCenterAndPreservesClearance() {
        var mesh = MeshGeometry.box(
                new SupportVolume("box", "part", SupportVolume.Role.SOLID_SUPPORT, 0, 0, 0, 60, 40, 5));
        var source = new BodyInstance("part", "body", Transform.translation(10, 20, 3));
        double[][] sizes = {{60, 5, 40}, {5, 40, 60}, {40, 60, 5}};
        for (var axis : PrintRotationGizmo.Axis.values()) {
            var rotated = PrintRotationGizmo.rotate(source, mesh, axis, 90);
            var b = MeshGeometry.bounds(MeshGeometry.transform(mesh, rotated.transform()));
            var size = sizes[axis.ordinal()];
            assertEquals(size[0], b.maxX() - b.minX(), 1e-8);
            assertEquals(size[1], b.maxY() - b.minY(), 1e-8);
            assertEquals(size[2], b.maxZ() - b.minZ(), 1e-8);
            assertEquals(40, (b.minX() + b.maxX()) / 2, 1e-8);
            assertEquals(40, (b.minY() + b.maxY()) / 2, 1e-8);
            assertEquals(3, b.minZ(), 1e-8);
            assertEquals(source.id(), rotated.id());
            assertSame(source, PrintRotationGizmo.rotate(source, mesh, axis, 0));
        }
    }

    @Test
    void picksRingButLeavesCenterForMovingAndUnwrapsSnappedAngles() {
        var ring = new PrintRotationGizmo.Ring(
                PrintRotationGizmo.Axis.Z, Point2D.ZERO, new Point2D(100, 0), new Point2D(0, 100));
        assertNull(ring.hit(Point2D.ZERO));
        var start = ring.point(Math.toRadians(170));
        var hit = ring.hit(start);
        assertNotNull(hit);
        var drag = new PrintRotationGizmo.Drag(hit, start);
        assertEquals(20, drag.degrees(ring.point(Math.toRadians(-170)), false), 1e-8);
        assertEquals(30, drag.degrees(ring.point(Math.toRadians(-162)), true), 1e-8);
    }

    @Test
    void edgeOnRingRemainsDraggable() {
        var ring =
                new PrintRotationGizmo.Ring(PrintRotationGizmo.Axis.X, Point2D.ZERO, new Point2D(0, 100), Point2D.ZERO);
        var start = ring.point(ring.handleAngle());
        var drag = new PrintRotationGizmo.Drag(ring.hit(start), start);
        assertEquals(15, drag.degrees(start.add(0, -20), true), 1e-8);
    }
}
