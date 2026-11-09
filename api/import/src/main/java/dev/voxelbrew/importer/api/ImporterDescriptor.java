package dev.voxelbrew.importer.api;
/** Stable discovery metadata; file settings belong to importer-owned dialogs. */
public record ImporterDescriptor(
        String id,
        @lombok.NonNull String name,
        int apiVersion,
        @lombok.NonNull ImportResult.Kind resultKind) {
    public ImporterDescriptor {
        if (id == null || !id.matches("[a-z0-9][a-z0-9.-]*")) throw new IllegalArgumentException("Invalid importer ID");
        if (name.isBlank()) throw new IllegalArgumentException("Missing importer name");
    }
}
