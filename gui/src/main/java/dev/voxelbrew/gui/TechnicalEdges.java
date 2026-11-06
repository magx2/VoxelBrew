package dev.voxelbrew.gui;

import dev.voxelbrew.engine.mesh.MeshData;
import java.util.*;

/** Detached adjacency built on the rebuild worker, never from native handles on the UI thread. */
final class TechnicalEdges {
    record Point(double x, double y, double z) {
        Point subtract(Point other) {
            return new Point(x - other.x, y - other.y, z - other.z);
        }

        Point cross(Point other) {
            return new Point(y * other.z - z * other.y, z * other.x - x * other.z, x * other.y - y * other.x);
        }

        double dot(Point other) {
            return x * other.x + y * other.y + z * other.z;
        }
    }

    record Edge(Point a, Point b, Point firstNormal, Point secondNormal, boolean boundary) {
        boolean visibleFrom(Point eye) {
            if (boundary) return true;
            var sight = eye.subtract(a);
            double first = firstNormal.dot(sight), second = secondNormal.dot(sight);
            return (first > 0 && second <= 0) || (second > 0 && first <= 0);
        }
    }

    private record Vertex(long x, long y, long z) {}

    private record Pair(int a, int b) {}

    private static final class Adjacent {
        final Point a, b, first;
        final int face;
        Point second;
        boolean boundary;

        Adjacent(Point a, Point b, Point normal, int face) {
            this.a = a;
            this.b = b;
            this.first = normal;
            this.face = face;
        }
    }

    private final List<Edge> edges;

    private TechnicalEdges(List<Edge> edges) {
        this.edges = List.copyOf(edges);
    }

    List<Edge> edges() {
        return edges;
    }

    static TechnicalEdges from(MeshData mesh) {
        // OCCT duplicates shared vertices between faces. Weld only for display adjacency;
        // retain the original double coordinates and never change the model/export mesh.
        var vertices = new HashMap<Vertex, Integer>();
        var points = new ArrayList<Point>();
        var indices = new int[mesh.vertexCount()];
        for (int v = 0; v < indices.length; v++) {
            var point = new Point(mesh.coordinate(v, 0), mesh.coordinate(v, 1), mesh.coordinate(v, 2));
            var key = new Vertex(Math.round(point.x / 1e-7), Math.round(point.y / 1e-7), Math.round(point.z / 1e-7));
            var existing = vertices.get(key);
            if (existing == null) {
                existing = points.size();
                vertices.put(key, existing);
                points.add(point);
            }
            indices[v] = existing;
        }
        var adjacent = new LinkedHashMap<Pair, Adjacent>();
        for (int t = 0; t < mesh.triangleCount(); t++) {
            int a = indices[mesh.vertexIndex(t, 0)],
                    b = indices[mesh.vertexIndex(t, 1)],
                    c = indices[mesh.vertexIndex(t, 2)];
            var normal =
                    points.get(b).subtract(points.get(a)).cross(points.get(c).subtract(points.get(a)));
            if (normal.dot(normal) == 0) continue;
            int[] corners = {a, b, c};
            for (int k = 0; k < 3; k++) {
                int start = corners[k], end = corners[(k + 1) % 3];
                var pair = new Pair(Math.min(start, end), Math.max(start, end));
                var old = adjacent.get(pair);
                if (old == null)
                    adjacent.put(pair, new Adjacent(points.get(start), points.get(end), normal, mesh.faceIndex(t)));
                else {
                    old.boundary |= old.face != mesh.faceIndex(t) || old.second != null;
                    old.second = normal;
                }
            }
        }
        return new TechnicalEdges(adjacent.values().stream()
                .map(edge -> new Edge(edge.a, edge.b, edge.first, edge.second, edge.boundary || edge.second == null))
                .toList());
    }
}
