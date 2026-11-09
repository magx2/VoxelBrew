package dev.voxelbrew.exporter.api;

/** Frontend-neutral interaction request with an exporter-defined result type.
 * Providers may define any request data, validation, and response values they need.
 * Each frontend registers a handler for the request type with its window host.
 */
public interface ExportDialog<R> {
    String title();
}
