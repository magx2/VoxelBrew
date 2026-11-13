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
                setup.workspaceSettings(),
                setup.supportsDisabled());
    }

    static Transform autoOrient(dev.voxelbrew.engine.mesh.MeshData mesh, Transform current) {
        return dev.voxelbrew.engine.printing.PrintOrientation.autoOrient(mesh, current);
    }

    static boolean contains(double px, double py, double[] x, double[] y) {
        double area = (x[1] - x[0]) * (y[2] - y[0]) - (y[1] - y[0]) * (x[2] - x[0]);
        if (Math.abs(area) < 1e-9) return false;
        double a = ((x[1] - px) * (y[2] - py) - (y[1] - py) * (x[2] - px)) / area;
        double b = ((x[2] - px) * (y[0] - py) - (y[2] - py) * (x[0] - px)) / area;
        return a >= 0 && b >= 0 && a + b <= 1;
    }
}
