package dev.voxelbrew.engine.construction;

import static dev.voxelbrew.engine.construction.ConstructionGeometry.*;

/** Analytic construction operations, shared by desktop and headless callers. */
public final class ConstructionTools {
    private static final double TOLERANCE = 1e-7;

    private ConstructionTools() {}

    public enum Tool {
        COORDINATE_SYSTEM,
        OFFSET_PLANE,
        ANGLED_PLANE,
        TANGENT_PLANE,
        MIDPLANE,
        PERPENDICULAR_PLANE,
        PLANE_TWO_EDGES,
        PLANE_THREE_POINTS,
        PLANE_ALONG_PATH,
        AXIS_SURFACE,
        AXIS_NORMAL,
        AXIS_TWO_PLANES,
        AXIS_TWO_POINTS,
        AXIS_EDGE,
        POINT_VERTEX,
        POINT_TWO_EDGES,
        POINT_THREE_PLANES,
        POINT_CENTER,
        POINT_EDGE_PLANE,
        POINT_ALONG_PATH;

        public java.util.List<InputRole> inputs() {
            return switch (this) {
                case COORDINATE_SYSTEM -> java.util.List.of(InputRole.POINT, InputRole.AXIS, InputRole.AXIS);
                case OFFSET_PLANE -> java.util.List.of(InputRole.PLANE);
                case ANGLED_PLANE -> java.util.List.of(InputRole.PLANE, InputRole.AXIS);
                case TANGENT_PLANE, AXIS_SURFACE -> java.util.List.of(InputRole.SURFACE);
                case MIDPLANE, AXIS_TWO_PLANES -> java.util.List.of(InputRole.PLANE, InputRole.PLANE);
                case PERPENDICULAR_PLANE -> java.util.List.of(InputRole.AXIS, InputRole.POINT);
                case PLANE_TWO_EDGES, POINT_TWO_EDGES -> java.util.List.of(InputRole.AXIS, InputRole.AXIS);
                case PLANE_THREE_POINTS -> java.util.List.of(InputRole.POINT, InputRole.POINT, InputRole.POINT);
                case PLANE_ALONG_PATH, POINT_ALONG_PATH -> java.util.List.of(InputRole.PATH);
                case AXIS_NORMAL -> java.util.List.of(InputRole.SURFACE, InputRole.POINT);
                case AXIS_TWO_POINTS -> java.util.List.of(InputRole.POINT, InputRole.POINT);
                case AXIS_EDGE -> java.util.List.of(InputRole.AXIS);
                case POINT_VERTEX -> java.util.List.of(InputRole.POINT);
                case POINT_THREE_PLANES -> java.util.List.of(InputRole.PLANE, InputRole.PLANE, InputRole.PLANE);
                case POINT_CENTER -> java.util.List.of(InputRole.CENTER);
                case POINT_EDGE_PLANE -> java.util.List.of(InputRole.AXIS, InputRole.PLANE);
            };
        }
    }

    public enum InputRole {
        PLANE,
        AXIS,
        POINT,
        SURFACE,
        PATH,
        CENTER
    }

    public static CoordinateSystem coordinateSystem(Vector origin, Vector xDirection, Vector yDirection) {
        var x = xDirection.unit();
        var z = x.cross(yDirection).unit();
        return new CoordinateSystem(new Plane(origin, z, x).frame());
    }

    public static Plane offsetPlane(Plane source, double distance) {
        finite(distance);
        return new Plane(source.origin().plus(source.normal().times(distance)), source.normal(), source.xDirection());
    }

    /** The rotation axis must lie in the supporting plane. */
    public static Plane angledPlane(Plane source, Axis axis, double degrees) {
        finite(degrees);
        if (Math.abs(source.normal().dot(axis.direction())) > TOLERANCE
                || Math.abs(source.origin().minus(axis.origin()).dot(source.normal())) > TOLERANCE)
            throw new IllegalArgumentException("Rotation edge must lie in the reference plane");
        double angle = Math.toRadians(degrees);
        return new Plane(
                axis.origin(),
                rotate(source.normal(), axis.direction(), angle),
                rotate(source.xDirection(), axis.direction(), angle));
    }

    public static Plane tangentPlane(Vector surfacePoint, Vector surfaceNormal) {
        return new Plane(surfacePoint, surfaceNormal);
    }

