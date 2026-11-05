package dev.voxelbrew.engine.sketch;

import static dev.voxelbrew.engine.sketch.SketchGeometry.*;

import java.util.*;
import org.apache.commons.math3.linear.*;

/** Frontend-neutral construction methods for the 2D sketch Create tool families. */
public final class SketchTools {
    private SketchTools() {}

    public enum Tool {
        RECTANGLE_TWO_POINT("2-point rectangle", 2),
        RECTANGLE_THREE_POINT("3-point rectangle", 3),
        RECTANGLE_CENTER("Center rectangle", 2),
        CIRCLE_CENTER("Center diameter circle", 2),
        CIRCLE_TWO_POINT("2-point circle", 2),
        CIRCLE_THREE_POINT("3-point circle", 3),
        CIRCLE_TWO_TANGENT("2-tangent circle", 1),
        CIRCLE_THREE_TANGENT("3-tangent circle", 1),
        ARC_CENTER("Center point arc", 3),
        ARC_THREE_POINT("3-point arc", 3),
        ARC_TANGENT("Tangent arc", 1),
        POLYGON_INSCRIBED("Inscribed polygon", 2),
        POLYGON_CIRCUMSCRIBED("Circumscribed polygon", 2),
        POLYGON_EDGE("Edge polygon", 3),
        ELLIPSE("Ellipse", 3),
        SLOT_CENTER_TO_CENTER("Center-to-center slot", 3),
        SLOT_OVERALL("Overall slot", 3),
        SLOT_CENTER_POINT("Center point slot", 3),
        SLOT_ARC_THREE_POINT("3-point arc slot", 4),
        SLOT_ARC_CENTER("Center point arc slot", 4),
        FIT_SPLINE("Fit point spline", 0),
        CONTROL_SPLINE("Control point spline", 0),
        CONIC("Conic curve", 3),
        TEXT("Text", 1),
        TEXT_ON_PATH("Text on path", 1);
        private final String label;
        private final int clicks;

        Tool(String label, int clicks) {
            this.label = label;
            this.clicks = clicks;
        }

        public int clicks() {
            return clicks;
        }

        @Override
        public String toString() {
            return label;
        }

        public String instructions() {
            return switch (this) {
                case RECTANGLE_TWO_POINT -> "Click two opposite corners.";
                case RECTANGLE_THREE_POINT ->
                        "Click two corners along the first edge, then the opposite edge.";
                case RECTANGLE_CENTER -> "Click the center, then a corner.";
                case CIRCLE_CENTER -> "Click the center, then a point on the circumference.";
                case CIRCLE_TWO_POINT -> "Click opposite ends of the diameter.";
                case CIRCLE_THREE_POINT -> "Click three points on the circumference.";
                case CIRCLE_TWO_TANGENT ->
                        "Select two lines, set Radius, then click near the desired circle center.";
                case CIRCLE_THREE_TANGENT ->
                        "Select three lines, then click near the desired circle center.";
                case ARC_CENTER -> "Click center, start, then end (counterclockwise).";
                case ARC_THREE_POINT -> "Click start, end, then a point along the arc.";
                case ARC_TANGENT -> "Select a curve endpoint, then click the new arc endpoint.";
                case POLYGON_INSCRIBED -> "Set Sides; click center, then a vertex.";
                case POLYGON_CIRCUMSCRIBED ->
                        "Set Sides; click center, then the midpoint of an edge.";
                case POLYGON_EDGE ->
                        "Set Sides; click two endpoints of an edge, then the side containing the polygon.";
                case ELLIPSE ->
                        "Click center, first axis endpoint, then a point on the second axis.";
                case SLOT_CENTER_TO_CENTER ->
                        "Click both cap centers, then a point on the slot's side.";
                case SLOT_OVERALL ->
                        "Click both overall ends, then a point defining the half-width.";
                case SLOT_CENTER_POINT ->
                        "Click the slot center, one cap center, then a point defining the half-width.";
                case SLOT_ARC_THREE_POINT ->
                        "Click centerline start, end, a point on its arc, then a point defining width.";
                case SLOT_ARC_CENTER ->
                        "Click center, centerline start, end, then a point defining width.";
                case FIT_SPLINE, CONTROL_SPLINE ->
                        "Place points, then choose Complete curve or press Enter. Closed joins back to the start.";
                case CONIC ->
                        "Set Rho (0–1); click start, end, then the tangent-intersection vertex.";
                case TEXT -> "Enter text and height, then click its baseline anchor.";
                case TEXT_ON_PATH ->
                        "Select a path curve; enter text/height, then click near the desired start on the path.";
            };
        }
    }

