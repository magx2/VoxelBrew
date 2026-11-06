package dev.voxelbrew.importer.api;

import java.util.List;
import java.util.Objects;

/** Typed, frontend-neutral provider option; values cross the SPI as canonical strings. */
@lombok.extern.slf4j.Slf4j
public record ImportOption(
        String key, String label, Type type, String defaultValue, List<String> choices) {
    public enum Type {
        STRING,
        BOOLEAN,
        INTEGER,
        DECIMAL,
        CHOICE
    }

    public ImportOption {
        if (key == null || !key.matches("[a-z][a-z0-9.-]*"))
            throw new IllegalArgumentException("Invalid option key");
        Objects.requireNonNull(label);
        Objects.requireNonNull(type);
        Objects.requireNonNull(defaultValue);
        choices = List.copyOf(choices);
        validate(type, defaultValue, choices);
    }

    public void validate(String value) {
        try {
            validate(type, value, choices);
        } catch (IllegalArgumentException failure) {
            log.debug("Rejected importer option {} of type {}", key, type);
            throw new IllegalArgumentException(
                    "Invalid option '" + key + "': " + failure.getMessage(), failure);
        }
    }

    private static void validate(Type type, String value, List<String> choices) {
        Objects.requireNonNull(value);
        switch (type) {
            case BOOLEAN -> {
                if (!value.equals("true") && !value.equals("false"))
                    throw new IllegalArgumentException("expected true or false");
            }
            case INTEGER -> Long.parseLong(value);
            case DECIMAL -> {
                if (!Double.isFinite(Double.parseDouble(value)))
                    throw new IllegalArgumentException("expected finite decimal");
            }
            case CHOICE -> {
                if (!choices.contains(value))
                    throw new IllegalArgumentException("expected one of " + choices);
            }
            case STRING -> {}
        }
    }
}
