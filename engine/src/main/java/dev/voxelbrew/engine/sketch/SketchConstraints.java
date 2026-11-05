package dev.voxelbrew.engine.sketch;

import static dev.voxelbrew.engine.sketch.SketchGeometry.*;

import java.util.ArrayList;
import java.util.List;

/** Geometric equations shared by the solver, persistence validation, CLI, and desktop editor. */
public final class SketchConstraints {
    private SketchConstraints() {}

    public static Constraint create(
            SketchGeometry g, String id, Type type, List<Ref> refs, double value) {
        List<Double> values = List.of();
        if (type.dimension()) values = List.of(value);
        else if (type == Type.FIX && refs.size() == 1) {
            var ref = refs.getFirst();
            if (ref.part() == Part.OBJECT) values = g.entity(ref.entityId()).values();
            else {
                var p = g.point(ref);
                values = List.of(p.x(), p.y());
            }
        } else if (type == Type.TANGENT
                && refs.size() == 2
                && refs.stream().allMatch(r -> r.part() == Part.OBJECT)) {
            var a = g.entity(refs.get(0).entityId());
            var b = g.entity(refs.get(1).entityId());
            if (a.kind() == Kind.CIRCLE && b.kind() == Kind.CIRCLE) {
                double d = a.point(Part.CENTER).distance(b.point(Part.CENTER));
                values =
                        List.of(
                                Math.abs(d - a.value(2) - b.value(2))
                                                <= Math.abs(d - Math.abs(a.value(2) - b.value(2)))
                                        ? 1.0
                                        : -1.0);
            } else if (a.kind() == Kind.LINE || b.kind() == Kind.LINE) {
                var line = a.kind() == Kind.LINE ? a : b;
                var circle = a.kind() == Kind.LINE ? b : a;
                if (circle.kind() == Kind.CIRCLE)
                    values =
                            List.of(
                                    Math.copySign(
                                            1,
                                            direction(line)
                                                    .cross(
                                                            circle.point(Part.CENTER)
                                                                    .minus(line.at(0)))));
            }
        } else if (type == Type.POLYGON && refs.size() >= 3) {
            var a = direction(g.entity(refs.get(0).entityId()));
            var b = direction(g.entity(refs.get(1).entityId()));
            values = List.of(Math.copySign(Math.TAU / refs.size(), a.cross(b)));
        }
        var constraint = new Constraint(id, type, refs, values);
        residuals(g, constraint); // Validate kinds and reference arity before exposing an edit.
        return constraint;
    }

    public static String selectionHint(Type type) {
        return switch (type) {
            case HORIZONTAL, VERTICAL -> "Select a line, or two points.";
            case COINCIDENT -> "Select two points, or a point and a line/circle/arc/spline.";
            case TANGENT -> "Select a line and circle, two circles, or two curve endpoints.";
            case EQUAL -> "Select two lines, or two circles/arcs.";
            case PARALLEL, PERPENDICULAR, COLLINEAR -> "Select two lines.";
            case FIX -> "Select one entity or point. Select its Fix constraint to unfix.";
            case MIDPOINT -> "Select a point, then a line or arc.";
            case CONCENTRIC -> "Select two circles or arcs.";
            case SYMMETRY -> "Select two points/entities, then the symmetry-axis line.";
            case CURVATURE -> "Select two curve endpoints; at least one must be a cubic spline.";
            case POLYGON -> "Select at least three connected lines in boundary order.";
            case LENGTH -> "Select one line.";
            case DISTANCE, DISTANCE_X, DISTANCE_Y ->
                    "Select two points (the origin is selectable).";
            case RADIUS, DIAMETER -> "Select a circle or arc.";
            case ANGLE -> "Select one line (from X), or two lines. Enter degrees.";
        };
    }

    public static double[] residuals(SketchGeometry g, Constraint c) {
        try {
            return equations(g, c);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(
                    "Constraint " + c.id() + " (" + c.type() + "): " + failure.getMessage(),
                    failure);
        }
    }