    public record Options(
            int sides,
            int degree,
            boolean closed,
            boolean construction,
            double radius,
            double rho,
            String text,
            String font,
            int style,
            double height,
            double angle,
            double spacing) {
        public Options {
            for (double v : new double[] {radius, rho, height, angle, spacing})
                if (!Double.isFinite(v))
                    throw new IllegalArgumentException("Tool options must be finite");
        }

        public static Options defaults() {
            return new Options(6, 3, false, false, 5, .5, "Text", "SansSerif", 0, 10, 0, 0);
        }
    }

    public static SketchGeometry add(
            SketchGeometry source, Tool tool, String id, List<Vec> p, List<Ref> refs, Options o) {
        if (tool.clicks() != 0 && p.size() != tool.clicks())
            throw new IllegalArgumentException(tool.instructions());
        if (tool.clicks() == 0 && p.size() < 2)
            throw new IllegalArgumentException("Place at least two spline points");
        for (var point : p)
            if (!Double.isFinite(point.x()) || !Double.isFinite(point.y()))
                throw new IllegalArgumentException("Points must be finite");
        var entities = new ArrayList<Entity>();
        SketchGeometry addition = null;
        Vec a = p.getFirst(), b = p.size() > 1 ? p.get(1) : a;
        switch (tool) {
            case RECTANGLE_TWO_POINT, RECTANGLE_CENTER -> {
                Vec start = tool == Tool.RECTANGLE_CENTER ? a.times(2).minus(b) : a;
                addition =
                        Sketches.rectangle(
                                id,
                                Math.min(start.x(), b.x()),
                                Math.min(start.y(), b.y()),
                                Math.abs(b.x() - start.x()),
                                Math.abs(b.y() - start.y()),
                                false);
            }
            case RECTANGLE_THREE_POINT -> {
                var n = b.minus(a).unit().normal();
                var d = n.times(p.get(2).minus(a).dot(n));
                entities.add(line(id + "-1", a, b));
                entities.add(line(id + "-2", b, b.plus(d)));
                entities.add(line(id + "-3", b.plus(d), a.plus(d)));
                entities.add(line(id + "-4", a.plus(d), a));
                addition = join(entities);
                addition =
                        constrain(
                                addition,
                                id + "-parallel-1",
                                Type.PARALLEL,
                                entities.get(0).ref(),
                                entities.get(2).ref());
                addition =
                        constrain(
                                addition,
                                id + "-parallel-2",
                                Type.PARALLEL,
                                entities.get(1).ref(),
                                entities.get(3).ref());
                addition =
                        constrain(
                                addition,
                                id + "-right-angle",
                                Type.PERPENDICULAR,
                                entities.get(0).ref(),
                                entities.get(1).ref());
            }
            case CIRCLE_CENTER -> entities.add(circle(id, a, a.distance(b)));
            case CIRCLE_TWO_POINT -> entities.add(circle(id, a.mix(b, .5), a.distance(b) / 2));
            case CIRCLE_THREE_POINT -> {
                var c = circumcenter(a, b, p.get(2));
                entities.add(circle(id, c, c.distance(a)));
            }
            case CIRCLE_TWO_TANGENT, CIRCLE_THREE_TANGENT ->
                    entities.add(
                            tangentCircle(
                                    source,
                                    id,
                                    refs,
                                    a,
                                    o.radius(),
                                    tool == Tool.CIRCLE_THREE_TANGENT));
            case ARC_CENTER -> entities.add(centerArc(id, a, b, p.get(2)));
            case ARC_THREE_POINT -> entities.add(threeArc(id, a, b, p.get(2)));
            case ARC_TANGENT -> {
                if (refs.size() != 1
                        || (refs.getFirst().part() != Part.START
                                && refs.getFirst().part() != Part.END))
                    throw new IllegalArgumentException(tool.instructions());
                var ref = refs.getFirst();
                var curve = source.entity(ref.entityId());
                double t = ref.part() == Part.START ? 0 : 1;
                var start = curve.at(t);
                var tangent = curve.derivative(t).unit().times(t == 0 ? -1 : 1);
                var normal = tangent.normal();
                var d = a.minus(start);
                double dot = d.dot(normal);
                if (Math.abs(dot) < 1e-9)
                    throw new IllegalArgumentException(
                            "Tangent arc endpoint must be off the tangent line");
                double r = d.dot(d) / (2 * dot);
                var center = start.plus(normal.times(r));
                double angle = Math.atan2(start.y() - center.y(), start.x() - center.x());
                double sweep =
                        positiveAngle(Math.atan2(a.y() - center.y(), a.x() - center.x()) - angle);
                if (r < 0) sweep -= Math.TAU;
                entities.add(arc(id, center, Math.abs(r), angle, sweep));
            }
            case POLYGON_INSCRIBED, POLYGON_CIRCUMSCRIBED, POLYGON_EDGE -> {
                if (o.sides() < 3 || o.sides() > 32)
                    throw new IllegalArgumentException("Sides must be 3–32");
                Vec center = a;
                double radius = a.distance(b),
                        angle = Math.atan2(b.y() - a.y(), b.x() - a.x()),
                        orientation = 1;
                if (tool == Tool.POLYGON_CIRCUMSCRIBED) {
                    radius /= Math.cos(Math.PI / o.sides());
                    angle -= Math.PI / o.sides();
                }
                if (tool == Tool.POLYGON_EDGE) {
                    var d = b.minus(a);
                    double sign = Math.copySign(1, d.cross(p.get(2).minus(a)));
                    orientation = sign;
                    center =
                            a.mix(b, .5)
                                    .plus(
                                            d.unit()
                                                    .normal()
                                                    .times(
                                                            sign
                                                                    * d.length()
                                                                    / (2
                                                                            * Math.tan(
                                                                                    Math.PI
                                                                                            / o
                                                                                                    .sides()))));
                    radius = center.distance(a);
                    angle = Math.atan2(a.y() - center.y(), a.x() - center.x());
                }
                var base = Sketches.polygon(id, 0, 0, radius, o.sides());
                for (var e : base.entities()) {
                    var values = new ArrayList<Double>();
                    for (int i = 0; i < 4; i += 2) {
                        var v =
                                SketchCurves.rotate(
                                                new Vec(e.value(i), orientation * e.value(i + 1)),
                                                angle)
                                        .plus(center);
                        values.add(v.x());
                        values.add(v.y());
                    }
                    entities.add(e.withValues(values));
                }
                addition = base.withEntities(entities);
                if (orientation < 0)
                    addition =
                            addition.withConstraints(
                                    List.of(
                                            SketchConstraints.create(
                                                    addition,
                                                    id + "-regular",
                                                    Type.POLYGON,
                                                    entities.stream().map(Entity::ref).toList(),
                                                    0)));
            }
            case ELLIPSE -> {
                var axis = b.minus(a);
                double angle = Math.atan2(axis.y(), axis.x()),
                        minor = Math.abs(p.get(2).minus(a).dot(axis.unit().normal()));
                entities.add(
                        Sketches.entity(
                                id,
                                Kind.ELLIPSE,
                                false,
                                a.x(),
                                a.y(),
                                axis.length(),
                                minor,
                                angle));
            }
            case SLOT_CENTER_TO_CENTER, SLOT_OVERALL, SLOT_CENTER_POINT -> {
                var direction = b.minus(a).unit();
                double r = Math.abs(p.get(2).minus(a).dot(direction.normal()));
                Vec first = a, last = b;
                if (tool == Tool.SLOT_CENTER_POINT) first = a.times(2).minus(b);
                if (tool == Tool.SLOT_OVERALL) {
                    if (2 * r >= a.distance(b) - 1e-6)
                        throw new IllegalArgumentException("Overall length must exceed slot width");
                    first = a.plus(direction.times(r));
                    last = b.minus(direction.times(r));
                }
                addition = straightSlot(id, first, last, r);
            }
            case SLOT_ARC_CENTER, SLOT_ARC_THREE_POINT -> {
                var centerline =
                        tool == Tool.SLOT_ARC_CENTER
                                ? centerArc(id, a, b, p.get(2))
                                : threeArc(id, a, b, p.get(2));
                var center = new Vec(centerline.value(0), centerline.value(1));
                double halfWidth = Math.abs(center.distance(p.get(3)) - centerline.value(2));
                addition =
                        arcSlot(
                                id,
                                center,
                                centerline.value(2),
                                centerline.value(3),
                                centerline.value(4),
                                halfWidth);
            }
            case FIT_SPLINE, CONTROL_SPLINE -> {
                var values = new ArrayList<Double>();
                for (var point : p) {
                    values.add(point.x());
                    values.add(point.y());
                }
                entities.add(
                        new Entity(
                                id,
                                tool == Tool.FIT_SPLINE ? Kind.FIT_SPLINE : Kind.CONTROL_SPLINE,
                                values,
                                false,
                                tool == Tool.CONTROL_SPLINE ? o.degree() : 0,
                                o.closed(),
                                null));
            }
            case CONIC ->
                    entities.add(
                            Sketches.entity(
                                    id,
                                    Kind.CONIC,
                                    false,
                                    a.x(),
                                    a.y(),
                                    p.get(2).x(),
                                    p.get(2).y(),
                                    b.x(),
                                    b.y(),
                                    o.rho()));
            case TEXT, TEXT_ON_PATH -> {
                String path = "";
                double x = a.x(), y = a.y();
                if (tool == Tool.TEXT_ON_PATH) {
                    if (refs.size() != 1 || refs.getFirst().part() != Part.OBJECT)
                        throw new IllegalArgumentException(tool.instructions());
                    path = refs.getFirst().entityId();
                    var curve = source.entity(path);
                    var samples = curve.sample();
                    double nearest = Double.POSITIVE_INFINITY, distance = 0, best = 0;
                    for (int i = 0; i < samples.size(); i++) {
                        if (i > 0) distance += samples.get(i - 1).distance(samples.get(i));
                        double d = samples.get(i).distance(a);
                        if (d < nearest) {
                            nearest = d;
                            best = distance;
                        }
                    }
                    x = best;
                    y = 0;
                }
                entities.add(
                        SketchText.create(
                                id,
                                o.text(),
                                o.font(),
                                o.style(),
                                x,
                                y,
                                o.height(),
                                o.angle(),
                                o.spacing(),
                                path,
                                o.construction()));
            }
        }
        if (addition == null)
            addition =
                    new SketchGeometry(
                            entities, List.of(), entities.stream().map(Entity::id).toList());
        if (o.construction())
            addition =
                    addition.withEntities(
                                    addition.entities().stream()
                                            .map(e -> e.withConstruction(true))
                                            .toList())
                            .withBoundary(List.of());
        var all = new ArrayList<>(source.entities());
        all.addAll(addition.entities());
        var constraints = new ArrayList<>(source.constraints());
        constraints.addAll(addition.constraints());
        var boundary = new ArrayList<>(source.boundary());
        boundary.addAll(addition.boundary());
        if (tool == Tool.TEXT_ON_PATH) boundary.remove(refs.getFirst().entityId());
        var result = new SketchGeometry(all, constraints, boundary);
        if (tool == Tool.CIRCLE_TWO_TANGENT || tool == Tool.CIRCLE_THREE_TANGENT) {
            for (int i = 0; i < refs.size(); i++)
                result =
                        constrain(
                                result,
                                id + "-tangent-" + i,
                                Type.TANGENT,
                                new Ref(id, Part.OBJECT),
                                refs.get(i));
            if (tool == Tool.CIRCLE_TWO_TANGENT)
                result =
                        result.add(
                                SketchConstraints.create(
                                        result,
                                        id + "-radius",
                                        Type.RADIUS,
                                        List.of(new Ref(id, Part.OBJECT)),
                                        o.radius()));
        }
        if (tool == Tool.ARC_TANGENT)
            result =
                    constrain(
                            result,
                            id + "-tangent",
                            Type.TANGENT,
                            refs.getFirst(),
                            new Ref(id, Part.START));
        SketchConstraints.validateGeometry(result);
        return result;
    }

