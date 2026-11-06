package dev.voxelbrew.engine.sketch;

import dev.voxelbrew.engine.model.CadDocument;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Immutable, millimeter sketch definition. Entity and constraint IDs survive editing and saves. */
public record SketchGeometry(List<Entity> entities, List<Constraint> constraints, List<String> boundary) {
    public static final double TOLERANCE = 1e-6;
    public static final Ref ORIGIN = new Ref("origin", Part.POINT);

    public SketchGeometry {
        entities = List.copyOf(entities);
        constraints = List.copyOf(constraints);
        boundary = List.copyOf(boundary);
        if (entities.size() > 64 || constraints.size() > 256)
            throw new IllegalArgumentException("A sketch supports up to 64 entities and 256 constraints");
        var ids = new HashSet<String>();
        for (var e : entities)
            if (e.id().equals("origin") || !ids.add(e.id()))
                throw new IllegalArgumentException("Duplicate or reserved sketch entity ID: " + e.id());
        ids.clear();
        for (var c : constraints)
            if (!ids.add(c.id())) throw new IllegalArgumentException("Duplicate constraint ID: " + c.id());
        if (new HashSet<>(boundary).size() != boundary.size())
            throw new IllegalArgumentException("Duplicate profile boundary entity");
    }

    public static SketchGeometry empty() {
        return new SketchGeometry(List.of(), List.of(), List.of());
    }

