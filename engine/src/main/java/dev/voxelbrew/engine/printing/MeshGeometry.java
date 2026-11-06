package dev.voxelbrew.engine.printing;

import dev.voxelbrew.engine.geometry.Bounds;
import dev.voxelbrew.engine.mesh.MeshData;
import dev.voxelbrew.engine.mesh.MeshValidation;
import dev.voxelbrew.engine.model.Transform;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.util.List;

public final class MeshGeometry {
    private MeshGeometry() {}

    public static MeshData transform(MeshData mesh, Transform placement) {
        double[] vertices = mesh.vertices();
        for (int i = 0; i < vertices.length; i += 3) {
            var p = placement.apply(vertices[i], vertices[i + 1], vertices[i + 2]);
            System.arraycopy(p, 0, vertices, i, 3);
        }
        return new MeshData(vertices, mesh.triangles(), mesh.faceIndices());
    }

    public static Bounds bounds(MeshData mesh) {
        double[] min = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
        double[] max = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (int v = 0; v < mesh.vertexCount(); v++)
            for (int axis = 0; axis < 3; axis++) {
                min[axis] = Math.min(min[axis], mesh.coordinate(v, axis));
                max[axis] = Math.max(max[axis], mesh.coordinate(v, axis));
            }
        return new Bounds(min[0], min[1], min[2], max[0], max[1], max[2]);
    }

    public static MeshData box(SupportVolume box) {
        double x = box.x(), y = box.y(), z = box.z(), a = x + box.width(), b = y + box.depth(), c = z + box.height();
        return new MeshData(
                new double[] {x, y, z, a, y, z, a, b, z, x, b, z, x, y, c, a, y, c, a, b, c, x, b, c},
                new int[] {
                    0, 2, 1, 0, 3, 2, 4, 5, 6, 4, 6, 7, 0, 1, 5, 0, 5, 4, 1, 2, 6, 1, 6, 5, 2, 3, 7, 2, 7, 6, 3, 0, 4,
                    3, 4, 7
                },
                new int[] {0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5});
    }

    /** Orient a planar evaluated face downward, retaining XY center and touching bed Z=0. */
    public static Transform layOnFace(MeshData mesh, int face, Transform current) {
        var world = transform(mesh, current);
        double[] n = null;
        double plane = 0;
        for (int t = 0; t < world.triangleCount(); t++)
            if (world.faceIndex(t) == face) {
                double[] next = MeshValidation.normal(world, t);
                double len = Math.hypot(Math.hypot(next[0], next[1]), next[2]);
                if (len < 1e-12) continue;
                for (int a = 0; a < 3; a++) next[a] /= len;
                if (n == null) {
                    n = next;
                    int v = world.vertexIndex(t, 0);
                    plane = n[0] * world.coordinate(v, 0)
                            + n[1] * world.coordinate(v, 1)
                            + n[2] * world.coordinate(v, 2);
                } else if (n[0] * next[0] + n[1] * next[1] + n[2] * next[2] < 1 - 1e-8)
                    throw new IllegalArgumentException("Selected face is not planar");
                for (int corner = 0; corner < 3; corner++) {
                    int v = world.vertexIndex(t, corner);
                    double d = n[0] * world.coordinate(v, 0)
                            + n[1] * world.coordinate(v, 1)
                            + n[2] * world.coordinate(v, 2);
                    if (Math.abs(d - plane) > 0.001) throw new IllegalArgumentException("Selected face is not planar");
                }
            }
        if (n == null) throw new IllegalArgumentException("Face is unavailable in this revision: " + face);
        double cos = -n[2], vx = -n[1], vy = n[0], vz = 0;
        Transform rotation;
        if (cos < -1 + 1e-10) rotation = Transform.rotation(180, 0, 0);
        else {
            double[][] k = {{0, -vz, vy}, {vz, 0, -vx}, {-vy, vx, 0}};
            double[] r = new double[12];
            for (int row = 0; row < 3; row++)
                for (int col = 0; col < 3; col++) {
                    r[4 * row + col] = (row == col ? 1 : 0) + k[row][col];
                    for (int i = 0; i < 3; i++) r[4 * row + col] += k[row][i] * k[i][col] / (1 + cos);
                }
            rotation = new Transform(r);
        }
        var placed = rotation.compose(current);
        var before = bounds(world);
        var after = bounds(transform(mesh, placed));
        if (Math.abs(-plane - after.minZ()) > 0.001)
            throw new IllegalArgumentException("Selected face cannot lie on the bed without putting material below it");
        return Transform.translation(
                        (before.minX() + before.maxX() - after.minX() - after.maxX()) / 2,
                        (before.minY() + before.maxY() - after.minY() - after.maxY()) / 2,
                        -after.minZ())
                .compose(placed);
    }

    static Area polygon(List<PrintProfile.Point> points) {
        var path = new Path2D.Double();
        path.moveTo(points.getFirst().x(), points.getFirst().y());
        for (var point : points) path.lineTo(point.x(), point.y());
        path.closePath();
        return new Area(path);
    }

    static Area footprint(MeshData mesh) {
        // All projected triangles wind the same way, so nonzero winding gives their union,
        // including overlaps, without repeatedly reprocessing the growing boundary.
        var path = new Path2D.Double(Path2D.WIND_NON_ZERO);
        for (int t = 0; t < mesh.triangleCount(); t++) {
            int a = mesh.vertexIndex(t, 0), b = mesh.vertexIndex(t, 1), c = mesh.vertexIndex(t, 2);
            double ax = mesh.coordinate(a, 0), ay = mesh.coordinate(a, 1);
            double bx = mesh.coordinate(b, 0), by = mesh.coordinate(b, 1);
            double cx = mesh.coordinate(c, 0), cy = mesh.coordinate(c, 1);
            double cross = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
            if (cross == 0) continue;
            path.moveTo(ax, ay);
            path.lineTo(cross > 0 ? bx : cx, cross > 0 ? by : cy);
            path.lineTo(cross > 0 ? cx : bx, cross > 0 ? cy : by);
            path.closePath();
        }
        return new Area(path);
    }
}