    private static Entity line(String id, Vec a, Vec b) {
        return Sketches.entity(id, Kind.LINE, false, a.x(), a.y(), b.x(), b.y());
    }

    private static Entity circle(String id, Vec c, double r) {
        return Sketches.entity(id, Kind.CIRCLE, false, c.x(), c.y(), r);
    }

    private static Entity arc(String id, Vec c, double r, double angle, double sweep) {
        return Sketches.entity(id, Kind.ARC, false, c.x(), c.y(), r, angle, sweep);
    }

    private static SketchGeometry constrain(SketchGeometry g, String id, Type type, Ref... refs) {
        return g.add(SketchConstraints.create(g, id, type, List.of(refs), 0));
    }

    private static SketchGeometry join(List<Entity> entities) {
        var g = new SketchGeometry(entities, List.of(), entities.stream().map(Entity::id).toList());
        for (int i = 0; i < entities.size(); i++)
            g =
                    constrain(
                            g,
                            entities.get(i).id() + "-join",
                            Type.COINCIDENT,
                            entities.get(i).ref(Part.END),
                            entities.get((i + 1) % entities.size()).ref(Part.START));
        return g;
    }

    public static Vec circumcenter(Vec a, Vec b, Vec c) {
        var u = b.minus(a);
        var v = c.minus(a);
        double determinant = 2 * u.cross(v);
        if (Math.abs(determinant) < 1e-10)
            throw new IllegalArgumentException("Three points must not be collinear");
        return a.plus(
                new Vec(
                        (u.dot(u) * v.y() - v.dot(v) * u.y()) / determinant,
                        (u.x() * v.dot(v) - v.x() * u.dot(u)) / determinant));
    }

