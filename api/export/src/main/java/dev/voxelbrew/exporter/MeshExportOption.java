package dev.voxelbrew.exporter;

import java.util.List;
import lombok.NonNull;

/** Typed, frontend-neutral provider option; values cross the SPI as canonical strings. */
@lombok.extern.slf4j.Slf4j
public record MeshExportOption(
        String key,
        @NonNull String label,
        @NonNull Type type,
        @NonNull String defaultValue,
        List<String> choices) {
    public enum Type {
        STRING,
        BOOLEAN,
        INTEGER,
        DECIMAL,
        CHOICE
    }

    public MeshExportOption {
        if (key == null || !key.matches("[a-z][a-z0-9.-]*")) throw new IllegalArgumentException("Invalid option key");

        choices = List.copyOf(choices);
        validate(type, defaultValue, choices);
    }

    public void validate(String value) {
        try {
            validate(type, value, choices);
        } catch (IllegalArgumentException failure) {
            log.debug("Rejected exporter option {} of type {}", key, type);
            throw new IllegalArgumentException("Invalid option '" + key + "': " + failure.getMessage(), failure);
        }
    }

    private static void validate(Type type, @NonNull String value, List<String> choices) {

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
                if (!choices.contains(value)) throw new IllegalArgumentException("expected one of " + choices);
            }
            case STRING -> {}
        }
    }
}
