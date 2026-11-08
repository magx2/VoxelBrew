package dev.voxelbrew.engine.model;

import static dev.voxelbrew.engine.units.Measurements.*;

import javax.measure.Quantity;
import javax.measure.quantity.Length;

/** Persistent definitions only: no UI objects, protobuf messages or native handles. */
public sealed interface Feature
        permits Feature.Sketch,
                Feature.Extrude,
                Feature.ThroughCut,
                Feature.SplitBody,
                Feature.EdgeFinish,
                Feature.Shell,
                Feature.Custom,
                ConstructionFeature {
    String id();

    /** Local sketch XY frame, with an additional signed offset along its normal in mm. */
    record Plane(String id, double offsetZ, Transform frame, FaceSupport support, String constructionSupport) {
        public Plane {
            java.util.Objects.requireNonNull(frame);
            java.util.Objects.requireNonNull(constructionSupport);
            if (!constructionSupport.isEmpty()) {
                CadDocument.identifier(constructionSupport, "construction plane support");
                if (support != null)
                    throw new IllegalArgumentException("Plane cannot have both face and construction supports");
            }
        }

        public Plane(String id, double offsetZ, Transform frame, FaceSupport support) {
            this(id, offsetZ, frame, support, "");
        }

        public Plane(String id, double offsetZ) {
            this(id, offsetZ, Transform.IDENTITY, null);
        }

        public Plane(String id, Quantity<Length> offset) {
            this(id, value(offset, MILLIMETRE));
        }

        public Transform placement() {
            return frame.compose(Transform.translation(0, 0, offsetZ));
        }

        public Plane withOffset(double value) {
            return new Plane(id, value, frame, support, constructionSupport);
        }

        public boolean isOrigin() {
            return java.util.Set.of("origin-xy", "origin-xz", "origin-yz").contains(id);
        }

        public static Plane origin(String name) {
            return switch (name) {
                case "XY" -> new Plane("origin-xy", 0);
                case "XZ" -> new Plane("origin-xz", 0, new Transform(1, 0, 0, 0, 0, 0, -1, 0, 0, 1, 0, 0), null);
                case "YZ" -> new Plane("origin-yz", 0, new Transform(0, 0, 1, 0, 1, 0, 0, 0, 0, 1, 0, 0), null);
                default -> throw new IllegalArgumentException("Unknown origin plane: " + name);
            };
        }
    }

    record ProfileRef(String sketchId, String profileId) {}

    record BodyRef(String featureId, String outputName) {}

    sealed interface Profile permits Rectangle, Circle, Constrained {}

    record Rectangle(double x, double y, double width, double height) implements Profile {
        public Rectangle(Quantity<Length> x, Quantity<Length> y, Quantity<Length> width, Quantity<Length> height) {
            this(value(x, MILLIMETRE), value(y, MILLIMETRE), value(width, MILLIMETRE), value(height, MILLIMETRE));
        }
    }

    record Circle(double centerX, double centerY, double diameter) implements Profile {
        public Circle(Quantity<Length> centerX, Quantity<Length> centerY, Quantity<Length> diameter) {
            this(value(centerX, MILLIMETRE), value(centerY, MILLIMETRE), value(diameter, MILLIMETRE));
        }
    }

    record Constrained(dev.voxelbrew.engine.sketch.SketchGeometry geometry) implements Profile {
        public Constrained {
            java.util.Objects.requireNonNull(geometry);
        }
    }

    record Sketch(String id, String profileId, Plane plane, Profile profile) implements Feature {}

    record Extrude(String id, ProfileRef profile, double distance) implements Feature {
        public Extrude(String id, ProfileRef profile, Quantity<Length> distance) {
            this(id, profile, value(distance, MILLIMETRE));
        }
    }

    record ThroughCut(String id, ProfileRef profile, BodyRef body) implements Feature {}

    /** Plane equation normal dot position = offset, with a unit normal and mm offset. */
    record SplitPlane(double normalX, double normalY, double normalZ, double offset) {
        public SplitPlane(double normalX, double normalY, double normalZ, Quantity<Length> offset) {
            this(normalX, normalY, normalZ, value(offset, MILLIMETRE));
        }
    }

    record SplitBody(String id, BodyRef body, SplitPlane plane) implements Feature {}

    /** Edge indices follow the source solid's OCCT edge enumeration. Empty means every edge. */
    record EdgeFinish(String id, BodyRef body, Kind kind, double size, java.util.List<Integer> edges)
            implements Feature {
        public enum Kind {
            FILLET,
            CHAMFER
        }

        public EdgeFinish {
            edges = java.util.List.copyOf(edges);
        }
    }

    /** Face index follows the source solid's OCCT face enumeration; zero makes a closed shell. */
    record Shell(String id, BodyRef body, double thickness, int removedFace) implements Feature {}

    /** Opaque provider-owned definition. Missing providers must never rewrite these bytes. */
    record Custom(String id, String providerId, String type, int payloadVersion, byte[] payload) implements Feature {
        public Custom {
            CadDocument.identifier(id, "feature ID");
            CadDocument.identifier(providerId, "provider ID");
            CadDocument.identifier(type, "feature type");
            if (payloadVersion < 1) throw new IllegalArgumentException("Payload version must be positive");
            payload = payload.clone();
            if (payload.length > 16 * 1024 * 1024) throw new IllegalArgumentException("Feature payload exceeds 16 MiB");
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Custom c
                    && id.equals(c.id)
                    && providerId.equals(c.providerId)
                    && type.equals(c.type)
                    && payloadVersion == c.payloadVersion
                    && java.util.Arrays.equals(payload, c.payload);
        }

        @Override
        public int hashCode() {
            return 31 * java.util.Objects.hash(id, providerId, type, payloadVersion)
                    + java.util.Arrays.hashCode(payload);
        }

        @Override
        public String toString() {
            return "Custom[id="
                    + id
                    + ", providerId="
                    + providerId
                    + ", type="
                    + type
                    + ", payloadVersion="
                    + payloadVersion
                    + ", bytes="
                    + payload.length
                    + "]";
        }
    }
}
