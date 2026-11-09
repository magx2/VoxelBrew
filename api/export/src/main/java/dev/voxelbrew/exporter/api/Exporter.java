package dev.voxelbrew.exporter.api;

import dev.voxelbrew.engine.model.CadDocument;
import java.util.concurrent.CompletableFuture;

/**
 * Exports a CAD document to an implementation-defined destination, such as a local file or a cloud
 * service. Implementations own settings collection, model conversion, validation and publication;
 * the host does not supply an output stream or assume that the result is a file.
 *
 * <p>Request settings through {@link ExportWindow} so the same exporter can work with desktop and
 * headless hosts. Native evaluation is not provided by this interface: an exporter needing evaluated
 * geometry must supply its own evaluation dependencies. Ordinary exports must not mutate the input
 * document or its save metadata. Geometry exporters must reject incomplete or failed evaluations
 * and define their selection, units, coordinate space and treatment of supports explicitly.
 *
 * @param <R> successful result, for example a {@link java.nio.file.Path} or an upload receipt
 * @see ExporterProvider
 */
@FunctionalInterface
public interface Exporter<R> {
    /**
     * Collects settings and starts an export of the supplied document.
     *
     * <p>Call {@link ExportWindow#show(ExportDialog)} on the host's interaction thread (the UI thread
     * for desktop hosts), then schedule expensive conversion and I/O on the executor supplied by
     * {@link ExporterProvider#create(java.util.concurrent.Executor)}. Copy accepted settings before
     * starting background work; do not read mutable dialog state from a worker.
     *
     * <p>Complete the future only after validation and publication finish. Represent cancelled
     * settings collection with a {@link java.util.concurrent.CancellationException}, and propagate
     * export failures through exceptional completion. File exporters must honor overwrite consent
     * and preserve existing files on failure, for example by publishing a completed temporary file
     * atomically. Cancelling the returned future does not by itself stop worker tasks: implementations
     * supporting cancellation during export must coordinate cleanup and publication themselves.
     *
     * @param document document to export; must not be {@code null}
     * @param parent host for exporter-defined interactions; must not be {@code null}
     * @return non-null future reporting the published result, cancellation or failure
     */
    CompletableFuture<R> export(CadDocument document, ExportWindow parent);
}
