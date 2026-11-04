package dev.voxelbrew.engine.model;

/** Shared edit-time and replay-time validation. Broken references are diagnosed by replay. */
final class FeatureValidation {
    private FeatureValidation() {}

    static void validate(Feature feature) {
        if (feature instanceof Feature.Sketch sketch) {
            CadDocument.identifier(sketch.profileId(), "profile ID");
            CadDocument.identifier(sketch.plane().id(), "plane ID");
            finite(feature.id(), "plane.offsetZ", sketch.plane().offsetZ());
            if (sketch.plane().id().equals("origin-xy") && sketch.plane().offsetZ() != 0) {
                throw new ModelException(
                        feature.id(), "origin-xy is fixed at 0 mm; use an offset plane ID");
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
            } else {
                throw new ModelException(feature.id(), "profile is missing or unsupported");
            }
        } else if (feature instanceof Feature.Extrude extrusion) {
            finite(feature.id(), "distance", extrusion.distance());
            if (Math.abs(extrusion.distance()) < 1e-6) {
                throw new ModelException(
                        feature.id(), "distance must have magnitude >= 0.000001 mm");
            }
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