    private static double positiveAngle(double a) {
        return ((a % Math.TAU) + Math.TAU) % Math.TAU;
    }

    private static Entity centerArc(String id, Vec c, Vec a, Vec b) {
        double start = Math.atan2(a.y() - c.y(), a.x() - c.x());
        return arc(
                id,
                c,
                c.distance(a),
                start,
                positiveAngle(Math.atan2(b.y() - c.y(), b.x() - c.x()) - start));
    }

    private static Entity threeArc(String id, Vec a, Vec b, Vec through) {
        var c = circumcenter(a, b, through);
        double start = Math.atan2(a.y() - c.y(), a.x() - c.x());
        double sweep = positiveAngle(Math.atan2(b.y() - c.y(), b.x() - c.x()) - start);
        if (positiveAngle(Math.atan2(through.y() - c.y(), through.x() - c.x()) - start) > sweep)
            sweep -= Math.TAU;
        return arc(id, c, c.distance(a), start, sweep);
    }

    private static SketchGeometry straightSlot(String id, Vec a, Vec b, double r) {
        if (a.distance(b) < 1e-6 || r < 1e-6)
            throw new IllegalArgumentException("Slot length and width must be positive");
        var u = b.minus(a).unit();
        var n = u.normal().times(r);
        double angle = Math.atan2(u.y(), u.x());
        var entities =
                List.of(
                        line(id + "-top", a.plus(n), b.plus(n)),
                        arc(id + "-end", b, r, angle + Math.PI / 2, -Math.PI),
                        line(id + "-bottom", b.minus(n), a.minus(n)),
                        arc(id + "-start", a, r, angle - Math.PI / 2, -Math.PI));
        var g = join(entities);
        for (int i = 0; i < 4; i++)
            g =
                    constrain(
                            g,
                            id + "-smooth-" + i,
                            Type.TANGENT,
                            entities.get(i).ref(Part.END),
                            entities.get((i + 1) % 4).ref(Part.START));
        g =
                constrain(
                        g,
                        id + "-parallel",
                        Type.PARALLEL,
                        entities.get(0).ref(),
                        entities.get(2).ref());
        return constrain(
                g, id + "-equal-caps", Type.EQUAL, entities.get(1).ref(), entities.get(3).ref());
    }

