package dev.voxelbrew.importer.api;

import dev.voxelbrew.engine.model.CadDocument;
import dev.voxelbrew.engine.printing.GcodeJob;
import lombok.NonNull;

/** Detached, frontend-neutral import results. Meshes are not misrepresented as editable history. */
public sealed interface ImportResult {
    enum Kind {
        DOCUMENT,
        GCODE
    }

    Kind kind();

    record Document(@NonNull CadDocument document) implements ImportResult {
        public Document {}

        @Override
        public Kind kind() {
            return Kind.DOCUMENT;
        }
    }

    record Gcode(@NonNull GcodeJob job) implements ImportResult {
        public Gcode {}

        @Override
        public Kind kind() {
            return Kind.GCODE;
        }
    }
}