    private static double[] equations(SketchGeometry g, Constraint c) {
        var refs = c.refs();
        var values = c.values();
        int n = refs.size();
        if (c.type().dimension() && values.size() != 1)
            throw new IllegalArgumentException("One driving value is required");
        switch (c.type()) {
            case HORIZONTAL, VERTICAL -> {
                Vec delta;
                if (n == 1) delta = direction(line(g, refs.getFirst()));
                else {
                    count(c, 2);
                    delta = g.point(refs.get(1)).minus(g.point(refs.get(0)));
                }
                return new double[] {c.type() == Type.HORIZONTAL ? delta.y() : delta.x()};
            }
            case COINCIDENT -> {
                count(c, 2);
                var a = refs.get(0);
                var b = refs.get(1);
                if (a.part() == Part.OBJECT) {
                    var temp = a;
                    a = b;
                    b = temp;
                }
                var point = g.point(a);
                if (b.part() != Part.OBJECT) return xy(point.minus(g.point(b)));
                var curve = object(g, b);
                return switch (curve.kind()) {
                    case LINE ->
                            new double[] {direction(curve).unit().cross(point.minus(curve.at(0)))};
                    case CIRCLE ->
                            new double[] {
                                point.distance(curve.point(Part.CENTER)) - curve.value(2)
                            };
                    case ARC, SPLINE -> xy(point.minus(curve.at(nearest(curve, point))));
                    case POINT -> xy(point.minus(curve.at(0)));
                };
            }
            case PARALLEL, PERPENDICULAR, COLLINEAR -> {
                count(c, 2);
                var a = line(g, refs.get(0));
                var b = line(g, refs.get(1));
                var u = direction(a).unit();
                var v = direction(b).unit();
                if (c.type() == Type.PERPENDICULAR) return new double[] {10 * u.dot(v)};
                if (c.type() == Type.PARALLEL) return new double[] {10 * u.cross(v)};
                return new double[] {10 * u.cross(v), u.cross(b.at(0).minus(a.at(0)))};
            }
            case EQUAL -> {
                count(c, 2);
                var a = object(g, refs.get(0));
                var b = object(g, refs.get(1));
                if (a.kind() == Kind.LINE && b.kind() == Kind.LINE)
                    return new double[] {direction(a).length() - direction(b).length()};
                circular(a);
                circular(b);
                return new double[] {a.value(2) - b.value(2)};
            }
            case FIX -> {
                count(c, 1);
                var ref = refs.getFirst();
                List<Double> pose;
                if (ref.part() == Part.OBJECT) pose = object(g, ref).values();
                else {
                    var p = g.point(ref);
                    pose = List.of(p.x(), p.y());
                }
                if (pose.size() != values.size())
                    throw new IllegalArgumentException(
                            "Fixed pose is missing or has the wrong size");
                double[] residual = new double[pose.size()];
                for (int i = 0; i < residual.length; i++) residual[i] = pose.get(i) - values.get(i);
                return residual;
            }
            case MIDPOINT -> {
                count(c, 2);
                var curve = object(g, refs.get(1));
                if (curve.kind() != Kind.LINE && curve.kind() != Kind.ARC)
                    throw new IllegalArgumentException("Midpoint needs a line or arc");
                return xy(g.point(refs.get(0)).minus(curve.at(0.5)));
            }
            case CONCENTRIC -> {
                count(c, 2);
                var a = object(g, refs.get(0));
                var b = object(g, refs.get(1));
                circular(a);
                circular(b);
                return xy(a.point(Part.CENTER).minus(b.point(Part.CENTER)));
            }
            case SYMMETRY -> {
                count(c, 3);
                var axis = line(g, refs.get(2));
                var a = refs.get(0);
                var b = refs.get(1);
                if (a.part() != Part.OBJECT && b.part() != Part.OBJECT)
                    return xy(reflect(g.point(a), axis).minus(g.point(b)));
                var ea = object(g, a);
                var eb = object(g, b);
                if (ea.kind() != eb.kind())
                    throw new IllegalArgumentException("Symmetric objects must have the same type");
                var residual = new ArrayList<Double>();
                for (var part : ea.points()) {
                    var delta = reflect(ea.point(part), axis).minus(eb.point(part));
                    residual.add(delta.x());
                    residual.add(delta.y());
                }
                if (ea.kind() == Kind.CIRCLE || ea.kind() == Kind.ARC)
                    residual.add(ea.value(2) - eb.value(2));
                return array(residual);
            }
            case TANGENT, CURVATURE -> {
                count(c, 2);
                var ra = refs.get(0);
                var rb = refs.get(1);
                var a = g.entity(ra.entityId());
                var b = g.entity(rb.entityId());
                if (c.type() == Type.TANGENT
                        && ra.part() == Part.OBJECT
                        && rb.part() == Part.OBJECT) {
                    if (values.size() != 1 || Math.abs(values.getFirst()) != 1)
                        throw new IllegalArgumentException("Tangency branch must be +1 or -1");
                    if (a.kind() == Kind.CIRCLE && b.kind() == Kind.CIRCLE)
                        return new double[] {
                            a.point(Part.CENTER).distance(b.point(Part.CENTER))
                                    - Math.abs(a.value(2) + values.getFirst() * b.value(2))
                        };
                    var line = a.kind() == Kind.LINE ? a : b;
                    var circle = a.kind() == Kind.LINE ? b : a;
                    if (line.kind() != Kind.LINE || circle.kind() != Kind.CIRCLE)
                        throw new IllegalArgumentException(selectionHint(c.type()));
                    return new double[] {
                        direction(line).unit().cross(circle.point(Part.CENTER).minus(line.at(0)))
                                - values.getFirst() * circle.value(2)
                    };
                }
                if (!endpoint(ra)
                        || !endpoint(rb)
                        || a.kind() == Kind.CIRCLE
                        || b.kind() == Kind.CIRCLE)
                    throw new IllegalArgumentException(selectionHint(c.type()));
                double ta = ra.part() == Part.START ? 0 : 1, tb = rb.part() == Part.START ? 0 : 1;
                var delta = a.at(ta).minus(b.at(tb));
                var u = a.derivative(ta).unit().times(ta == 0 ? 1 : -1);
                var v = b.derivative(tb).unit().times(tb == 0 ? 1 : -1);
                double angle = 10 * Math.atan2(u.cross(v), -u.dot(v));
                if (c.type() == Type.TANGENT) return new double[] {delta.x(), delta.y(), angle};
                if (a.kind() != Kind.SPLINE && b.kind() != Kind.SPLINE)
                    throw new IllegalArgumentException("Curvature requires at least one spline");
                return new double[] {
                    delta.x(),
                    delta.y(),
                    angle,
                    100
                            * (a.curvature(ta) * (ta == 0 ? 1 : -1)
                                    + b.curvature(tb) * (tb == 0 ? 1 : -1))
                };
            }
            case POLYGON -> {
                if (n < 3
                        || values.size() != 1
                        || Math.abs(Math.abs(values.getFirst()) - Math.TAU / n) > 1e-12)
                    throw new IllegalArgumentException(
                            "A regular polygon needs at least three lines and its signed turn angle");
                var residual = new ArrayList<Double>();
                double length = direction(line(g, refs.getFirst())).length();
                double angle = values.getFirst();
                for (int i = 0; i < n; i++) {
                    var a = line(g, refs.get(i));
                    var b = line(g, refs.get((i + 1) % n));
                    var gap = a.at(1).minus(b.at(0));
                    residual.add(gap.x());
                    residual.add(gap.y());
                    var d = direction(a);
                    var next = direction(b);
                    var rotated =
                            new Vec(
                                    d.x() * Math.cos(angle) - d.y() * Math.sin(angle),
                                    d.x() * Math.sin(angle) + d.y() * Math.cos(angle));
                    residual.add(next.x() - rotated.x());
                    residual.add(next.y() - rotated.y());
                    if (i > 0) residual.add(d.length() - length);
                }
                return array(residual);
            }
            case LENGTH -> {
                count(c, 1);
                positive(values.getFirst());
                return new double[] {
                    direction(line(g, refs.getFirst())).length() - values.getFirst()
                };
            }
            case RADIUS, DIAMETER -> {
                count(c, 1);
                positive(values.getFirst());
                var curve = object(g, refs.getFirst());
                circular(curve);
                return new double[] {
                    curve.value(2) * (c.type() == Type.DIAMETER ? 2 : 1) - values.getFirst()
                };
            }
            case DISTANCE, DISTANCE_X, DISTANCE_Y -> {
                count(c, 2);
                var d = g.point(refs.get(1)).minus(g.point(refs.get(0)));
                if (c.type() == Type.DISTANCE && values.getFirst() < 0)
                    throw new IllegalArgumentException("Distance cannot be negative");
                return new double[] {
                    (c.type() == Type.DISTANCE
                                    ? d.length()
                                    : c.type() == Type.DISTANCE_X ? d.x() : d.y())
                            - values.getFirst()
                };
            }
            case ANGLE -> {
                if (n != 1 && n != 2) throw new IllegalArgumentException(selectionHint(c.type()));
                var a = n == 1 ? new Vec(1, 0) : direction(line(g, refs.get(0)));
                var b = direction(line(g, refs.getLast()));
                double delta = Math.atan2(a.cross(b), a.dot(b)) - Math.toRadians(values.getFirst());
                return new double[] {10 * Math.atan2(Math.sin(delta), Math.cos(delta))};
            }
        }
        throw new IllegalArgumentException("Unsupported constraint");
    }

