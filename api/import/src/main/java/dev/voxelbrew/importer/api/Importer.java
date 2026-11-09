package dev.voxelbrew.importer.api;

import java.util.concurrent.CompletableFuture;

/** Collect settings on the host interaction thread, then read on the supplied worker.
 * Never modify the source. Cancellation completes exceptionally with CancellationException.
 * @param <R> detached result supplied by the importer
 */
@FunctionalInterface
public interface Importer<R> {
    CompletableFuture<R> importFrom(ImportWindow parent);
}
