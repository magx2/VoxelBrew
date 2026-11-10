package dev.voxelbrew.gui;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.engine.printing.*;
import javafx.geometry.Point2D;
import org.junit.jupiter.api.Test;

class PrintRotationGizmoTest {
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
