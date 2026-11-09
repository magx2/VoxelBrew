package dev.voxelbrew.importer.api;

public interface ImportRuntime {
    DocumentSource documents();

    GcodeSource gcode();

    ImporterCatalog.Factory importers();

    static ImportRuntime load() {
        return java.util.ServiceLoader.load(ImportRuntime.class)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No import runtime installed"));
    }
}
