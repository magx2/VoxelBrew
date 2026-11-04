package dev.voxelbrew.engine.mesh;

import java.util.HashMap;
import java.util.Map;

/** Closed-solid preflight, welding coincident face-boundary vertices within a 1e-7 mm grid. */
public final class MeshValidation {
    public static final double WELD_MM = 1e-7;

    private MeshValidation() {}

    private record Point(long x, long y, long z) {}

    private record Edge(int low, int high) {}

    /** Share face-boundary vertices for indexed formats such as 3MF. Face mappings are retained. */
    public static MeshData weld(MeshData mesh) {
        Map<Point, Integer> unique = new HashMap<>();
        var coordinates = new java.util.ArrayList<Double>();
        int[] remap = new int[mesh.vertexCount()];
        for (int v = 0; v < remap.length; v++) {
            var point =
                    new Point(
                            quantize(mesh.coordinate(v, 0)),
                            quantize(mesh.coordinate(v, 1)),
                            quantize(mesh.coordinate(v, 2)));
            Integer index = unique.get(point);
            if (index == null) {
                index = unique.size();
                unique.put(point, index);
                for (int axis = 0; axis < 3; axis++) coordinates.add(mesh.coordinate(v, axis));
            }
            remap[v] = index;
        }
        int[] triangles = mesh.triangles();
        for (int i = 0; i < triangles.length; i++) triangles[i] = remap[triangles[i]];
        var result =
                new MeshData(
                        coordinates.stream().mapToDouble(Double::doubleValue).toArray(),
                        triangles,
                        mesh.faceIndices());
        requireClosed(result);
        return result;
    }

    public static void requireClosed(MeshData mesh) {
        Map<Point, Integer> points = new HashMap<>();
        int[] welded = new int[mesh.vertexCount()];
        for (int v = 0; v < welded.length; v++) {
            var p =
                    new Point(
                            quantize(mesh.coordinate(v, 0)),
                            quantize(mesh.coordinate(v, 1)),
                            quantize(mesh.coordinate(v, 2)));
            welded[v] = points.computeIfAbsent(p, ignored -> points.size());
        }
        Map<Edge, int[]> edges = new HashMap<>();
        for (int t = 0; t < mesh.triangleCount(); t++) {
            int a = welded[mesh.vertexIndex(t, 0)],
                    b = welded[mesh.vertexIndex(t, 1)],
                    c = welded[mesh.vertexIndex(t, 2)];
            if (a == b || b == c || c == a)
                throw new IllegalArgumentException("Degenerate mesh triangle " + t);
            double[] normal = normal(mesh, t);
            if (Math.hypot(Math.hypot(normal[0], normal[1]), normal[2]) < 1e-15)
                throw new IllegalArgumentException("Zero-area mesh triangle " + t);
            edge(edges, a, b);
            edge(edges, b, c);
            edge(edges, c, a);
        }
        long invalid = edges.values().stream().filter(v -> v[0] != 2 || v[1] != 0).count();
        if (invalid != 0)
            throw new IllegalArgumentException(
                    "Mesh is not closed and consistently oriented: " + invalid + " invalid edges");
    }

    private static long quantize(double value) {
        if (Math.abs(value / WELD_MM) >= Long.MAX_VALUE)
            throw new IllegalArgumentException("Coordinate exceeds mesh welding range");
        return Math.round(value / WELD_MM);
    }

    private static void edge(Map<Edge, int[]> edges, int a, int b) {
        var counts =
                edges.computeIfAbsent(
                        new Edge(Math.min(a, b), Math.max(a, b)), ignored -> new int[2]);
        counts[0]++;
        counts[1] += a < b ? 1 : -1;
    }

    public static double[] normal(MeshData mesh, int triangle) {
        int a = mesh.vertexIndex(triangle, 0),
                b = mesh.vertexIndex(triangle, 1),
                c = mesh.vertexIndex(triangle, 2);
        double ux = mesh.coordinate(b, 0) - mesh.coordinate(a, 0),
                uy = mesh.coordinate(b, 1) - mesh.coordinate(a, 1),
                uz = mesh.coordinate(b, 2) - mesh.coordinate(a, 2);
        double vx = mesh.coordinate(c, 0) - mesh.coordinate(a, 0),
                vy = mesh.coordinate(c, 1) - mesh.coordinate(a, 1),
                vz = mesh.coordinate(c, 2) - mesh.coordinate(a, 2);
        return new double[] {uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx};
    }
}
