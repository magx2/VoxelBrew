package dev.voxelbrew.engine.construction;

import dev.voxelbrew.engine.model.Transform;

/** Detached construction geometry in design-space millimeters. */
public final class ConstructionGeometry {
    private ConstructionGeometry() {}

    public record Vector(double x, double y, double z) {
        public Vector {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
                throw new IllegalArgumentException("Construction coordinates must be finite");
        }

        public Vector plus(Vector other) {
            return new Vector(x + other.x, y + other.y, z + other.z);
        }

        public Vector minus(Vector other) {
            return new Vector(x - other.x, y - other.y, z - other.z);
        }

        public Vector times(double factor) {
            return new Vector(x * factor, y * factor, z * factor);
        }

        public double dot(Vector other) {
            return x * other.x + y * other.y + z * other.z;
        }

        public Vector cross(Vector other) {
            return new Vector(y * other.z - z * other.y, z * other.x - x * other.z, x * other.y - y * other.x);
        }

        public double length() {
            return Math.hypot(Math.hypot(x, y), z);
        }

        public Vector unit() {
            double length = length();
            if (length < 1e-9) throw new IllegalArgumentException("Construction direction is undefined");
            return times(1 / length);
        }

        public double distance(Vector other) {
            return minus(other).length();
        }
    }

    public sealed interface Datum permits Point, Axis, Plane, CoordinateSystem {}

    public record Point(Vector position) implements Datum {
        public Point {
            java.util.Objects.requireNonNull(position);
        }
    }

    public record Axis(Vector origin, Vector direction) implements Datum {
        public Axis {
            java.util.Objects.requireNonNull(origin);
            direction = direction.unit();
        }
    }

    public record Plane(Vector origin, Vector normal, Vector xDirection) implements Datum {
        public Plane {
            java.util.Objects.requireNonNull(origin);
            normal = normal.unit();
            xDirection = xDirection.minus(normal.times(xDirection.dot(normal))).unit();
        }

        public Plane(Vector origin, Vector normal) {
            this(origin, normal, perpendicular(normal));
        }

        public Transform frame() {
            var y = normal.cross(xDirection);
            return new Transform(
                    xDirection.x,
                    y.x,
                    normal.x,
                    origin.x,
                    xDirection.y,
                    y.y,
                    normal.y,
                    origin.y,
                    xDirection.z,
                    y.z,
                    normal.z,
                    origin.z);
        }
    }

    public record CoordinateSystem(Transform frame) implements Datum {
        public CoordinateSystem {
            java.util.Objects.requireNonNull(frame);
        }
    }

    /** Exact curve sampling must use normalized arc length, not the native curve parameter. */
    public interface Path {
        Vector position(double fraction);

        Vector tangent(double fraction);
    }

    public static Vector perpendicular(Vector direction) {
        var n = direction.unit();
        var seed = Math.abs(n.x) < .8 ? new Vector(1, 0, 0) : new Vector(0, 1, 0);
        return seed.minus(n.times(seed.dot(n))).unit();
    }
}