    public static void validateGeometry(SketchGeometry g) {
        for (var e : g.entities()) {
            if (e.kind() == Kind.LINE && direction(e).length() < 1e-6)
                throw new IllegalArgumentException("Entity " + e.id() + ": line must not collapse");
            if ((e.kind() == Kind.CIRCLE || e.kind() == Kind.ARC) && e.value(2) < 1e-6)
                throw new IllegalArgumentException(
                        "Entity " + e.id() + ": radius must be at least 0.000001 mm");
            if (e.kind() == Kind.ARC
                    && (Math.abs(e.value(4)) < 1e-6 || Math.abs(e.value(4)) >= Math.TAU))
                throw new IllegalArgumentException(
                        "Entity "
                                + e.id()
                                + ": arc sweep must be nonzero and less than a full circle");
            if (e.kind() == Kind.SPLINE
                    && (e.derivative(0).length() < 1e-6 || e.derivative(1).length() < 1e-6))
                throw new IllegalArgumentException(
                        "Entity " + e.id() + ": spline endpoint handles must not collapse");
        }
        for (String id : g.boundary()) {
            var e = g.entity(id);
            if (e.construction() || e.kind() == Kind.POINT)
                throw new IllegalArgumentException(
                        "Profile boundary cannot contain construction geometry or points: " + id);
        }
    }

