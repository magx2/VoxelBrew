package dev.voxelbrew.importer;

import dev.voxelbrew.importer.api.*;

public final class DefaultImportRuntime implements ImportRuntime {
    public DocumentSource documents() {
        return DocumentReader::load;
    }

    public GcodeSource gcode() {
        return new GcodeSource() {
            public dev.voxelbrew.engine.printing.GcodeJob read(java.nio.file.Path path) throws java.io.IOException {
                return GcodeReader.read(path);
            }

            public dev.voxelbrew.engine.printing.GcodeJob readLines(java.util.List<String> lines)
                    throws java.io.IOException {
                return GcodeReader.read(new java.io.ByteArrayInputStream(
                        (String.join("\n", lines) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            }
        };
    }

    public ImporterCatalog.Factory importers() {
        return ImporterRegistry::new;
    }
}
