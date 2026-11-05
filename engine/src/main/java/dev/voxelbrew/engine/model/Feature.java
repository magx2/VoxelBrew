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
                Feature.Custom {
    String id();

    record Plane(String id, double offsetZ) {
        public Plane(String id, Quantity<Length> offset) {
            this(id, value(offset, MILLIMETRE));
        }
    }

    record ProfileRef(String sketchId, String profileId) {}

    record BodyRef(String featureId, String outputName) {}

    sealed interface Profile permits Rectangle, Circle, Constrained {}

    record Rectangle(double x, double y, double width, double height) implements Profile {
        public Rectangle(
                Quantity<Length> x,
                Quantity<Length> y,
                Quantity<Length> width,
                Quantity<Length> height) {
            this(
                    value(x, MILLIMETRE),
                    value(y, MILLIMETRE),
                    value(width, MILLIMETRE),
                    value(height, MILLIMETRE));
        }
    }

    record Circle(double centerX, double centerY, double diameter) implements Profile {
        public Circle(
                Quantity<Length> centerX, Quantity<Length> centerY, Quantity<Length> diameter) {
            this(
                    value(centerX, MILLIMETRE),
                    value(centerY, MILLIMETRE),
                    value(diameter, MILLIMETRE));
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

    /** Opaque provider-owned definition. Missing providers must never rewrite these bytes. */
    record Custom(String id, String providerId, String type, int payloadVersion, byte[] payload)
            implements Feature {
        public Custom {
            CadDocument.identifier(id, "feature ID");
            CadDocument.identifier(providerId, "provider ID");
            CadDocument.identifier(type, "feature type");
            if (payloadVersion < 1)
                throw new IllegalArgumentException("Payload version must be positive");
            payload = payload.clone();
            if (payload.length > 16 * 1024 * 1024)
                throw new IllegalArgumentException("Feature payload exceeds 16 MiB");
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