    public static void requireSatisfied(SketchGeometry g) {
        validateGeometry(g);
        for (var c : g.constraints())
            for (double r : residuals(g, c))
                if (!Double.isFinite(r) || Math.abs(r) > TOLERANCE)
                    throw new IllegalArgumentException(
                            "Constraint "
                                    + c.id()
                                    + " is not satisfied; edit the sketch to resolve it");
    }

    private static boolean endpoint(Ref r) {
        return r.part() == Part.START || r.part() == Part.END;
    }

    private static Vec direction(Entity e) {
        return e.at(1).minus(e.at(0));
    }

    private static Entity object(SketchGeometry g, Ref r) {
        if (r.part() != Part.OBJECT)
            throw new IllegalArgumentException("Select an entity, not its point: " + r);
        return g.entity(r.entityId());
    }

    private static Entity line(SketchGeometry g, Ref r) {
        var e = object(g, r);
        if (e.kind() != Kind.LINE) throw new IllegalArgumentException("Select a line: " + r);
        return e;
    }

    private static void circular(Entity e) {
        if (e.kind() != Kind.CIRCLE && e.kind() != Kind.ARC)
            throw new IllegalArgumentException("Select a circle or arc: " + e.id());
    }

    private static void count(Constraint c, int count) {
        if (c.refs().size() != count) throw new IllegalArgumentException(selectionHint(c.type()));
    }

    private static void positive(double value) {
        if (value < 1e-6)
            throw new IllegalArgumentException("Dimension must be at least 0.000001 mm");
    }

    private static Vec reflect(Vec p, Entity axis) {
        var u = direction(axis).unit();
        var d = p.minus(axis.at(0));
        return axis.at(0).plus(u.times(2 * d.dot(u))).minus(d);
    }

    private static double[] xy(Vec v) {
        return new double[] {v.x(), v.y()};
    }

    private static double[] array(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).toArray();
    }

    private static double nearest(Entity curve, Vec point) {
        // Bracket the closest interval, then refine without introducing persisted auxiliary points.
        double t = 0, distance = Double.POSITIVE_INFINITY;
        for (int i = 0; i <= 64; i++) {
            double next = (double) i / 64, d = curve.at(next).distance(point);
            if (d < distance) {
                distance = d;
                t = next;
            }
        }
        double lo = Math.max(0, t - 1.0 / 64), hi = Math.min(1, t + 1.0 / 64);
        for (int i = 0; i < 45; i++) {
            double a = lo + (hi - lo) / 3, b = hi - (hi - lo) / 3;
            if (curve.at(a).distance(point) < curve.at(b).distance(point)) hi = b;
            else lo = a;
        }
        return (lo + hi) / 2;
    }
}
