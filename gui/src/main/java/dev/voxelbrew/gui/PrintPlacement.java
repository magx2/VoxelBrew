package dev.voxelbrew.gui;

import dev.voxelbrew.engine.model.BodyInstance;
import dev.voxelbrew.engine.model.Transform;

/** Screen projection and hit testing for transient plate placement. */
final class PrintPlacement {
    private PrintPlacement() {}

    static BodyInstance move(BodyInstance source, double dx, double dy, boolean isometric, boolean snap) {
        double x = isometric ? dy + dx / 2 : dx;
        double y = isometric ? dy - dx / 2 : dy;
        if (snap) {
            x = Math.rint(x);
            y = Math.rint(y);
        }
        return new BodyInstance(
                source.id(), source.bodyId(), Transform.translation(x, y, 0).compose(source.transform()));
    }

    static dev.voxelbrew.engine.printing.PrintSetup reorder(
            dev.voxelbrew.engine.printing.PrintSetup setup, String source, String target) {
        var plates = new java.util.ArrayList<>(setup.plates());
        var moving =
                plates.stream().filter(p -> p.id().equals(source)).findFirst().orElseThrow();
        int destination = java.util.stream.IntStream.range(0, plates.size())
                .filter(i -> plates.get(i).id().equals(target))
                .findFirst()
                .orElseThrow();
        plates.remove(moving);
        plates.add(destination, moving);
        return new dev.voxelbrew.engine.printing.PrintSetup(
                setup.id(),
                setup.version(),
                setup.backendId(),
                setup.printerId(),
                setup.materialId(),
                setup.process(),
                plates,
                setup.activePlateId(),
                setup.printerSnapshot(),
                setup.workspaceSettings());
    }

    /** Rank feasible planar bed faces by unsupported area, bed contact, then height. */
    static Transform autoOrient(dev.voxelbrew.engine.mesh.MeshData mesh, Transform current) {
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

    static boolean contains(double px, double py, double[] x, double[] y) {
        double area = (x[1] - x[0]) * (y[2] - y[0]) - (y[1] - y[0]) * (x[2] - x[0]);
        if (Math.abs(area) < 1e-9) return false;
        double a = ((x[1] - px) * (y[2] - py) - (y[1] - py) * (x[2] - px)) / area;
        double b = ((x[2] - px) * (y[0] - py) - (y[2] - py) * (x[0] - px)) / area;
        return a >= 0 && b >= 0 && a + b <= 1;
    }
}
