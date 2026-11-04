package consumer;

import dev.voxelbrew.engine.feature.*;
import dev.voxelbrew.engine.geometry.Solid;
import java.nio.ByteBuffer;
import java.util.*;

/** Independent API consumer. Version 1 payload: three big-endian doubles in millimeters. */
public class SpacerProvider implements FeatureProvider {
    public FeatureDescriptor descriptor() {
        return new FeatureDescriptor(
                "example.spacer",
                "spacer",
                "Spacer",
                API_VERSION,
                1,
                List.of(
                        new FeatureParameter(
                                "outerDiameter", "Outer diameter", "mm", 20, 0.001, 1000),
                        new FeatureParameter("innerDiameter", "Hole diameter", "mm", 4, 0.001, 999),
                        new FeatureParameter("height", "Height", "mm", 8, 0.001, 1000)));
    }

    private static void validate(Map<String, Double> p) {
        if (p.get("innerDiameter") >= p.get("outerDiameter"))
            throw new IllegalArgumentException("innerDiameter must be smaller than outerDiameter");
    }

    public byte[] encode(Map<String, Double> p) {
        validate(p);
        return ByteBuffer.allocate(24)
                .putDouble(p.get("outerDiameter"))
                .putDouble(p.get("innerDiameter"))
                .putDouble(p.get("height"))
                .array();
    }

    public Map<String, Double> decode(int version, byte[] payload) {
        if (version != 1)
            throw new IllegalArgumentException("Unsupported spacer payload version " + version);
        if (payload.length != 24)
            throw new IllegalArgumentException("Invalid spacer payload length");
        var b = ByteBuffer.wrap(payload);
        var result =
                Map.of(
                        "outerDiameter",
                        b.getDouble(),
                        "innerDiameter",
                        b.getDouble(),
                        "height",
                        b.getDouble());
        validate(result);
        return result;
    }

    public Solid evaluate(int version, byte[] payload) {
        var p = decode(version, payload);
        try (var cylinder = Solid.circlePrism(0, 0, 0, p.get("outerDiameter"), p.get("height"))) {
            return cylinder.cutThroughCircle(0, 0, p.get("innerDiameter"));
        }
    }

    public static final class Duplicate extends SpacerProvider {}

    public static final class Incompatible extends SpacerProvider {
        @Override
        public FeatureDescriptor descriptor() {
            return new FeatureDescriptor(
                    "example.future", "spacer", "Future spacer", 999, 1, List.of());
        }
    }

    public static final class Failing extends SpacerProvider {
        @Override
        public FeatureDescriptor descriptor() {
            var d = super.descriptor();
            return new FeatureDescriptor(
                    "example.failed", d.type(), "Failed feature", API_VERSION, 1, d.parameters());
        }

        @Override
        public Solid evaluate(int version, byte[] payload) {
            throw new LinkageError("Deliberate feature provider failure");
        }
    }
}
