package dev.voxelbrew.importer;

import dev.voxelbrew.importer.api.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Shared file interaction and bounded asynchronous reading for built-ins. */
@lombok.RequiredArgsConstructor
abstract class FileImporter implements Importer<ImportResult> {
    private final Executor worker;
    private final String title;
    private final List<String> extensions;

    @Override
    public final CompletableFuture<ImportResult> importFrom(ImportWindow parent) {
        var result = new CompletableFuture<ImportResult>();
        try {
            var dialog = new FileImportDialog(title, extensions, List.of());
            if (parent.show(dialog).isEmpty()) return CompletableFuture.failedFuture(new CancellationException());
            var source = dialog.getSettings().path();
            worker.execute(() -> {
                try (var input = ImportInput.open(source)) {
                    result.complete(read(input));
                } catch (Exception | LinkageError | AssertionError failure) {
                    result.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException failure) {
            result.completeExceptionally(failure);
        }
        return result;
    }

    protected abstract ImportResult read(InputStream input) throws IOException;
}
