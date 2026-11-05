package dev.voxelbrew.engine.printing;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.export.Transform;
import dev.voxelbrew.engine.mesh.MeshData;
import java.awt.geom.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class FootprintTest {
    private static Area triangleUnion(MeshData mesh) {
        var area = new Area();
        for (int t = 0; t < mesh.triangleCount(); t++) {
            var path = new Path2D.Double();
            for (int c = 0; c < 3; c++) {
                int v = mesh.vertexIndex(t, c);
                if (c == 0) path.moveTo(mesh.coordinate(v, 0), mesh.coordinate(v, 1));
                else path.lineTo(mesh.coordinate(v, 0), mesh.coordinate(v, 1));
            }
            path.closePath();
            area.add(new Area(path));
        }
        return area;
    }

    @Test
    void batchedProjectionPreservesHolesOverlapsOppositeWindingsAndDisconnectedRegions() {
        var points = new ArrayList<Double>();
        for (int y = 0; y < 3; y++)
            for (int x = 0; x < 3; x++) {
                if (x == 1 && y == 1) continue;
                for (double value :
                        new double[] {
                            x, y, 0, x + 1, y, 0, x + 1, y + 1, 0, x, y, 0, x, y + 1, 0, x + 1,
                            y + 1, 0
                        }) points.add(value);
            }
        // Opposite duplicate, a disjoint island and an edge-on triangle.
        for (double value :
                new double[] {
                    0, 0, 2, 1, 1, 2, 1, 0, 2, 5, 0, 0, 6, 0, 0, 5, 1, 0, 0, 0, 0, 0, 0, 1, 0, 3, 1
                }) points.add(value);
        double[] vertices = points.stream().mapToDouble(Double::doubleValue).toArray();
        int[] triangles = java.util.stream.IntStream.range(0, vertices.length / 3).toArray();
        var mesh = new MeshData(vertices, triangles, new int[triangles.length / 3]);
        var footprint = MeshGeometry.footprint(mesh);
        assertFalse(footprint.contains(1.5, 1.5));
        assertTrue(footprint.contains(.5, .5));
        assertTrue(footprint.contains(5.2, .2));
        var difference = triangleUnion(mesh);
        difference.exclusiveOr(footprint);
        assertTrue(difference.isEmpty());
        for (var transform :
                List.of(Transform.rotation(15, 30, 38), Transform.translation(-10, 17, 2))) {
            var placed = MeshGeometry.transform(mesh, transform);
            var expected = triangleUnion(placed);
            var actual = MeshGeometry.footprint(placed);
            for (double x = -12.13; x < 9; x += .37)
                for (double y = -.17; y < 22; y += .43)
                    assertEquals(
                            expected.contains(x, y),
                            actual.contains(x, y),
                            "Projected occupancy at " + x + "," + y);
        }
    }
}
