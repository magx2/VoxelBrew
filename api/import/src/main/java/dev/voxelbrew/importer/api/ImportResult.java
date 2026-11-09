package dev.voxelbrew.importer.api;

import dev.voxelbrew.engine.model.CadDocument;
import dev.voxelbrew.engine.printing.GcodeJob;
import java.util.Objects;

/** Detached, frontend-neutral import results. Meshes are not misrepresented as editable history. */
public sealed interface ImportResult {
    enum Kind {
        DOCUMENT,
        GCODE
    }

    Kind kind();

    record Document(CadDocument document) implements ImportResult {
        public Document {
            Objects.requireNonNull(document);
        }

        @Override
        public Kind kind() {
            return Kind.DOCUMENT;
        }
    }

    record Gcode(GcodeJob job) implements ImportResult {
        public Gcode {
            Objects.requireNonNull(job);
        }

        @Override
        public Kind kind() {
            return Kind.GCODE;
        }
    }
}
