package dev.voxelbrew.engine.printing;

import dev.voxelbrew.engine.model.Transform;

/** Shared automatic bed orientation for desktop and headless preparation. */
public final class PrintOrientation {
    private PrintOrientation() {}

    /** Rank feasible planar bed faces by unsupported area, bed contact, then height. */
    public static Transform autoOrient(dev.voxelbrew.engine.mesh.MeshData mesh, Transform current) {
        Transform best = null;
        double bestOverhang = Double.POSITIVE_INFINITY, bestContact = -1, bestHeight = Double.POSITIVE_INFINITY;
        for (int face :
                java.util.Arrays.stream(mesh.faceIndices()).distinct().sorted().toArray()) {
            Transform candidate;
            try {
                candidate = dev.voxelbrew.engine.printing.MeshGeometry.layOnFace(mesh, face, current);
            } catch (IllegalArgumentException unsuitable) {
                continue;
            }
            var placed = dev.voxelbrew.engine.printing.MeshGeometry.transform(mesh, candidate);
            double overhang = 0, contact = 0;
            for (int t = 0; t < placed.triangleCount(); t++) {
                double[] n = dev.voxelbrew.engine.mesh.MeshValidation.normal(placed, t);
                double area = Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]) / 2;
                boolean onBed = true;
                for (int c = 0; c < 3; c++) onBed &= Math.abs(placed.coordinate(placed.vertexIndex(t, c), 2)) < 0.001;
                if (onBed) contact += area;
                else if (n[2] < -2 * area * Math.cos(Math.PI / 4)) overhang += area;
            }
            double height =
                    dev.voxelbrew.engine.printing.MeshGeometry.bounds(placed).maxZ();
            if (overhang < bestOverhang - 1e-6
                    || Math.abs(overhang - bestOverhang) < 1e-6
                            && (contact > bestContact + 1e-6
                                    || Math.abs(contact - bestContact) < 1e-6 && height < bestHeight)) {
                best = candidate;
                bestOverhang = overhang;
                bestContact = contact;
                bestHeight = height;
            }
        }
        if (best == null) throw new IllegalArgumentException("No planar face can support this object on the bed");
        return best;
    }
    /** Rotate about the placed center in world/local axes, preserving bed clearance. */
    public static dev.voxelbrew.engine.model.BodyInstance rotate(
            dev.voxelbrew.engine.model.BodyInstance source,
            dev.voxelbrew.engine.mesh.MeshData mesh,
            int axis,
            double degrees,
            boolean local) {
        if (axis < 0 || axis > 2 || !Double.isFinite(degrees)) throw new IllegalArgumentException("Invalid rotation");
        if (Math.abs(degrees) < 1e-9) return source;
        var before = MeshGeometry.bounds(MeshGeometry.transform(mesh, source.transform()));
        double x = (before.minX() + before.maxX()) / 2,
                y = (before.minY() + before.maxY()) / 2,
                z = (before.minZ() + before.maxZ()) / 2;
        var rotation = Transform.rotation(axis == 0 ? degrees : 0, axis == 1 ? degrees : 0, axis == 2 ? degrees : 0);
        var matrix = source.transform().matrix();
        matrix[3] = matrix[7] = matrix[11] = 0;
        var frame = local ? new Transform(matrix) : Transform.IDENTITY;
        rotation = frame.compose(rotation).compose(frame.inverse());
        var placed = Transform.translation(x, y, z)
                .compose(rotation)
                .compose(Transform.translation(-x, -y, -z))
                .compose(source.transform());
        var after = MeshGeometry.bounds(MeshGeometry.transform(mesh, placed));
        return new dev.voxelbrew.engine.model.BodyInstance(
                source.id(),
                source.bodyId(),
                Transform.translation(0, 0, before.minZ() - after.minZ()).compose(placed));
    }
}