    private static SketchGeometry arcSlot(
            String id, Vec c, double radius, double start, double sweep, double r) {
        if (r < 1e-6 || r >= radius)
            throw new IllegalArgumentException(
                    "Arc slot half-width must be positive and smaller than its centerline radius");
        if (sweep < 0) {
            start += sweep;
            sweep = -sweep;
        }
        var a = c.plus(new Vec(Math.cos(start), Math.sin(start)).times(radius));
        var b = c.plus(new Vec(Math.cos(start + sweep), Math.sin(start + sweep)).times(radius));
        var entities =
                List.of(
                        arc(id + "-outer", c, radius + r, start, sweep),
                        arc(id + "-end", b, r, start + sweep, Math.PI),
                        arc(id + "-inner", c, radius - r, start + sweep, -sweep),
                        arc(id + "-start", a, r, start + Math.PI, Math.PI));
        var g = join(entities);
        for (int i = 0; i < 4; i++)
            g =
                    constrain(
                            g,
                            id + "-smooth-" + i,
                            Type.TANGENT,
                            entities.get(i).ref(Part.END),
                            entities.get((i + 1) % 4).ref(Part.START));
        g =
                constrain(
                        g,
                        id + "-concentric",
                        Type.CONCENTRIC,
                        entities.get(0).ref(),
                        entities.get(2).ref());
        return constrain(
                g, id + "-equal-caps", Type.EQUAL, entities.get(1).ref(), entities.get(3).ref());
    }