    static String derivedId(String id, String suffix) {
        String result = id + suffix;
        if (result.length() <= 128) return result;
        return id.substring(0, Math.min(80, id.length()))
                + "-"
                + java.util.UUID.nameUUIDFromBytes(result.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public enum Kind {
        POINT("Point", "x", "y"),
        LINE("Line", "startX", "startY", "endX", "endY"),
        CIRCLE("Circle", "centerX", "centerY", "radius"),
        ARC("Arc", "centerX", "centerY", "radius", "startAngle", "sweepAngle"),
        ELLIPSE("Ellipse", "centerX", "centerY", "majorRadius", "minorRadius", "angle"),
        CONIC("Conic curve", "startX", "startY", "vertexX", "vertexY", "endX", "endY", "rho"),
        FIT_SPLINE("Fit point spline"),
        CONTROL_SPLINE("Control point spline"),
        TEXT("Text", "x", "y", "height", "angle", "spacing"),
        SPLINE("Cubic spline", "startX", "startY", "control1X", "control1Y", "control2X", "control2Y", "endX", "endY");

        private final String label;
        private final List<String> parameters;

        Kind(String label, String... parameters) {
            this.label = label;
            this.parameters = List.of(parameters);
        }

        public List<String> parameters() {
            return parameters;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** Arc angles are radians. Splines are exact cubic Bezier curves, not sampled polylines. */
    public record Entity(
            String id,
            Kind kind,
            List<Double> values,
            boolean construction,
            int degree,
            boolean closed,
            SketchText.Data text) {
        public Entity(String id, Kind kind, List<Double> values, boolean construction) {
            this(id, kind, values, construction, kind == Kind.CONTROL_SPLINE ? 3 : 0, false, null);
        }

        public Entity {
            CadDocument.identifier(id, "sketch entity ID");
            java.util.Objects.requireNonNull(kind);
            values = List.copyOf(values);
            boolean variable = kind == Kind.FIT_SPLINE || kind == Kind.CONTROL_SPLINE;
            if (variable
                    && (values.size() % 2 != 0
                            || values.size() > 64
                            || values.size() < (kind == Kind.CONTROL_SPLINE ? (degree + 1) * 2 : closed ? 6 : 4)))
                throw new IllegalArgumentException("A spline needs 2–32 fit points or degree+1–32 control points");
            if (kind == Kind.CONTROL_SPLINE && degree != 3 && degree != 5)
                throw new IllegalArgumentException("Control spline degree must be 3 or 5");
            if ((kind != Kind.CONTROL_SPLINE && degree != 0) || (!variable && closed))
                throw new IllegalArgumentException("Degree and closed flags apply only to spline types");
            if ((kind == Kind.TEXT) != (text != null))
                throw new IllegalArgumentException("Text geometry requires its persisted glyph outlines");
            if (!variable && values.size() != kind.parameters().size())
                throw new IllegalArgumentException(
                        kind + " needs " + kind.parameters().size() + " parameters");
            for (double value : values)
                if (!Double.isFinite(value))
                    throw new IllegalArgumentException("Entity " + id + ": coordinates must be finite");
        }

        public double value(int i) {
            return values.get(i);
        }

        public Entity withValues(List<Double> next) {
            return new Entity(id, kind, next, construction, degree, closed, text);
        }

        public Entity withConstruction(boolean next) {
            return new Entity(id, kind, values, next, degree, closed, text);
        }

        public Ref ref() {
            return new Ref(id, Part.OBJECT);
        }

        public Ref ref(Part part) {
            return new Ref(id, part);
        }

        public List<Ref> pointRefs() {
            var result = new ArrayList<>(points().stream().map(this::ref).toList());
            if (kind == Kind.FIT_SPLINE || kind == Kind.CONTROL_SPLINE)
                for (int i = 0; i < values.size() / 2; i++)
                    result.add(new Ref(id, kind == Kind.FIT_SPLINE ? Part.FIT : Part.CONTROL, i));
            return List.copyOf(result);
        }

        public Vec point(Ref ref) {
            if (ref.part() == Part.FIT || ref.part() == Part.CONTROL) {
                if ((ref.part() == Part.FIT ? kind != Kind.FIT_SPLINE : kind != Kind.CONTROL_SPLINE)
                        || ref.index() < 0
                        || ref.index() >= values.size() / 2)
                    throw new IllegalArgumentException("Invalid spline point: " + ref);
                return new Vec(value(2 * ref.index()), value(2 * ref.index() + 1));
            }
            return point(ref.part());
        }

        public boolean loop() {
            return kind == Kind.CIRCLE || kind == Kind.ELLIPSE || closed;
        }

        public List<Part> points() {
            return switch (kind) {
                case POINT -> List.of(Part.POINT);
                case LINE -> List.of(Part.START, Part.END);
                case CIRCLE -> List.of(Part.CENTER);
                case ARC -> List.of(Part.CENTER, Part.START, Part.END);
                case SPLINE -> List.of(Part.START, Part.CONTROL1, Part.CONTROL2, Part.END);
                case ELLIPSE -> List.of(Part.CENTER, Part.MAJOR, Part.MINOR);
                case CONIC -> List.of(Part.START, Part.CONTROL1, Part.END);
                case FIT_SPLINE, CONTROL_SPLINE -> List.of(Part.START, Part.END);
                case TEXT -> List.of(Part.POINT);
            };
        }

        public Vec point(Part part) {
            if (!points().contains(part)) throw new IllegalArgumentException(id + " has no " + part + " point");
            return switch (part) {
                case POINT, CENTER -> new Vec(value(0), value(1));
                case START -> at(0);
                case END -> at(1);
                case CONTROL1 -> new Vec(value(2), value(3));
                case CONTROL2 -> new Vec(value(4), value(5));
                case MAJOR -> at(0);
                case MINOR -> at(0.25);
                case CONTROL, FIT -> throw new IllegalArgumentException("Select an indexed spline point");
                case OBJECT -> throw new IllegalArgumentException("Select an endpoint or center");
            };
        }

        public Vec at(double t) {
            if (kind == Kind.ELLIPSE
                    || kind == Kind.CONIC
                    || kind == Kind.FIT_SPLINE
                    || kind == Kind.CONTROL_SPLINE
                    || kind == Kind.TEXT) return SketchCurves.at(this, t, 0);
            return switch (kind) {
                case POINT -> new Vec(value(0), value(1));
                case LINE -> new Vec(value(0), value(1)).mix(new Vec(value(2), value(3)), t);
                case CIRCLE, ARC -> {
                    double angle = kind == Kind.CIRCLE ? t * Math.TAU : value(3) + t * value(4);
                    yield new Vec(value(0) + value(2) * Math.cos(angle), value(1) + value(2) * Math.sin(angle));
                }
                case SPLINE -> {
                    double s = 1 - t;
                    yield new Vec(value(0), value(1))
                            .times(s * s * s)
                            .plus(new Vec(value(2), value(3)).times(3 * s * s * t))
                            .plus(new Vec(value(4), value(5)).times(3 * s * t * t))
                            .plus(new Vec(value(6), value(7)).times(t * t * t));
                }
                default -> throw new IllegalArgumentException("Unsupported curve");
            };
        }

        public Vec derivative(double t) {
            if (kind == Kind.ELLIPSE
                    || kind == Kind.CONIC
                    || kind == Kind.FIT_SPLINE
                    || kind == Kind.CONTROL_SPLINE
                    || kind == Kind.TEXT) return SketchCurves.at(this, t, 1);
            return switch (kind) {
                case POINT -> new Vec(0, 0);
                case LINE -> at(1).minus(at(0));
                case CIRCLE, ARC -> {
                    double sweep = kind == Kind.CIRCLE ? Math.TAU : value(4);
                    double angle = kind == Kind.CIRCLE ? t * sweep : value(3) + t * sweep;
                    yield new Vec(-Math.sin(angle), Math.cos(angle)).times(value(2) * sweep);
                }
                case SPLINE -> {
                    var p0 = new Vec(value(0), value(1));
                    var p1 = new Vec(value(2), value(3));
                    var p2 = new Vec(value(4), value(5));
                    var p3 = new Vec(value(6), value(7));
                    yield p1.minus(p0)
                            .times(3 * (1 - t) * (1 - t))
                            .plus(p2.minus(p1).times(6 * (1 - t) * t))
                            .plus(p3.minus(p2).times(3 * t * t));
                }
                default -> throw new IllegalArgumentException("Unsupported curve");
            };
        }

        public double curvature(double t) {
            if (kind == Kind.ELLIPSE || kind == Kind.CONIC || kind == Kind.FIT_SPLINE || kind == Kind.CONTROL_SPLINE) {
                var first = SketchCurves.at(this, t, 1);
                return first.cross(SketchCurves.at(this, t, 2)) / Math.pow(Math.max(first.length(), 1e-12), 3);
            }
            if (kind == Kind.CIRCLE) return 1 / value(2);
            if (kind == Kind.ARC) return Math.copySign(1 / value(2), value(4));
            if (kind != Kind.SPLINE) return 0;
            var p0 = new Vec(value(0), value(1));
            var p1 = new Vec(value(2), value(3));
            var p2 = new Vec(value(4), value(5));
            var p3 = new Vec(value(6), value(7));
            var second = p2.minus(p1.times(2))
                    .plus(p0)
                    .times(6 * (1 - t))
                    .plus(p3.minus(p2.times(2)).plus(p1).times(6 * t));
            var first = derivative(t);
            return first.cross(second) / Math.pow(Math.max(first.length(), 1e-12), 3);
        }

        public List<Vec> sample() {
            if (kind == Kind.FIT_SPLINE || kind == Kind.CONTROL_SPLINE) return SketchCurves.sample(this, 128);
            int count = kind == Kind.LINE || kind == Kind.POINT ? 1 : 128;
            var result = new ArrayList<Vec>();
            for (int i = 0; i <= count; i++) result.add(at((double) i / count));
            return List.copyOf(result);
        }
    }

    public enum Part {
        OBJECT,
        POINT,
        START,
        END,
        CENTER,
        CONTROL1,
        CONTROL2,
        MAJOR,
        MINOR,
        CONTROL,
        FIT
    }

    public record Ref(String entityId, Part part, int index) {
        public Ref(String entityId, Part part) {
            this(entityId, part, 0);
        }

        public Ref {
            CadDocument.identifier(entityId, "sketch reference ID");
            java.util.Objects.requireNonNull(part);
            if (index < 0 || index > 31 || (part != Part.CONTROL && part != Part.FIT && index != 0))
                throw new IllegalArgumentException("Invalid reference index");
        }

        @Override
        public String toString() {
            return entityId
                    + (part == Part.OBJECT
                            ? ""
                            : "."
                                    + part.name().toLowerCase(java.util.Locale.ROOT)
                                    + (part == Part.CONTROL || part == Part.FIT ? "[" + index + "]" : ""));
        }

        public static Ref parse(String value) {
            int dot = value.lastIndexOf('.');
            if (dot > 0) {
                try {
                    String suffix = value.substring(dot + 1);
                    int bracket = suffix.indexOf('[');
                    if (bracket > 0 && suffix.endsWith("]"))
                        return new Ref(
                                value.substring(0, dot),
                                Part.valueOf(suffix.substring(0, bracket).toUpperCase(java.util.Locale.ROOT)),
                                Integer.parseInt(suffix.substring(bracket + 1, suffix.length() - 1)));
                    return new Ref(
                            value.substring(0, dot),
                            Part.valueOf(value.substring(dot + 1).toUpperCase(java.util.Locale.ROOT)));
                } catch (IllegalArgumentException ignored) {
                    /* A dotted entity ID is also valid. */
                }
            }
            return new Ref(value, Part.OBJECT);
        }
    }

    public enum Type {
        HORIZONTAL("Horizontal"),
        VERTICAL("Vertical"),
        COINCIDENT("Coincident"),
        TANGENT("Tangent"),
        EQUAL("Equal"),
        PARALLEL("Parallel"),
        PERPENDICULAR("Perpendicular"),
        FIX("Fix"),
        MIDPOINT("Midpoint"),
        CONCENTRIC("Concentric"),
        COLLINEAR("Collinear"),
        SYMMETRY("Symmetry"),
        CURVATURE("Curvature (G2)"),
        POLYGON("Regular polygon"),
        LENGTH("Length"),
        DISTANCE("Distance"),
        DISTANCE_X("Horizontal distance"),
        DISTANCE_Y("Vertical distance"),
        RADIUS("Radius"),
        DIAMETER("Diameter"),
        ANGLE("Angle"),
        MAJOR_RADIUS("Ellipse first radius"),
        MINOR_RADIUS("Ellipse second radius"),
        RHO("Conic rho"),
        TEXT_HEIGHT("Text height"),
        TEXT_SPACING("Text spacing (em)");
        private final String label;

        Type(String label) {
            this.label = label;
        }

        public boolean dimension() {
            return ordinal() >= LENGTH.ordinal();
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** Values store driving dimensions, a fixed pose, or a chosen tangency/polygon branch. */
    public record Constraint(String id, Type type, List<Ref> refs, List<Double> values) {
        public Constraint {
            CadDocument.identifier(id, "constraint ID");
            java.util.Objects.requireNonNull(type);
            refs = List.copyOf(refs);
            values = List.copyOf(values);
            for (double value : values)
                if (!Double.isFinite(value))
                    throw new IllegalArgumentException("Constraint " + id + ": values must be finite");
        }

        public Constraint withValue(double value) {
            return new Constraint(id, type, refs, List.of(value));
        }
    }

    public record Vec(double x, double y) {
        public Vec plus(Vec v) {
            return new Vec(x + v.x, y + v.y);
        }

        public Vec minus(Vec v) {
            return new Vec(x - v.x, y - v.y);
        }

        public Vec times(double k) {
            return new Vec(x * k, y * k);
        }

        public Vec mix(Vec v, double t) {
            return times(1 - t).plus(v.times(t));
        }

        public double dot(Vec v) {
            return x * v.x + y * v.y;
        }

        public double cross(Vec v) {
            return x * v.y - y * v.x;
        }

        public double length() {
            return Math.hypot(x, y);
        }

        public double distance(Vec v) {
            return minus(v).length();
        }

        public Vec unit() {
            return times(1 / Math.max(length(), 1e-12));
        }

        public Vec normal() {
            return new Vec(-y, x);
        }
    }

    public Entity entity(String id) {
        return entities.stream()
                .filter(e -> e.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Missing sketch entity: " + id));
    }

    public List<Entity> curves(Entity entity) {
        return entity.kind() == Kind.TEXT ? SketchText.curves(this, entity) : List.of(entity);
    }

    public Vec point(Ref ref) {
        return ref.equals(ORIGIN) ? new Vec(0, 0) : entity(ref.entityId()).point(ref);
    }

    public SketchGeometry withEntities(List<Entity> next) {
        return new SketchGeometry(next, constraints, boundary);
    }

    public SketchGeometry withConstraints(List<Constraint> next) {
        return new SketchGeometry(entities, next, boundary);
    }

    public SketchGeometry withBoundary(List<String> next) {
        return new SketchGeometry(entities, constraints, next);
    }

    public SketchGeometry replace(Entity next) {
        return withEntities(
                entities.stream().map(e -> e.id().equals(next.id()) ? next : e).toList());
    }

    public SketchGeometry add(Entity next) {
        var list = new ArrayList<>(entities);
        list.add(next);
        return withEntities(list);
    }

    public SketchGeometry add(Constraint next) {
        var list = new ArrayList<>(constraints);
        list.add(next);
        return withConstraints(list);
    }
}
