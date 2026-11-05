package dev.voxelbrew.gui;

import dev.voxelbrew.engine.mesh.MeshData;
import dev.voxelbrew.engine.sketch.SketchGeometry.Vec;
import java.util.List;

/** Detached six-sided strokes: one mesh replaces thousands of JavaFX Cylinder nodes for text. */
final class SketchStrokes {
    private SketchStrokes() {}

    record Segment(Vec a, Vec b) {}

    static MeshData mesh(List<Segment> segments, double z, double radius) {
        if (!Double.isFinite(z) || !Double.isFinite(radius) || radius <= 0)
            throw new IllegalArgumentException("Invalid sketch stroke dimensions");
        var lines = segments.stream().filter(s -> s.a().distance(s.b()) >= 1e-9).toList();
        if (lines.isEmpty()) throw new IllegalArgumentException("No sketch stroke segments");
        var points = new double[lines.size() * 14 * 3];
        var triangles = new int[lines.size() * 24 * 3];
        var faces = new int[lines.size() * 24];
        int triangle = 0;
        for (int index = 0; index < lines.size(); index++) {
            var line = lines.get(index);
            double length = line.a().distance(line.b());
            double ux = -(line.b().y() - line.a().y()) / length;
            double uy = (line.b().x() - line.a().x()) / length;
            int base = index * 14;
            for (int end = 0; end < 2; end++) {
                var center = end == 0 ? line.a() : line.b();
                for (int side = 0; side < 6; side++) {
                    double angle = side * Math.PI / 3;
                    int vertex = 3 * (base + end * 6 + side);
                    points[vertex] = center.x() + radius * ux * Math.cos(angle);
                    points[vertex + 1] = center.y() + radius * uy * Math.cos(angle);
                    points[vertex + 2] = z + radius * Math.sin(angle);
                }
                int cap = 3 * (base + 12 + end);
                points[cap] = center.x();
                points[cap + 1] = center.y();
                points[cap + 2] = z;
            }
            for (int side = 0; side < 6; side++) {
                int next = (side + 1) % 6;
                int[] corners = {
                    side, next, side + 6, next, next + 6, side + 6, 12, next, side, 13, side + 6,
                    next + 6
                };
                for (int corner : corners) {
                    faces[triangle / 3] = index;
                    triangles[triangle++] = base + corner;
                }
            }
        }
        return new MeshData(points, triangles, faces);
    }
}
