package dev.voxelbrew.engine.feature;

import dev.voxelbrew.engine.model.CadDocument;
import lombok.NonNull;

/** Numeric parameter metadata shared by CLI and GUI. Bounds are inclusive. */
public record FeatureParameter(
        String key, @NonNull String label, @NonNull String unit, double defaultValue, double minimum, double maximum) {
    public FeatureParameter {
        CadDocument.identifier(key, "parameter key");

        if (!Double.isFinite(minimum) || !Double.isFinite(maximum) || minimum > maximum)
            throw new IllegalArgumentException("Invalid parameter bounds: " + key);
        check(key, defaultValue, minimum, maximum);
    }

    public void validate(double value) {
        check(key, value, minimum, maximum);
    }

    private static void check(String key, double value, double minimum, double maximum) {
        if (!Double.isFinite(value) || value < minimum || value > maximum)
            throw new IllegalArgumentException(key + " must be finite and between " + minimum + " and " + maximum);
    }
}
