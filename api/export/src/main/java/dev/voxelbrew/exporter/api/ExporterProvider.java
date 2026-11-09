package dev.voxelbrew.exporter.api;

import java.util.concurrent.Executor;

/**
 * Discovery and construction entry point for an exporter implementation.
 *
 * <p>External JARs register a public implementation with a public no-argument constructor in the
 * UTF-8 resource {@code META-INF/services/dev.voxelbrew.exporter.api.ExporterProvider}. Each line
 * names a provider class, not an exporter class. The host discovers providers with
 * {@link java.util.ServiceLoader}; no dependency-injection annotations are required. Compile against
 * the host API but do not bundle its classes into the plugin JAR.
 *
 * <p>Keep construction and metadata lookup lightweight. Exporters use the supplied host executor for
 * background work and must never shut it down. Provider-owned resources must remain valid until all
 * export futures using them have completed. Discovery alone does not register frontend handlers for
 * custom {@link ExportDialog} types; those require separate host integration.
 */
public interface ExporterProvider {
    /**
     * Describes the option shown in export menus and selected by identifier in headless workflows.
     *
     * @return non-null metadata with a stable, unique ID and a human-readable name
     */
    ExporterDescriptor descriptor();

    /**
     * Creates an exporter with the host's background executor.
     *
     * <p>Keep invocation-specific settings local to each export or create separate instances so that
     * simultaneous exports cannot overwrite each other's state.
     *
     * @param worker host-owned executor for conversion and I/O; must not be {@code null}
     * @return non-null exporter implementing this provider's advertised option
     */
    Exporter<?> create(Executor worker);

    /**
     * Creates an exporter with access to the host's plugin directory.
     *
     * <p>Override this overload if document evaluation needs to discover custom feature providers.
     * The default implementation ignores the directory and delegates to {@link #create(Executor)}.
     * The directory is not an export destination and need not already exist.
     *
     * @param worker host-owned executor for conversion and I/O
     * @param pluginDirectory directory used by the host for plugin discovery
     * @return non-null exporter configured for this host
     */
    default Exporter<?> create(Executor worker, java.nio.file.Path pluginDirectory) {
        return create(worker);
    }
}
