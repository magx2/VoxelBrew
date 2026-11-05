package dev.voxelbrew.gui;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.sketch.SketchGeometry.Vec;
import java.util.*;
import org.junit.jupiter.api.Test;

class SketchStrokesTest {
    @Test
    void batchedStrokesKeepClosedOutwardGeometryAtTheSketchElevation() {
        var a = new Vec(1, 2);
        var b = new Vec(5, 6);
        var mesh =
                SketchStrokes.mesh(
                        List.of(
                                new SketchStrokes.Segment(a, b),
                                new SketchStrokes.Segment(b, a),
                                new SketchStrokes.Segment(a, a)),
                        10,
                        .5);
        var edges = new HashMap<String, Integer>();
        double volume = 0;
        for (int t = 0; t < mesh.triangleCount(); t++) {
            var vertices = new double[3][3];
            for (int c = 0; c < 3; c++) {
                int v = mesh.vertexIndex(t, c), next = mesh.vertexIndex(t, (c + 1) % 3);
                edges.merge(Math.min(v, next) + ":" + Math.max(v, next), 1, Integer::sum);
                for (int axis = 0; axis < 3; axis++) vertices[c][axis] = mesh.coordinate(v, axis);
                assertTrue(vertices[c][2] >= 9.5 && vertices[c][2] <= 10.5);
            }
            var p = vertices[0];
            var q = vertices[1];
            var r = vertices[2];
            volume +=
                    (p[0] * (q[1] * r[2] - q[2] * r[1])
                                    + p[1] * (q[2] * r[0] - q[0] * r[2])
                                    + p[2] * (q[0] * r[1] - q[1] * r[0]))
                            / 6;
        }
        assertTrue(edges.values().stream().allMatch(count -> count == 2));
        assertEquals(2 * 3 * Math.sqrt(3) / 2 * .25 * a.distance(b), volume, 1e-9);
        assertThrows(IllegalArgumentException.class, () -> SketchStrokes.mesh(List.of(), 0, 1));
    }
}
