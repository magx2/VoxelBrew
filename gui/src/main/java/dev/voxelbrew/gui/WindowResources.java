package dev.voxelbrew.gui;

import dev.voxelbrew.exporter.ExporterRegistry;
import dev.voxelbrew.exporter.printing.SlicerRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import javax.inject.*;
import lombok.extern.slf4j.Slf4j;

/** Window-owned resources. Dagger scopes instances; the window explicitly closes them. */
@Singleton
@Slf4j
final class WindowResources implements AutoCloseable {
    final ExporterRegistry exporters;
    final SlicerRegistry slicers;
    final ExecutorService rebuild, files, slicing, validation, appearance;
    private boolean closed;
    private final CompletableFuture<Void> disposal = new CompletableFuture<>();

    @Inject
    WindowResources(
            @Named("plugins") Path plugins,
            ExporterRegistry.Factory exporterFactory,
            SlicerRegistry.Factory slicerFactory) {
        ExporterRegistry opened = null;
        try {
            opened = exporterFactory.open(plugins);
            slicers = slicerFactory.open(plugins);
            exporters = opened;
        } catch (IOException | RuntimeException | Error failure) {
            if (opened != null)
                try {
                    opened.close();
                } catch (IOException close) {
                    failure.addSuppressed(close);
                }
            if (failure instanceof IOException io) throw new UncheckedIOException(io);
            if (failure instanceof RuntimeException runtime) throw runtime;
            throw (Error) failure;
        }
        rebuild = worker("rebuild");
        files = worker("file-io");
        slicing = worker("slicer");
        validation = worker("print-validation");
        appearance = worker("appearance");
    }

    static ExecutorService worker(String role) {
        return Executors.newSingleThreadExecutor(
                Thread.ofPlatform().daemon().name("voxelbrew-" + role).factory());
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        // Drain captured file writes and cancelled native/slicer work on their owning threads.
        var workers = List.of(rebuild, files, slicing, validation, appearance);
        for (var worker : workers) worker.shutdown();
        if (workers.stream().allMatch(ExecutorService::isTerminated)) closeLoaders();
        else
            Thread.ofVirtual()
                    .name("voxelbrew-window-cleanup")
                    .start(
                            () -> {
                                boolean interrupted = false;
                                for (var worker : workers) {
                                    while (!worker.isTerminated()) {
                                        try {
                                            worker.awaitTermination(1, TimeUnit.DAYS);
                                        } catch (InterruptedException failure) {
                                            interrupted = true;
                                        }
                                    }
                                }
                                closeLoaders();
                                if (interrupted) Thread.currentThread().interrupt();
                            });
    }

    CompletionStage<Void> whenClosed() {
        return disposal.minimalCompletionStage();
    }

    private void closeLoaders() {
        IOException failed = null;
        try {
            exporters.close();
        } catch (IOException failure) {
            failed = failure;
        }
        try {
            slicers.close();
        } catch (IOException failure) {
            if (failed == null) failed = failure;
            else failed.addSuppressed(failure);
        }
        if (failed == null) disposal.complete(null);
        else {
            log.warn("Cannot close plugin loaders cleanly", failed);
            disposal.completeExceptionally(failed);
        }
    }
}
