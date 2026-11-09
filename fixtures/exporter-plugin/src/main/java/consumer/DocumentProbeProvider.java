package consumer;

import dev.voxelbrew.exporter.api.*;
import java.nio.file.Files;
import java.util.concurrent.*;

/** Independent API-only document exporter used to qualify runtime JAR discovery. */
public class DocumentProbeProvider implements ExporterProvider {
    public DocumentProbeProvider() {}

    @Override
    public ExporterDescriptor descriptor() {
        return new ExporterDescriptor("document-probe", "Document probe");
    }

    @Override
    public Exporter<?> create(Executor worker) {
        return (document, parent) -> {
            var dialog = new FileExportDialog("Document probe", "txt");
            if (parent.show(dialog).isEmpty()) return CompletableFuture.failedFuture(new CancellationException());
            var destination = dialog.getSettings();
            return CompletableFuture.supplyAsync(
                    () -> {
                        try {
                            var options = destination.overwrite()
                                    ? new java.nio.file.OpenOption[] {
                                        java.nio.file.StandardOpenOption.CREATE,
                                        java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
                                    }
                                    : new java.nio.file.OpenOption[] {java.nio.file.StandardOpenOption.CREATE_NEW};
                            Files.writeString(destination.path(), "document=" + document.id(), options);
                            return destination.path();
                        } catch (java.io.IOException failure) {
                            throw new CompletionException(failure);
                        }
                    },
                    worker);
        };
    }

    public static final class Duplicate extends DocumentProbeProvider {}

    public static final class Failing extends DocumentProbeProvider {
        @Override
        public ExporterDescriptor descriptor() {
            return new ExporterDescriptor("failing", "Failure");
        }

        @Override
        public Exporter<?> create(Executor worker) {
            return (document, parent) ->
                    CompletableFuture.failedFuture(new java.io.IOException("Deliberate provider failure"));
        }
    }
}
