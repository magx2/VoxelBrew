package dev.voxelbrew.exporter.api;

import java.util.concurrent.Executor;

/** ServiceLoader entry point. External providers need a public no-argument constructor.
 * Use the supplied host worker for background work; never shut it down.
 * Export futures must complete only when all provider work has finished.
 */
public interface ExporterProvider {
    ExporterDescriptor descriptor();

    Exporter<?> create(Executor worker);

    /** Host plugin directory, for exporters that rebuild documents containing custom features. */
    default Exporter<?> create(Executor worker, java.nio.file.Path pluginDirectory) {
        return create(worker);
    }
}
