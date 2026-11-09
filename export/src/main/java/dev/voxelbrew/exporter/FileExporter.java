package dev.voxelbrew.exporter;

import dev.voxelbrew.engine.model.CadDocument;
import dev.voxelbrew.exporter.api.ExportWindow;
import dev.voxelbrew.exporter.api.Exporter;
import dev.voxelbrew.exporter.api.FileExportDialog;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import lombok.RequiredArgsConstructor;

/** Shared destination dialog and asynchronous publication for document exporters. */
@RequiredArgsConstructor
abstract class FileExporter implements Exporter<Path> {
    private final String extension;
    private final String title;
    private final Executor worker;
    private final DocumentOutput writer;

    @Override
    public final CompletableFuture<Path> export(CadDocument document, ExportWindow parent) {
        var selected = parent.show(new FileExportDialog(title, extension));
        if (selected.isEmpty()) return CompletableFuture.failedFuture(new CancellationException());
        Path target = selected.get().path().toAbsolutePath().normalize();
        boolean overwrite = selected.get().overwrite();
        var result = new CompletableFuture<Path>();
        try {
            worker.execute(() -> {
                try {
                    writer.save(document, target, overwrite);
                    result.complete(target);
                } catch (Exception | LinkageError failure) {
                    result.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException failure) {
            result.completeExceptionally(failure);
        }
        return result;
    }
}
