package dev.voxelbrew.importer.api;

import dev.voxelbrew.engine.model.CadDocument;
import lombok.NonNull;

/** Detached, frontend-neutral import results. Meshes are not misrepresented as editable history. */
public sealed interface ImportResult {
    enum Kind {
        DOCUMENT
    }

    Kind kind();

    record Document(@NonNull CadDocument document) implements ImportResult {
        public Document {}

        @Override
        public Kind kind() {
            return Kind.DOCUMENT;
        }
    }
}
