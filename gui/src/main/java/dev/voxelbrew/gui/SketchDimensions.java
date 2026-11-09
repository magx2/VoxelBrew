package dev.voxelbrew.gui;

import static dev.voxelbrew.engine.sketch.SketchGeometry.*;

import dev.voxelbrew.engine.sketch.SketchGeometry;
import java.util.List;

/** Infers a driving measurement from the selected references and annotation placement. */
final class SketchDimensions {
    private SketchDimensions() {}

    record Measurement(Type type, double value) {}

    static List<Ref> references(SketchGeometry g, List<Ref> refs) {
        return refs.stream()
                .map(r -> r.part() == Part.OBJECT && g.entity(r.entityId()).kind() == Kind.POINT
                        ? g.entity(r.entityId()).ref(Part.POINT)
                        : r)
                .toList();
    }

    static Measurement measure(SketchGeometry g, List<Ref> refs, Vec position) {
        refs = references(g, refs);
        if (refs.size() == 1 && refs.getFirst().part() == Part.OBJECT) {
            var e = g.entity(refs.getFirst().entityId());
            if (e.kind() == Kind.LINE)
                return new Measurement(Type.LENGTH, e.at(0).distance(e.at(1)));
            if (e.kind() == Kind.CIRCLE) return new Measurement(Type.DIAMETER, e.value(2) * 2);
            if (e.kind() == Kind.ARC) return new Measurement(Type.RADIUS, e.value(2));
        }
        if (refs.size() == 2 && refs.stream().allMatch(r -> r.part() != Part.OBJECT)) {
            var a = g.point(refs.get(0));
            var b = g.point(refs.get(1));
            if (Math.abs(b.x() - a.x()) > 1e-8
                    && Math.abs(b.y() - a.y()) > 1e-8
                    && position.x() >= Math.min(a.x(), b.x())
                    && position.x() <= Math.max(a.x(), b.x())
                    && position.y() >= Math.min(a.y(), b.y())
                    && position.y() <= Math.max(a.y(), b.y())) return new Measurement(Type.DISTANCE, a.distance(b));
            boolean vertical = position.x() < Math.min(a.x(), b.x()) || position.x() > Math.max(a.x(), b.x());
            return vertical
                    ? new Measurement(Type.DISTANCE_Y, b.y() - a.y())
                    : new Measurement(Type.DISTANCE_X, b.x() - a.x());
        }
        if (refs.size() == 2
                && refs.stream()
                        .allMatch(r -> r.part() == Part.OBJECT
                                && g.entity(r.entityId()).kind() == Kind.LINE)) {
            var a = g.entity(refs.get(0).entityId());
            var b = g.entity(refs.get(1).entityId());
            var u = a.at(1).minus(a.at(0));
            var v = b.at(1).minus(b.at(0));
            if (Math.abs(u.unit().cross(v.unit())) < 1e-6)
                return new Measurement(
                        Type.DISTANCE, Math.abs(u.unit().cross(b.at(0).minus(a.at(0)))));
            return new Measurement(Type.ANGLE, Math.toDegrees(Math.atan2(u.cross(v), u.dot(v))));
        }
        throw new IllegalArgumentException("Unsupported dimension selection");
    }
}
