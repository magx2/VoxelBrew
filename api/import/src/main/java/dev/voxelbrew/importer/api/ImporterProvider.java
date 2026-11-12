package dev.voxelbrew.importer.api;

import java.util.concurrent.Executor;

/** Discovery entry point. Register public no-argument providers with ServiceLoader.
 * The host owns the executor; providers must not shut it down. Futures cover the entire read.
 */
public interface ImporterProvider {
    int API_VERSION = 3;

    ImporterDescriptor descriptor();

    Importer<? extends ImportResult> create(Executor worker);
}
