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
        return regions(g, false);
    }

    /** Individual bounded faces, including faces separated by a shared sketch edge. */
    public static List<Region> selectableRegions(SketchGeometry g) {
        return regions(g, true);
    }

    public static String regionProfileId(String profileId, Region region) {
        return profileId + "/region/" + region.id();
    }

    public static List<Region> referencedRegions(dev.voxelbrew.engine.model.Feature.Sketch sketch, String profileId) {
        var geometry = ((dev.voxelbrew.engine.model.Feature.Constrained) sketch.profile()).geometry();
        if (sketch.profileId().equals(profileId)) return regions(geometry);
        return List.of(selectableRegions(geometry).stream()
                .filter(region -> regionProfileId(sketch.profileId(), region).equals(profileId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Selected sketch region no longer exists")));
    }

    private static List<Region> regions(SketchGeometry g, boolean individual) {
        SketchConstraints.requireSatisfied(g);
        if (g.boundary().isEmpty())
            throw new IllegalArgumentException("Select closed profile geometry in Edit sketch before extruding");
        var remaining = new ArrayList<>(g.boundary().stream()
                .map(g::entity)
                .flatMap(e -> g.curves(e).stream())
                .toList());
        if (remaining.isEmpty()) throw new IllegalArgumentException("Profile has no curves");
        var loops = boundaryLoops(remaining, individual);
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
            String id = individual
                    ? regionId(loops.get(i))
                    : loops.get(i).getFirst().entity().id();
            result.add(new Region(id, new PlanarProfile(wires)));
        }
        return List.copyOf(result);
    }

    /** Walk faces on the left; clockwise walks bound the exterior of connected regions. */
    private static List<List<Edge>> boundaryLoops(List<Entity> entities, boolean individual) {
        var loops = new ArrayList<List<Edge>>();
        var directed = new ArrayList<Edge>();
        for (var entity : entities) {
            if (entity.loop()) loops.add(List.of(new Edge(entity, false)));
            else {
                directed.add(new Edge(entity, false));
                directed.add(new Edge(entity, true));
            }
        }
        var next = new HashMap<Edge, Edge>();
        for (var edge : directed) {
            var outgoing = directed.stream()
                    .filter(e -> e.start().distance(edge.end()) <= TOLERANCE)
                    .sorted(Comparator.comparingDouble(SketchProfiles::angle))
                    .toList();
            if (outgoing.size() < 2)
                throw new IllegalArgumentException(
                        "Open profile at " + edge.entity().id() + "; add Coincident constraints to close it");
            int reverse = outgoing.indexOf(edge.reverse());
            next.put(edge, outgoing.get((reverse + outgoing.size() - 1) % outgoing.size()));
        }
        var visited = new HashSet<Edge>();
        for (var start : directed) {
            if (visited.contains(start)) continue;
            var path = new ArrayList<Edge>();
            var edge = start;
            do {
                if (!visited.add(edge))
                    throw new IllegalArgumentException(
                            "Ambiguous profile boundary at " + edge.entity().id());
                path.add(edge);
                edge = next.get(edge);
            } while (!edge.equals(start));
            double signedArea = area(samples(path));
            if (Math.abs(signedArea) < 1e-12) throw new IllegalArgumentException("Profile has zero or invalid area");
            if (signedArea < 0 && path.stream().map(Edge::entity).distinct().count() != path.size())
                throw new IllegalArgumentException("Profile contains an open connecting edge");
            if (individual ? signedArea > 0 : signedArea < 0) {
                if (path.stream().map(Edge::entity).distinct().count() != path.size())
                    throw new IllegalArgumentException("Profile contains an open connecting edge");
                loops.add(path);
            }
        }
        loops.sort(
                Comparator.comparingInt(loop -> entities.indexOf(loop.getFirst().entity())));
        return loops;
    }

    private static String regionId(List<Edge> loop) {
        // Length-prefix IDs so shared edges, entity ordering and direction cannot alias faces.
        String key = loop.stream()
                .map(e -> e.entity().id())
                .sorted()
                .map(id -> id.length() + ":" + id)
                .collect(java.util.stream.Collectors.joining());
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static double angle(Edge edge) {
        // Only ordering uses a near-endpoint sample; output retains the exact curve.
        var start = edge.start();
        var nearby = edge.entity().at(edge.reversed() ? 1 - 1e-5 : 1e-5);
        return Math.atan2(nearby.y() - start.y(), nearby.x() - start.x());
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
