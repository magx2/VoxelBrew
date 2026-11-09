package dev.voxelbrew.importer.api;

import java.util.Optional;

/**
 * Host abstraction for importer interactions, shared by desktop and headless workflows.
 *
 * <p>A desktop implementation can retain its actual owner window internally; a CLI implementation
 * can resolve settings from parsed arguments or prompts. Neither requires frontend types in the
 * importer contract. Implementations decide which request types they support and must document any
 * thread restrictions. This interface does not assume a local file source.
 *
 * @see ImportDialogs
 */
public interface ImportWindow {
    /**
     * Resolves an importer-defined request synchronously, returning only after confirmation or
     * cancellation.
     *
     * <p>Hosts must invoke {@link ImportDialog#collectSettings(java.util.function.Supplier)} around
     * the whole interaction so that {@link ImportDialog#getSettings()} exposes only newly confirmed
     * settings. Return an empty optional for cancellation, not for an unsupported request or invalid
     * settings. Unsupported requests must fail explicitly; validation failures must be corrected or
     * reported to the caller. {@link ImportDialogs} provides the lifecycle and request dispatch.
     *
     * @param <R> accepted settings type
     * @param dialog request to resolve; must not be {@code null}
     * @return accepted settings, also recorded on the dialog, or empty on cancellation
     * @throws UnsupportedOperationException if this host cannot handle the dialog type
     */
    <R> Optional<R> show(ImportDialog<R> dialog);
}
