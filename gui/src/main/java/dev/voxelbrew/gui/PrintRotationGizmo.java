package dev.voxelbrew.gui;

import dev.voxelbrew.engine.mesh.MeshData;
import dev.voxelbrew.engine.model.BodyInstance;
import dev.voxelbrew.engine.model.Transform;
import dev.voxelbrew.engine.printing.MeshGeometry;
import javafx.geometry.Point2D;
import javafx.geometry.Point3D;

/** World-axis rotation rings and transient placement math, independent of scene event dispatch. */
final class PrintRotationGizmo {
    enum Axis {
        X,
        Y,
        Z
    }

    record Hit(Ring ring, double angle, double distance) {}

    record Ring(Axis axis, Point2D center, Point2D u, Point2D v) {
        Point2D point(double angle) {
            return center.add(u.multiply(Math.cos(angle))).add(v.multiply(Math.sin(angle)));
        }

        double handleAngle() {
            return Math.PI / 4 + axis.ordinal() * Math.PI / 2;
        }

        Double angle(Point2D point) {
            double determinant = u.getX() * v.getY() - u.getY() * v.getX();
            double radius = Math.max(u.magnitude(), v.magnitude());
            if (Math.abs(determinant) < radius * radius * .03) return null;
            var delta = point.subtract(center);
            double cosine = (delta.getX() * v.getY() - delta.getY() * v.getX()) / determinant;
            double sine = (u.getX() * delta.getY() - u.getY() * delta.getX()) / determinant;
            return Math.atan2(sine, cosine);
        }

        Hit hit(Point2D point) {
            if (point.distance(point(handleAngle())) <= 10) return new Hit(this, handleAngle(), 0);
            // Leave the object's center available for translation, including edge-on rings.
            if (point.distance(center) < Math.max(18, Math.max(u.magnitude(), v.magnitude()) * .3)) return null;
            Hit best = null;
            for (int i = 0; i < 72; i++) {
                double angle = i * Math.PI / 36;
                var a = point(angle);
                var b = point(angle + Math.PI / 36);
                var segment = b.subtract(a);
                double lengthSquared = segment.dotProduct(segment);
                if (lengthSquared < 1e-9) continue;
                double fraction = Math.clamp(point.subtract(a).dotProduct(segment) / lengthSquared, 0, 1);
                double distance = point.distance(a.add(segment.multiply(fraction)));
                if (distance <= 7 && (best == null || distance < best.distance()))
                    best = new Hit(this, angle + fraction * Math.PI / 36, distance);
            }
            return best;
        }
    }

    static final class Drag {
        private final Ring ring;
        private final Point2D start;
        private final double startAngle;
        private double lastAngle, accumulated;
        private final boolean edgeOn;

        Drag(Hit hit, Point2D start) {
            ring = hit.ring();
            this.start = start;
            Double angle = ring.angle(start);
            edgeOn = angle == null;
            startAngle = edgeOn ? hit.angle() : angle;
            lastAngle = startAngle;
        }

        Axis axis() {
            return ring.axis();
        }

        double degrees(Point2D point, boolean snap) {
            if (edgeOn) {
                var tangent =
                        ring.u().multiply(-Math.sin(startAngle)).add(ring.v().multiply(Math.cos(startAngle)));
                // At an edge-on extremity use vertical screen motion rather than dividing by zero.
                if (tangent.magnitude() < 8)
                    tangent = new Point2D(0, -Math.max(40, ring.u().magnitude()));
                accumulated = point.subtract(start).dotProduct(tangent) / tangent.dotProduct(tangent);
            } else {
                double angle = ring.angle(point);
                accumulated += Math.atan2(Math.sin(angle - lastAngle), Math.cos(angle - lastAngle));
                lastAngle = angle;
            }
            double degrees = Math.toDegrees(accumulated);
            return snap ? Math.rint(degrees / 15) * 15 : degrees;
        }
    }

    private PrintRotationGizmo() {}

    static Ring ring(Axis axis, Point3D center, double radius, PrintCamera camera, double ox, double oy, double scale) {
        Point3D u = switch (axis) {
            case X -> new Point3D(0, 1, 0);
            case Y -> new Point3D(0, 0, 1);
            case Z -> new Point3D(1, 0, 0);
        };
        Point3D v = switch (axis) {
            case X -> new Point3D(0, 0, 1);
            case Y -> new Point3D(1, 0, 0);
            case Z -> new Point3D(0, 1, 0);
        };
        return new Ring(
                axis,
                new Point2D(
                        ox + camera.x(center.getX(), center.getY()) * scale,
                        oy - camera.y(center.getX(), center.getY(), center.getZ()) * scale),
                project(u, camera).multiply(radius * scale),
                project(v, camera).multiply(radius * scale));
    }

    private static Point2D project(Point3D point, PrintCamera camera) {
        return new Point2D(camera.x(point.getX(), point.getY()), -camera.y(point.getX(), point.getY(), point.getZ()));
    }

    static BodyInstance rotate(BodyInstance source, MeshData mesh, Axis axis, double degrees) {
        if (Math.abs(degrees) < 1e-9) return source;
        var before = MeshGeometry.bounds(MeshGeometry.transform(mesh, source.transform()));
        double x = (before.minX() + before.maxX()) / 2,
                y = (before.minY() + before.maxY()) / 2,
                z = (before.minZ() + before.maxZ()) / 2;
        var rotation = Transform.rotation(
                axis == Axis.X ? degrees : 0, axis == Axis.Y ? degrees : 0, axis == Axis.Z ? degrees : 0);
        var placed = Transform.translation(x, y, z)
                .compose(rotation)
                .compose(Transform.translation(-x, -y, -z))
                .compose(source.transform());
        var after = MeshGeometry.bounds(MeshGeometry.transform(mesh, placed));
        return new BodyInstance(
                source.id(),
                source.bodyId(),
                Transform.translation(0, 0, before.minZ() - after.minZ()).compose(placed));
    }
}
