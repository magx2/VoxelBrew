package dev.voxelbrew.exporter.api;

import java.util.Objects;

/** Metadata shared by every exporter; no local-file or result-type assumption. */
public record ExporterDescriptor(String id, String name) {
    public ExporterDescriptor {
        if (id == null || !id.matches("[a-z0-9][a-z0-9.-]*")) throw new IllegalArgumentException("Invalid exporter ID");
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) throw new IllegalArgumentException("Missing exporter name");
    }
}
