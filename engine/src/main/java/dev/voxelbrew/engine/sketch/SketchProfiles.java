package dev.voxelbrew.engine.sketch;

import static dev.voxelbrew.engine.sketch.SketchGeometry.*;

import dev.voxelbrew.engine.geometry.PlanarProfile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Resolves selected boundary IDs to closed, oriented wires without replacing exact curves. */
public final class SketchProfiles {
    private SketchProfiles() {}

    private record Edge(Entity entity, boolean reversed) {
        Vec start() {
            return entity.at(reversed ? 1 : 0);
        }

        Vec end() {
            return entity.at(reversed ? 0 : 1);
        }

        Edge reverse() {
            return new Edge(entity, !reversed);
        }
    }

    public static PlanarProfile profile(SketchGeometry g) {
        SketchConstraints.requireSatisfied(g);
        if (g.boundary().isEmpty())
            throw new IllegalArgumentException(
                    "Select closed profile geometry in Edit sketch before extruding");
        var remaining = new ArrayList<>(g.boundary().stream().map(g::entity).toList());
        var loops = new ArrayList<List<Edge>>();
        while (!remaining.isEmpty()) {
            var first = remaining.removeFirst();
            var path = new ArrayList<Edge>();
            path.add(new Edge(first, false));
            if (first.kind() != Kind.CIRCLE) {
                while (path.getLast().end().distance(path.getFirst().start()) > TOLERANCE) {
                    var endpoint = path.getLast().end();
                    var candidates =
                            remaining.stream()
                                    .filter(
                                            e ->
                                                    e.kind() != Kind.CIRCLE
                                                            && (e.at(0).distance(endpoint)
                                                                            <= TOLERANCE
                                                                    || e.at(1).distance(endpoint)
                                                                            <= TOLERANCE))
                                    .toList();
                    if (candidates.size() != 1)
                        throw new IllegalArgumentException(
                                candidates.isEmpty()
                                        ? "Open profile at "
                                                + path.getLast().entity().id()
                                                + "; add Coincident constraints to close it"
                                        : "Branching profile at "
                                                + path.getLast().entity().id()
                                                + "; select a single boundary");
                    var next = candidates.getFirst();
                    remaining.remove(next);
                    path.add(
                            new Edge(
                                    next,
                                    next.at(1).distance(endpoint) < next.at(0).distance(endpoint)));
                }
            }
            loops.add(path);
        }
        loops.sort(
                Comparator.comparingDouble((List<Edge> loop) -> Math.abs(area(samples(loop))))
                        .reversed());
        var exterior = samples(loops.getFirst());
        for (int i = 0; i < loops.size(); i++) {
            var loop = loops.get(i);
            double signedArea = area(samples(loop));
            if (Math.abs(signedArea) < 1e-12)
                throw new IllegalArgumentException("Profile has zero area");
            if (i > 0 && !inside(loop.getFirst().start(), exterior))
                throw new IllegalArgumentException(
                        "Profile contains separate regions; select one exterior and its holes");
            for (int j = 1; j < i; j++)
                if (inside(loop.getFirst().start(), samples(loops.get(j))))
                    throw new IllegalArgumentException("Nested islands need separate profiles");
            if ((i == 0 && signedArea < 0) || (i > 0 && signedArea > 0)) {
                Collections.reverse(loop);
                for (int j = 0; j < loop.size(); j++) loop.set(j, loop.get(j).reverse());
            }
        }
        return new PlanarProfile(
                loops.stream()
                        .map(
                                loop ->
                                        loop.stream()
                                                .map(
                                                        edge ->
                                                                new PlanarProfile.Segment(
                                                                        switch (edge.entity()
                                                                                .kind()) {
                                                                            case LINE ->
                                                                                    PlanarProfile
                                                                                            .Kind
                                                                                            .LINE;
                                                                            case CIRCLE ->
                                                                                    PlanarProfile
                                                                                            .Kind
                                                                                            .CIRCLE;
                                                                            case ARC ->
                                                                                    PlanarProfile
                                                                                            .Kind
                                                                                            .ARC;
                                                                            case SPLINE ->
                                                                                    PlanarProfile
                                                                                            .Kind
                                                                                            .BEZIER;
                                                                            case POINT ->
                                                                                    throw new IllegalArgumentException(
                                                                                            "Points cannot form a profile");
                                                                        },
                                                                        edge.entity().values(),
                                                                        edge.reversed()))
                                                .toList())
                        .toList());
    }

    public static boolean hasProfile(SketchGeometry g) {
        try {
            profile(g);
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static List<Vec> samples(List<Edge> loop) {
        var result = new ArrayList<Vec>();
        for (var edge : loop) {
            var points = new ArrayList<>(edge.entity().sample());
            if (edge.reversed()) Collections.reverse(points);
            result.addAll(points);
        }
        return result;
    }

    private static double area(List<Vec> polygon) {
        double sum = 0;
        for (int i = 0; i < polygon.size(); i++)
            sum += polygon.get(i).cross(polygon.get((i + 1) % polygon.size()));
        return sum / 2;
    }

    private static boolean inside(Vec p, List<Vec> polygon) {
        boolean result = false;
        for (int i = 0, j = polygon.size() - 1; i < polygon.size(); j = i++) {
            var a = polygon.get(i);
            var b = polygon.get(j);
            if ((a.y() > p.y()) != (b.y() > p.y())
                    && p.x() < (b.x() - a.x()) * (p.y() - a.y()) / (b.y() - a.y()) + a.x())
                result = !result;
        }
        return result;
    }
}
