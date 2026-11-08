package dev.voxelbrew.engine.construction;

import static dev.voxelbrew.engine.construction.ConstructionGeometry.*;
import static dev.voxelbrew.engine.construction.ConstructionTools.*;

import dev.voxelbrew.engine.geometry.Solid;
import dev.voxelbrew.engine.model.ConstructionFeature;
import dev.voxelbrew.engine.model.Feature;
import java.util.Map;
import java.util.function.Function;

/** Resolves earlier feature outputs before computing a construction result. */
public final class ConstructionEvaluator {
    private ConstructionEvaluator() {}

    private record Operand(Datum datum, double[] topology) {
        Vector vector(int offset) {
            return new Vector(topology[offset], topology[offset + 1], topology[offset + 2]);
        }

        int type() {
            return topology == null ? 0 : (int) topology[0];
        }

        Plane plane() {
            if (datum instanceof Plane p) return p;
            if (type() == 1) return new Plane(vector(1), vector(13), vector(7));
            throw new IllegalArgumentException("Select a plane or a planar face");
        }

        Axis axis() {
            if (datum instanceof Axis a) return a;
            if (type() == 6) return new Axis(vector(10), vector(13));
            throw new IllegalArgumentException("Select an axis or a straight edge");
        }

        Vector point() {
            if (datum instanceof Point p) return p.position();
            if (type() == 9) return vector(1);
            throw new IllegalArgumentException("Select a construction point or vertex");
        }

        Vector normal() {
            if (datum instanceof Plane p) return p.normal();
            if (type() >= 1 && type() <= 5) return vector(13);
            throw new IllegalArgumentException("Select a surface");
        }

        Vector normalAt(Vector point) {
            if (datum instanceof Plane || type() == 1) {
                var plane = plane();
                if (Math.abs(point.minus(plane.origin()).dot(plane.normal())) > 1e-7)
                    throw new IllegalArgumentException("Point must lie on the selected surface");
                return plane.normal();
            }
            if (type() < 2 || type() > 5) throw new IllegalArgumentException("Select an analytic surface");
            var delta = point.minus(vector(1));
            var axis = vector(4);
            var radial = delta.minus(axis.times(delta.dot(axis)));
            Vector normal;
            double error;
            switch (type()) {
                case 2 -> {
                    normal = radial.unit();
                    error = radial.length() - topology[16];
                }
                case 3 -> {
                    normal = radial.unit().times(Math.cos(topology[17])).minus(axis.times(Math.sin(topology[17])));
                    error = radial.length() - topology[16] - delta.dot(axis) * Math.tan(topology[17]);
                }
                case 4 -> {
                    normal = delta.unit();
                    error = delta.length() - topology[16];
                }
                case 5 -> {
                    var tube = delta.minus(radial.unit().times(topology[16]));
                    normal = tube.unit();
                    error = tube.length() - topology[17];
                }
                default -> throw new IllegalArgumentException("Unsupported surface");
            }
            if (Math.abs(error) > 1e-7) throw new IllegalArgumentException("Point must lie on the selected surface");
            return normal;
        }

        Vector sample() {
            if (topology != null) return vector(10);
            throw new IllegalArgumentException("Select a body edge or face");
        }
    }

