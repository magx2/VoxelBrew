package dev.voxelbrew.gui;

import static dev.voxelbrew.engine.sketch.SketchGeometry.*;

import dev.voxelbrew.engine.sketch.SketchGeometry;

/** Screen-sized drawing snaps, evaluated on the actual curves rather than display chords. */
final class SketchSnapping {
    private SketchSnapping() {}

    record Snap(Ref ref, Vec position) {}

    static Snap find(SketchGeometry geometry, Vec pointer, double scale) {
        double best = 8 / scale;
        Snap result = null;
        for (var entity : geometry.entities()) {
            for (var ref : entity.pointRefs()) {
                var point = entity.point(ref);
                double distance = pointer.distance(point);
                if (distance < best) {
                    best = distance;
                    result = new Snap(ref, point);
                }
            }
        }
        if (pointer.length() < best) result = new Snap(ORIGIN, new Vec(0, 0));
        if (result != null) return result;
        for (var entity : geometry.entities()) {
            if (entity.kind() == Kind.POINT || entity.kind() == Kind.TEXT) continue;
            var point = nearest(entity, pointer);
            double distance = pointer.distance(point);
            if (distance < best) {
                best = distance;
                result = new Snap(entity.ref(), point);
            }
        }
        return result;
    }

    static Vec nearest(Entity curve, Vec pointer) {
        if (curve.kind() == Kind.LINE) {
            var start = curve.at(0);
            var direction = curve.at(1).minus(start);
            return start.plus(direction.times(
                    Math.clamp(pointer.minus(start).dot(direction) / Math.max(1e-20, direction.dot(direction)), 0, 1)));
        }
        // Refine each sampled local minimum, including both ends of closed curves.
        int steps = 128;
        var distances = new double[steps + 1];
        Vec best = curve.at(0);
        double distance = best.distance(pointer);
        for (int i = 0; i <= steps; i++) {
            var point = curve.at((double) i / steps);
            distances[i] = point.distance(pointer);
            if (distances[i] < distance) {
                best = point;
                distance = distances[i];
            }
        }
        for (int i = 0; i <= steps; i++) {
            if (i > 0 && distances[i] > distances[i - 1] || i < steps && distances[i] > distances[i + 1]) continue;
            double lo = Math.max(0, (double) (i - 1) / steps);
            double hi = Math.min(1, (double) (i + 1) / steps);
            for (int iteration = 0; iteration < 45; iteration++) {
                double left = lo + (hi - lo) / 3, right = hi - (hi - lo) / 3;
                if (curve.at(left).distance(pointer) < curve.at(right).distance(pointer)) hi = right;
                else lo = left;
            }
            var point = curve.at((lo + hi) / 2);
            if (point.distance(pointer) < distance) {
                best = point;
                distance = point.distance(pointer);
            }
        }
        return best;
    }
}
