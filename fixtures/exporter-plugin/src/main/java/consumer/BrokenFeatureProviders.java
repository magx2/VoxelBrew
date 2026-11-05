package consumer;

import dev.voxelbrew.engine.feature.FeatureDescriptor;
import dev.voxelbrew.engine.geometry.Solid;
import java.util.Map;
import java.util.ServiceConfigurationError;

/** Independent consumers loaded only by the feature-failure compatibility fixtures. */
public final class BrokenFeatureProviders {
    private BrokenFeatureProviders() {}

    private static FeatureDescriptor descriptor(String provider) {
        var original = new SpacerProvider().descriptor();
        return new FeatureDescriptor(
                provider,
                original.type(),
                "Broken spacer",
                original.apiVersion(),
                original.payloadVersion(),
                original.parameters());
    }

    public static final class Descriptor extends SpacerProvider {
        @Override
        public FeatureDescriptor descriptor() {
            throw new AssertionError("Descriptor assertion");
        }
    }

    public static final class Encoder extends SpacerProvider {
        @Override
        public FeatureDescriptor descriptor() {
            return BrokenFeatureProviders.descriptor("example.assert.encode");
        }

        @Override
        public byte[] encode(Map<String, Double> parameters) {
            throw new AssertionError("Encoder assertion");
        }
    }

    public static final class Decoder extends SpacerProvider {
        @Override
        public FeatureDescriptor descriptor() {
            return BrokenFeatureProviders.descriptor("example.assert.decode");
        }

        @Override
        public Map<String, Double> decode(int version, byte[] payload) {
            throw new AssertionError("Decoder assertion");
        }
    }

    public static final class Evaluation extends SpacerProvider {
        @Override
        public FeatureDescriptor descriptor() {
            return BrokenFeatureProviders.descriptor("example.assert.evaluate");
        }

        @Override
        public Solid evaluate(int version, byte[] payload) {
            try (var solid = super.evaluate(version, payload)) {
                throw new AssertionError("Evaluation assertion after native construction");
            }
        }
    }

    public static final class NestedServices extends SpacerProvider {
        @Override
        public FeatureDescriptor descriptor() {
            return BrokenFeatureProviders.descriptor("example.service.evaluate");
        }

        @Override
        public Solid evaluate(int version, byte[] payload) {
            throw new ServiceConfigurationError("Missing nested provider dependency");
        }
    }
}
