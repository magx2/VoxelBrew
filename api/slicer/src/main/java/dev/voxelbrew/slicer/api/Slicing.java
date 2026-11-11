package dev.voxelbrew.slicer.api;

import dev.voxelbrew.engine.model.CadDocument;
import dev.voxelbrew.engine.printing.*;
import dev.voxelbrew.exporter.api.ExporterCatalog;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;

public interface Slicing {
    @FunctionalInterface
    public interface Commit {
        void run() throws IOException;
    }

    /**
     * Frontends serialize this callback with edits, checking revision before the atomic
     * publication.
     */
    @FunctionalInterface
    public interface PublicationGuard {
        void publish(String revision, Commit commit) throws IOException;
    }

    public static final PublicationGuard DIRECT = (revision, commit) -> commit.run();

    class OutdatedException extends IOException {
        private static final long serialVersionUID = 1L;

        public OutdatedException() {
            super("The model or print settings changed; this job is outdated");
        }
    }

    public record Result(Path output, GcodeJob job, String backendVersion, PrintArtifact artifact) {
        public Result(Path output, GcodeJob job, String backendVersion) {
            this(output, job, backendVersion, null);
        }
    }

    Result slice(
            SlicerBackend backend,
            CadDocument document,
            ManufacturingModel model,
            ExporterCatalog exporters,
            Path executable,
            Path destination,
            boolean overwrite,
            Cancellation cancellation,
            Consumer<String> diagnostics,
            PublicationGuard guard)
            throws IOException;
}
