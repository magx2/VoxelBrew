package dev.voxelbrew.engine.sketch;

import static dev.voxelbrew.engine.sketch.SketchGeometry.*;

import dev.voxelbrew.engine.model.Feature;
import java.util.ArrayList;
import java.util.List;

/** Shared construction and editing operations; frontends never implement constraint equations. */
public final class Sketches {
    private Sketches() {}

    public static Entity entity(String id, Kind kind, boolean construction, double... values) {
        return new Entity(id, kind, java.util.Arrays.stream(values).boxed().toList(), construction);
    }

    public static SketchGeometry rectangle(
            String prefix, double x, double y, double width, double height, boolean dimensioned) {
        if (width < 1e-6 || height < 1e-6)
            throw new IllegalArgumentException("Rectangle width and height must be positive");
        var edges = List.of(
                entity(prefix + "-bottom", Kind.LINE, false, x, y, x + width, y),
                entity(prefix + "-right", Kind.LINE, false, x + width, y, x + width, y + height),
                entity(prefix + "-top", Kind.LINE, false, x + width, y + height, x, y + height),
                entity(prefix + "-left", Kind.LINE, false, x, y + height, x, y));
        var g = new SketchGeometry(
                edges, List.of(), edges.stream().map(Entity::id).toList());
        for (int i = 0; i < 4; i++) {
            g = g.add(SketchConstraints.create(
                    g,
                    prefix + "-join-" + i,
                    Type.COINCIDENT,
                    List.of(edges.get(i).ref(Part.END), edges.get((i + 1) % 4).ref(Part.START)),
                    0));
            g = g.add(SketchConstraints.create(
                    g,
                    prefix + "-axis-" + i,
                    i % 2 == 0 ? Type.HORIZONTAL : Type.VERTICAL,
                    List.of(edges.get(i).ref()),
                    0));
        }
        if (dimensioned) {
            g = g.add(SketchConstraints.create(
                    g, "width", Type.LENGTH, List.of(edges.get(0).ref()), width));
            g = g.add(SketchConstraints.create(
                    g, "height", Type.LENGTH, List.of(edges.get(1).ref()), height));
            g = g.add(SketchConstraints.create(
                    g, "anchor", Type.FIX, List.of(edges.get(0).ref(Part.START)), 0));
        }
        return g;
    }

    public static SketchGeometry fromProfile(Feature.Profile profile) {
        if (profile instanceof Feature.Rectangle r)
            return rectangle("rectangle", r.x(), r.y(), r.width(), r.height(), true);
        if (profile instanceof Feature.Circle c) {
            var circle = entity("circle", Kind.CIRCLE, false, c.centerX(), c.centerY(), c.diameter() / 2);
            var g = new SketchGeometry(List.of(circle), List.of(), List.of(circle.id()));
            g = g.add(SketchConstraints.create(g, "diameter", Type.DIAMETER, List.of(circle.ref()), c.diameter()));
            return g.add(SketchConstraints.create(g, "anchor", Type.FIX, List.of(circle.ref(Part.CENTER)), 0));
        }
        if (profile instanceof Feature.Constrained c) return c.geometry();
        throw new IllegalArgumentException("Unsupported sketch profile");
    }

    public static SketchGeometry polygon(String prefix, double cx, double cy, double radius, int sides) {
        if (sides < 3 || sides > 32 || radius < 1e-6)
            throw new IllegalArgumentException("A regular polygon needs 3–32 sides and a positive radius");
        var lines = new ArrayList<Entity>();
        for (int i = 0; i < sides; i++) {
            double a = Math.TAU * i / sides, b = Math.TAU * (i + 1) / sides;
            lines.add(entity(
                    prefix + "-" + (i + 1),
                    Kind.LINE,
                    false,
                    cx + radius * Math.cos(a),
                    cy + radius * Math.sin(a),
                    cx + radius * Math.cos(b),
                    cy + radius * Math.sin(b)));
        }
        var g = new SketchGeometry(
                lines, List.of(), lines.stream().map(Entity::id).toList());
        return g.add(SketchConstraints.create(
                g,
                prefix + "-regular",
                Type.POLYGON,
                lines.stream().map(Entity::ref).toList(),
                0));
    }

    public static SketchGeometry removeEntity(SketchGeometry g, String id) {
        g.entity(id);
        return new SketchGeometry(
                g.entities().stream().filter(e -> !e.id().equals(id)).toList(),
                g.constraints().stream()
                        .filter(c ->
                                c.refs().stream().noneMatch(r -> r.entityId().equals(id)))
                        .toList(),
                g.boundary().stream().filter(e -> !e.equals(id)).toList());
    }

    public static SketchGeometry editDimension(SketchGeometry g, String id, double value) {
        var constraint = g.constraints().stream()
                .filter(c -> c.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Missing driving dimension: " + id));
        if (!constraint.type().dimension())
            throw new IllegalArgumentException("Constraint is not a driving dimension: " + id);
        return SketchSolver.solve(g.withConstraints(g.constraints().stream()
                        .map(c -> c.id().equals(id) ? c.withValue(value) : c)
                        .toList()))
                .requireSolved();
    }

    public static SketchSolver.Result reshape(SketchGeometry g, Entity entity) {
        var input = g.replace(entity);
        var target =
                SketchConstraints.create(input, temporaryId(g, "reshape-target"), Type.FIX, List.of(entity.ref()), 0);
        var result = SketchSolver.solve(input.add(target));
        return new SketchSolver.Result(
                result.geometry().withConstraints(g.constraints()),
                result.degreesOfFreedom(),
                result.redundantEquations(),
                result.problems());
    }

    public static SketchSolver.Result drag(SketchGeometry g, Ref point, Vec destination) {
        var target = new Constraint(
                temporaryId(g, "drag-target"), Type.FIX, List.of(point), List.of(destination.x(), destination.y()));
        var result = SketchSolver.solve(g.add(target));
        return new SketchSolver.Result(
                result.geometry().withConstraints(g.constraints()),
                result.degreesOfFreedom(),
                result.redundantEquations(),
                result.problems());
    }

    private static String temporaryId(SketchGeometry g, String prefix) {
        var ids = g.constraints().stream().map(Constraint::id).collect(java.util.stream.Collectors.toSet());
        for (int i = 0; ; i++) if (!ids.contains(prefix + "-" + i)) return prefix + "-" + i;
    }
}
