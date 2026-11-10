package dev.voxelbrew.engine.printing;

import dev.voxelbrew.engine.model.BodyInstance;
import dev.voxelbrew.engine.model.Transform;
import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;
import java.util.*;

/** Conservative XY packing of placed object/support bounds; never changes rotation or height. */
public final class PrintArrangement {
    private PrintArrangement() {}

    private record Part(BodyInstance instance, Rectangle2D bounds) {}

    /** Empty means no layout was found; callers must retain the original setup in that case. */
    public static Optional<List<BodyInstance>> arrange(ManufacturingModel model, double gap) {
        if (!Double.isFinite(gap) || gap <= 0) throw new IllegalArgumentException("Gap must be positive");
        var bed = MeshGeometry.polygon(model.profile().printer().bed());
        for (var zone : model.profile().printer().excludedZones()) bed.subtract(MeshGeometry.polygon(zone));
        var bedBounds = bed.getBounds2D();
        var parts = new ArrayList<Part>();
        for (var instance : model.instances()) {
            var b = MeshGeometry.bounds(model.placedMesh(instance));
            Rectangle2D bounds = new Rectangle2D.Double(b.minX(), b.minY(), b.maxX() - b.minX(), b.maxY() - b.minY());
            for (var support : model.setup().supports())
                if (support.instanceId().equals(instance.id())) {
                    var s = MeshGeometry.bounds(
                            MeshGeometry.transform(MeshGeometry.box(support), instance.transform()));
                    bounds.add(new Rectangle2D.Double(s.minX(), s.minY(), s.maxX() - s.minX(), s.maxY() - s.minY()));
                }
            parts.add(new Part(instance, bounds));
        }
        parts.sort(Comparator.comparingDouble(
                        (Part p) -> p.bounds().getWidth() * p.bounds().getHeight())
                .reversed());
        var occupied = new ArrayList<Rectangle2D>();
        var placements = new HashMap<String, BodyInstance>();
        for (var part : parts) {
            var xs = new TreeSet<Double>();
            var ys = new TreeSet<Double>();
            xs.add(bedBounds.getMinX() + gap);
            ys.add(bedBounds.getMinY() + gap);
            for (var rect : occupied) {
                xs.add(rect.getMaxX() + gap);
                ys.add(rect.getMaxY() + gap);
            }
            for (var zone : model.profile().printer().excludedZones()) {
                var rect = MeshGeometry.polygon(zone).getBounds2D();
                xs.add(rect.getMaxX() + gap);
                ys.add(rect.getMaxY() + gap);
            }
            Rectangle2D chosen = null;
            search:
            for (double y : ys)
                for (double x : xs) {
                    var candidate = new Rectangle2D.Double(
                            x, y, part.bounds().getWidth(), part.bounds().getHeight());
                    var padded = new Rectangle2D.Double(
                            x - gap, y - gap, candidate.width + 2 * gap, candidate.height + 2 * gap);
                    var outside = new Area(padded);
                    outside.subtract(bed);
                    if (!outside.isEmpty() || occupied.stream().anyMatch(padded::intersects)) continue;
                    chosen = candidate;
                    break search;
                }
            if (chosen == null) return Optional.empty();
            occupied.add(chosen);
            var source = part.instance();
            placements.put(
                    source.id(),
                    new BodyInstance(
                            source.id(),
                            source.bodyId(),
                            Transform.translation(
                                            chosen.getX() - part.bounds().getX(),
                                            chosen.getY() - part.bounds().getY(),
                                            0)
                                    .compose(source.transform())));
        }
        return Optional.of(
                model.instances().stream().map(i -> placements.get(i.id())).toList());
    }
}
