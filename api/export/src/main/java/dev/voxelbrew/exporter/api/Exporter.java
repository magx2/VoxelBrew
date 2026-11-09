package dev.voxelbrew.exporter.api;

import dev.voxelbrew.engine.model.CadDocument;
import java.util.concurrent.CompletableFuture;

/** Document export. The exporter owns its interactions through the parent window.
 * Completion reports an exporter-defined result, cancellation, or a failure.
 */
@FunctionalInterface
public interface Exporter<R> {
    CompletableFuture<R> export(CadDocument document, ExportWindow parent);
}
