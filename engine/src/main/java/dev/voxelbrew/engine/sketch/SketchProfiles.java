package dev.voxelbrew.engine.sketch;

import static dev.voxelbrew.engine.sketch.SketchGeometry.*;

import dev.voxelbrew.engine.geometry.PlanarProfile;
import java.util.*;

/**
 * Resolves selected curves into exact regions; screen samples only classify nesting/orientation.
 */
public final class SketchProfiles {
    private SketchProfiles() {}

    public record Region(String id, PlanarProfile profile) {}

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
        var regions = regions(g);
        if (regions.size() != 1)
            throw new IllegalArgumentException(
                    "Profile contains separate regions; choose one region or extrude them as separate bodies");
        return regions.getFirst().profile();
    }

    public static List<Region> regions(SketchGeometry g) {
        SketchConstraints.requireSatisfied(g);
        if (g.boundary().isEmpty())
            throw new IllegalArgumentException("Select closed profile geometry in Edit sketch before extruding");
        var remaining = new ArrayList<>(g.boundary().stream()
                .map(g::entity)
                .flatMap(e -> g.curves(e).stream())
                .toList());
        if (remaining.isEmpty()) throw new IllegalArgumentException("Profile has no curves");
        var loops = new ArrayList<List<Edge>>();
        while (!remaining.isEmpty()) {
            var first = remaining.removeFirst();
            var path = new ArrayList<Edge>();
            path.add(new Edge(first, false));
            if (!first.loop())
                while (path.getLast().end().distance(path.getFirst().start()) > TOLERANCE) {
                    var endpoint = path.getLast().end();
                    var candidates = remaining.stream()
                            .filter(e -> !e.loop()
                                    && (e.at(0).distance(endpoint) <= TOLERANCE
                                            || e.at(1).distance(endpoint) <= TOLERANCE))
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
                    path.add(new Edge(
                            next, next.at(1).distance(endpoint) < next.at(0).distance(endpoint)));
                }
            loops.add(path);
        }
        var polygons = loops.stream().map(SketchProfiles::samples).toList();
        var areas = polygons.stream().mapToDouble(SketchProfiles::area).toArray();
        int[] parent = new int[loops.size()];
        Arrays.fill(parent, -1);
        for (int i = 0; i < loops.size(); i++) {
            if (!Double.isFinite(areas[i]) || Math.abs(areas[i]) < 1e-12)
                throw new IllegalArgumentException("Profile has zero or invalid area");
            double size = Double.POSITIVE_INFINITY;
            for (int j = 0; j < loops.size(); j++)
                if (Math.abs(areas[j]) > Math.abs(areas[i])
                        && Math.abs(areas[j]) < size
                        && inside(loops.get(i).getFirst().start(), polygons.get(j))) {
                    parent[i] = j;
                    size = Math.abs(areas[j]);
                }
        }
        var result = new ArrayList<Region>();
        for (int i = 0; i < loops.size(); i++) {
            int depth = 0;
            for (int p = parent[i]; p >= 0; p = parent[p]) depth++;
            if (depth % 2 != 0) continue;
            var wires = new ArrayList<List<PlanarProfile.Segment>>();
            wires.add(segments(loops.get(i), areas[i] < 0));
            for (int j = 0; j < loops.size(); j++) if (parent[j] == i) wires.add(segments(loops.get(j), areas[j] > 0));
            result.add(new Region(loops.get(i).getFirst().entity().id(), new PlanarProfile(wires)));
        }
        return List.copyOf(result);
    }

    private static List<PlanarProfile.Segment> segments(List<Edge> loop, boolean reverse) {
        var edges = new ArrayList<>(loop);
        if (reverse) {
            Collections.reverse(edges);
            for (int i = 0; i < edges.size(); i++) edges.set(i, edges.get(i).reverse());
        }
        return edges.stream()
                .flatMap(e -> SketchCurves.segments(e.entity(), e.reversed()).stream())
                .toList();
    }

    public static boolean hasProfile(SketchGeometry g) {
        try {
            return !regions(g).isEmpty();
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
        for (int i = 0; i < polygon.size(); i++) sum += polygon.get(i).cross(polygon.get((i + 1) % polygon.size()));
        return sum / 2;
    }

    private static boolean inside(Vec p, List<Vec> polygon) {
        boolean result = false;
        for (int i = 0, j = polygon.size() - 1; i < polygon.size(); j = i++) {
            var a = polygon.get(i);
            var b = polygon.get(j);
            if ((a.y() > p.y()) != (b.y() > p.y())
                    && p.x() < (b.x() - a.x()) * (p.y() - a.y()) / (b.y() - a.y()) + a.x()) result = !result;
        }
        return result;
    }
}