    /** For intersecting planes, alternate selects the other angular bisector. */
    public static Plane midplane(Plane first, Plane second, boolean alternate) {
        var a = first.normal();
        var b = second.normal();
        if (a.cross(b).length() < TOLERANCE) {
            double offset = second.origin().minus(first.origin()).dot(a) / 2;
            return offsetPlane(first, offset);
        }
        var intersection = axisTwoPlanes(first, second);
        return new Plane(intersection.origin(), alternate ? a.minus(b) : a.plus(b));
    }

    public static Plane perpendicularPlane(Axis edge, Vector point) {
        return new Plane(point, edge.direction());
    }

    public static Plane planeTwoEdges(Axis first, Axis second) {
        var normal = first.direction().cross(second.direction());
        if (normal.length() < TOLERANCE)
            normal = first.direction().cross(second.origin().minus(first.origin()));
        normal = normal.unit();
        if (Math.abs(second.origin().minus(first.origin()).dot(normal)) > TOLERANCE)
            throw new IllegalArgumentException("Skew edges do not define a plane");
        return new Plane(first.origin(), normal, first.direction());
    }

    public static Plane planeThreePoints(Vector first, Vector second, Vector third) {
        var x = second.minus(first);
        return new Plane(first, x.cross(third.minus(first)), x);
    }

    public static Plane planeAlongPath(Path path, double fraction) {
        fraction(fraction);
        return new Plane(path.position(fraction), path.tangent(fraction));
    }

    public static Axis axisSurface(Vector center, Vector revolutionAxis) {
        return new Axis(center, revolutionAxis);
    }

    public static Axis axisNormal(Vector surfacePoint, Vector surfaceNormal) {
        return new Axis(surfacePoint, surfaceNormal);
    }

    public static Axis axisTwoPlanes(Plane first, Plane second) {
        var direction = first.normal().cross(second.normal());
        double denominator = direction.dot(direction);
        if (denominator < 1e-14) throw new IllegalArgumentException("Parallel planes have no unique intersection axis");
        double a = first.normal().dot(first.origin());
        double b = second.normal().dot(second.origin());
        var origin = second.normal()
                .cross(direction)
                .times(a)
                .plus(direction.cross(first.normal()).times(b))
                .times(1 / denominator);
        return new Axis(origin, direction);
    }

    public static Axis axisTwoPoints(Vector first, Vector second) {
        return new Axis(first, second.minus(first));
    }

    public static Axis axisEdge(Axis edge) {
        return edge;
    }

    public static Point pointVertex(Vector vertex) {
        return new Point(vertex);
    }

    public static Point pointTwoEdges(Axis first, Axis second) {
        var n = first.direction().cross(second.direction());
        double denominator = n.dot(n);
        if (denominator < 1e-14) throw new IllegalArgumentException("Parallel edges have no unique intersection");
        var delta = second.origin().minus(first.origin());
        if (Math.abs(delta.dot(n.unit())) > TOLERANCE)
            throw new IllegalArgumentException("Skew edges do not intersect");
        return new Point(first.origin()
                .plus(first.direction().times(delta.cross(second.direction()).dot(n) / denominator)));
    }

    public static Point pointThreePlanes(Plane first, Plane second, Plane third) {
        return pointEdgePlane(axisTwoPlanes(first, second), third);
    }

    public static Point pointCenter(Vector center) {
        return new Point(center);
    }

    public static Point pointEdgePlane(Axis edge, Plane plane) {
        double denominator = edge.direction().dot(plane.normal());
        if (Math.abs(denominator) < TOLERANCE)
            throw new IllegalArgumentException("Edge and plane have no unique intersection");
        double distance = plane.origin().minus(edge.origin()).dot(plane.normal()) / denominator;
        return new Point(edge.origin().plus(edge.direction().times(distance)));
    }

    public static Point pointAlongPath(Path path, double fraction) {
        fraction(fraction);
        return new Point(path.position(fraction));
    }

    private static Vector rotate(Vector value, Vector axis, double angle) {
        return value.times(Math.cos(angle))
                .plus(axis.cross(value).times(Math.sin(angle)))
                .plus(axis.times(axis.dot(value) * (1 - Math.cos(angle))));
    }

    private static void fraction(double fraction) {
        finite(fraction);
        if (fraction < 0 || fraction > 1) throw new IllegalArgumentException("Path fraction must be between 0 and 1");
    }

    private static void finite(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Construction parameter must be finite");
    }
}