    private static Entity tangentCircle(
            SketchGeometry source,
            String id,
            List<Ref> refs,
            Vec hint,
            double radius,
            boolean three) {
        if (refs.size() != (three ? 3 : 2))
            throw new IllegalArgumentException("Select " + (three ? 3 : 2) + " lines first");
        var lines =
                refs.stream()
                        .map(
                                r -> {
                                    var e = source.entity(r.entityId());
                                    if (r.part() != Part.OBJECT || e.kind() != Kind.LINE)
                                        throw new IllegalArgumentException("Select sketch lines");
                                    return e;
                                })
                        .toList();
        Entity best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int bits = 0; bits < (1 << refs.size()); bits++) {
            int n = three ? 3 : 2;
            double[][] matrix = new double[n][n];
            double[] rhs = new double[n];
            for (int i = 0; i < n; i++) {
                var line = lines.get(i);
                var normal = line.at(1).minus(line.at(0)).unit().normal();
                double sign = (bits & (1 << i)) == 0 ? 1 : -1;
                matrix[i][0] = normal.x();
                matrix[i][1] = normal.y();
                rhs[i] = normal.dot(line.at(0));
                if (three) matrix[i][2] = -sign;
                else rhs[i] += sign * radius;
            }
            var solver =
                    new SingularValueDecomposition(new Array2DRowRealMatrix(matrix, false))
                            .getSolver();
            var answer = solver.solve(new ArrayRealVector(rhs, false));
            // Parallel lines leave one center coordinate free; choose the branch nearest the click.
            if (!three) {
                var normal = lines.get(0).at(1).minus(lines.get(0).at(0)).unit().normal();
                if (Math.abs(matrix[0][0] * matrix[1][1] - matrix[0][1] * matrix[1][0]) < 1e-9) {
                    var tangent = normal.normal();
                    double along = hint.dot(tangent);
                    answer.setEntry(0, answer.getEntry(0) + tangent.x() * along);
                    answer.setEntry(1, answer.getEntry(1) + tangent.y() * along);
                }
            }
            double error =
                    new Array2DRowRealMatrix(matrix, false)
                            .operate(answer)
                            .subtract(new ArrayRealVector(rhs, false))
                            .getNorm();
            double r = three ? answer.getEntry(2) : radius;
            if (r < 1e-6 || error > 1e-6) continue;
            var center = new Vec(answer.getEntry(0), answer.getEntry(1));
            double distance = center.distance(hint);
            if (distance < bestDistance) {
                best = circle(id, center, r);
                bestDistance = distance;
            }
        }
        if (best == null)
            throw new IllegalArgumentException("No circle fits those tangent lines and radius");
        return best;
    }
}
