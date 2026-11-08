package dev.voxelbrew.gui;

import static dev.voxelbrew.engine.sketch.SketchGeometry.*;

import dev.voxelbrew.engine.sketch.SketchGeometry;
import java.util.LinkedHashSet;
import java.util.Set;

/** Directional selection against the same sampled curves used by the sketch canvas. */
final class SketchSelection {
    private SketchSelection() {}

    static Set<Ref> inBox(SketchGeometry geometry, Vec start, Vec end, boolean crossing) {
        double minX = Math.min(start.x(), end.x()), maxX = Math.max(start.x(), end.x());
        double minY = Math.min(start.y(), end.y()), maxY = Math.max(start.y(), end.y());
        var result = new LinkedHashSet<Ref>();
        for (var entity : geometry.entities()) {
            boolean any = false, all = true, hasCurve = false;
            for (var curve : entity.kind() == Kind.POINT ? java.util.List.<Entity>of() : geometry.curves(entity)) {
                var points = curve.sample();
                hasCurve = true;
                for (var p : points) all &= inside(p, minX, minY, maxX, maxY);
                for (int i = 1; i < points.size(); i++)
                    any |= intersects(points.get(i - 1), points.get(i), minX, minY, maxX, maxY);
            }
            if (hasCurve && (crossing ? any : all)) result.add(entity.ref());
            else if (entity.kind() == Kind.POINT)
                for (var ref : entity.pointRefs())
                    if (inside(entity.point(ref), minX, minY, maxX, maxY)) result.add(ref);
        }
        return result;
    }

    private static boolean inside(Vec p, double x0, double y0, double x1, double y1) {
        return p.x() >= x0 && p.x() <= x1 && p.y() >= y0 && p.y() <= y1;
    }

    private static boolean intersects(Vec a, Vec b, double x0, double y0, double x1, double y1) {
        double low = 0, high = 1;
        double[] origin = {a.x(), a.y()}, delta = {b.x() - a.x(), b.y() - a.y()};
        double[] min = {x0, y0}, max = {x1, y1};
        for (int axis = 0; axis < 2; axis++) {
            if (delta[axis] == 0) {
                if (origin[axis] < min[axis] || origin[axis] > max[axis]) return false;
            } else {
                double t0 = (min[axis] - origin[axis]) / delta[axis];
                double t1 = (max[axis] - origin[axis]) / delta[axis];
                low = Math.max(low, Math.min(t0, t1));
                high = Math.min(high, Math.max(t0, t1));
                if (low > high) return false;
            }
        }
        return true;
    }
}
