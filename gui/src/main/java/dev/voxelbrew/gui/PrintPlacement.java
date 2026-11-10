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

    static boolean contains(double px, double py, double[] x, double[] y) {
        double area = (x[1] - x[0]) * (y[2] - y[0]) - (y[1] - y[0]) * (x[2] - x[0]);
        if (Math.abs(area) < 1e-9) return false;
        double a = ((x[1] - px) * (y[2] - py) - (y[1] - py) * (x[2] - px)) / area;
        double b = ((x[2] - px) * (y[0] - py) - (y[2] - py) * (x[0] - px)) / area;
        return a >= 0 && b >= 0 && a + b <= 1;
    }
}