    public static Datum evaluate(
            ConstructionFeature feature, Map<String, Datum> datums, Function<Feature.BodyRef, Solid> solids) {
        double fraction = switch (feature.tool()) {
            case POINT_ALONG_PATH, PLANE_ALONG_PATH, TANGENT_PLANE -> feature.value();
            default -> .5;
        };
        var operands = feature.inputs().stream()
                .map(input -> resolve(input, datums, solids, fraction))
                .toList();
        var a = operands.getFirst();
        var b = operands.size() > 1 ? operands.get(1) : null;
        var c = operands.size() > 2 ? operands.get(2) : null;
        return switch (feature.tool()) {
            case COORDINATE_SYSTEM ->
                coordinateSystem(a.point(), b.axis().direction(), c.axis().direction());
            case OFFSET_PLANE -> offsetPlane(a.plane(), feature.value());
            case ANGLED_PLANE -> angledPlane(a.plane(), b.axis(), feature.value());
            case TANGENT_PLANE -> tangentPlane(a.sample(), a.normal());
            case MIDPLANE -> midplane(a.plane(), b.plane(), feature.alternate());
            case PERPENDICULAR_PLANE -> perpendicularPlane(a.axis(), b.point());
            case PLANE_TWO_EDGES -> planeTwoEdges(a.axis(), b.axis());
            case PLANE_THREE_POINTS -> planeThreePoints(a.point(), b.point(), c.point());
            case PLANE_ALONG_PATH -> {
                requirePath(a);
                yield new Plane(a.sample(), a.vector(13));
            }
            case AXIS_SURFACE -> {
                if (a.type() != 2 && a.type() != 3 && a.type() != 5)
                    throw new IllegalArgumentException("Select a cylinder, cone or torus face");
                yield axisSurface(a.vector(1), a.vector(4));
            }
            case AXIS_NORMAL -> {
                var point = b.point();
                yield axisNormal(point, a.normalAt(point));
            }
            case AXIS_TWO_PLANES -> axisTwoPlanes(a.plane(), b.plane());
            case AXIS_TWO_POINTS -> axisTwoPoints(a.point(), b.point());
            case AXIS_EDGE -> axisEdge(a.axis());
            case POINT_VERTEX -> pointVertex(a.point());
            case POINT_TWO_EDGES -> pointTwoEdges(a.axis(), b.axis());
            case POINT_THREE_PLANES -> pointThreePlanes(a.plane(), b.plane(), c.plane());
            case POINT_CENTER -> {
                if (a.type() != 4 && a.type() != 5 && a.type() != 7)
                    throw new IllegalArgumentException("Select a circle edge, sphere or torus face");
                yield pointCenter(a.vector(1));
            }
            case POINT_EDGE_PLANE -> pointEdgePlane(a.axis(), b.plane());
            case POINT_ALONG_PATH -> {
                requirePath(a);
                yield new Point(a.sample());
            }
        };
    }

    private static void requirePath(Operand operand) {
        if (operand.type() < 6 || operand.type() > 8) throw new IllegalArgumentException("Select a path edge");
    }

    private static Operand resolve(
            ConstructionFeature.Input input,
            Map<String, Datum> datums,
            Function<Feature.BodyRef, Solid> solids,
            double fraction) {
        var zero = new Vector(0, 0, 0);
        return switch (input.selection()) {
            case POSITION -> new Operand(new Point(input.position()), null);
            case ORIGIN_POINT -> new Operand(new Point(zero), null);
            case ORIGIN_AXIS -> new Operand(new Axis(zero, basis(input.index())), null);
            case ORIGIN_PLANE ->
                new Operand(new Plane(zero, basis(input.index()).times(input.index() == 1 ? -1 : 1)), null);
            case DATUM -> {
                var datum = datums.get(input.source());
                if (datum == null) throw new IllegalArgumentException("Missing construction output: " + input.source());
                if (datum instanceof CoordinateSystem system) {
                    double[] o = system.frame().apply(0, 0, 0);
                    var origin = new Vector(o[0], o[1], o[2]);
                    if (input.index() == 0) datum = new Point(origin);
                    else {
                        int direction = (input.index() - 1) % 3;
                        double[] end = system.frame()
                                .apply(direction == 0 ? 1 : 0, direction == 1 ? 1 : 0, direction == 2 ? 1 : 0);
                        var axis = new Vector(end[0], end[1], end[2]).minus(origin);
                        if (input.index() <= 3) datum = new Axis(origin, axis);
                        else {
                            double[] xPoint =
                                    system.frame().apply(input.index() == 4 ? 0 : 1, input.index() == 4 ? 1 : 0, 0);
                            datum = new Plane(
                                    origin,
                                    axis.times(input.index() == 5 ? -1 : 1),
                                    new Vector(xPoint[0], xPoint[1], xPoint[2]).minus(origin));
                        }
                    }
                }
                yield new Operand(datum, null);
            }
            case FACE, EDGE, VERTEX -> {
                var solid = solids.apply(new Feature.BodyRef(input.source(), input.output()));
                if (solid == null) throw new IllegalArgumentException("Missing body output: " + input.source());
                int topology = switch (input.selection()) {
                    case FACE -> 0;
                    case EDGE -> 1;
                    default -> 2;
                };
                yield new Operand(null, solid.constructionInput(topology, input.index(), fraction));
            }
        };
    }

    private static Vector basis(int index) {
        return switch (index) {
            case 0 -> new Vector(1, 0, 0);
            case 1 -> new Vector(0, 1, 0);
            case 2 -> new Vector(0, 0, 1);
            default -> throw new IllegalArgumentException("Unknown origin direction");
        };
    }
}
