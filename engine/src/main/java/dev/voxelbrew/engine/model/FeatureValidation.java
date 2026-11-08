package dev.voxelbrew.engine.model;

/** Shared edit-time and replay-time validation. Broken references are diagnosed by replay. */
final class FeatureValidation {
    private FeatureValidation() {}

    static void validate(Feature feature) {
        if (feature instanceof Feature.Sketch sketch) {
            CadDocument.identifier(sketch.profileId(), "profile ID");
            CadDocument.identifier(sketch.plane().id(), "plane ID");
            finite(feature.id(), "plane.offsetZ", sketch.plane().offsetZ());
            if (sketch.plane().isOrigin()
                    && (sketch.plane().offsetZ() != 0
                            || sketch.plane().support() != null
                            || !sketch.plane().constructionSupport().isEmpty()
                            || !sketch.plane()
                                    .frame()
                                    .equals(Feature.Plane.origin(sketch.plane()
                                                    .id()
                                                    .substring(7)
                                                    .toUpperCase(java.util.Locale.ROOT))
                                            .frame()))) {
                throw new ModelException(feature.id(), "Origin planes are fixed; use an offset plane ID");
            }
            if (sketch.profile() instanceof Feature.Rectangle r) {
                finite(feature.id(), "x", r.x());
                finite(feature.id(), "y", r.y());
                positive(feature.id(), "width", r.width());
                positive(feature.id(), "height", r.height());
                finite(feature.id(), "x + width", r.x() + r.width());
                finite(feature.id(), "y + height", r.y() + r.height());
            } else if (sketch.profile() instanceof Feature.Circle c) {
                finite(feature.id(), "centerX", c.centerX());
                finite(feature.id(), "centerY", c.centerY());
                positive(feature.id(), "diameter", c.diameter());
            } else if (sketch.profile() instanceof Feature.Constrained c) {
                try {
                    dev.voxelbrew.engine.sketch.SketchConstraints.requireSatisfied(c.geometry());
                } catch (IllegalArgumentException failure) {
                    throw new ModelException(feature.id(), failure.getMessage());
                }
            } else {
                throw new ModelException(feature.id(), "profile is missing or unsupported");
            }
        } else if (feature instanceof Feature.Extrude extrusion) {
            finite(feature.id(), "distance", extrusion.distance());
            if (Math.abs(extrusion.distance()) < 1e-6) {
                throw new ModelException(feature.id(), "distance must have magnitude >= 0.000001 mm");
            }
        } else if (feature instanceof Feature.EdgeFinish finish) {
            positive(feature.id(), "size", finish.size());
            if (finish.kind() == null) throw new ModelException(feature.id(), "edge finish kind is missing");
            if (finish.edges().isEmpty()) throw new ModelException(feature.id(), "select at least one edge");
            if (finish.edges().stream().anyMatch(index -> index == null || index < 1))
                throw new ModelException(feature.id(), "edge indices must be positive");
        } else if (feature instanceof Feature.Shell shell) {
            positive(feature.id(), "thickness", shell.thickness());
            if (shell.removedFace() < 0) throw new ModelException(feature.id(), "removed face must be nonnegative");
        } else if (feature instanceof Feature.SplitBody split) {
            var p = split.plane();
            finite(feature.id(), "normalX", p.normalX());
            finite(feature.id(), "normalY", p.normalY());
            finite(feature.id(), "normalZ", p.normalZ());
            finite(feature.id(), "offset", p.offset());
            if (Math.abs(Math.hypot(Math.hypot(p.normalX(), p.normalY()), p.normalZ()) - 1) > 1e-9)
                throw new ModelException(feature.id(), "split plane normal must have unit length");
        }
    }

    private static void finite(String id, String field, double value) {
        if (!Double.isFinite(value)) {
            throw new ModelException(id, field + " must be finite");
        }
    }

    private static void positive(String id, String field, double value) {
        finite(id, field, value);
        if (value < 1e-6) {
            throw new ModelException(id, field + " must be >= 0.000001 mm");
        }
    }
}
