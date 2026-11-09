package dev.voxelbrew.importer.api;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Per-host registry that dispatches typed import requests to synchronous handlers.
 *
 * <p>Configure handlers at the frontend composition root before invoking importers. Desktop handlers
 * can capture a parent window; CLI handlers can capture parsed arguments. Lookup uses the exact
 * runtime class of a request, not superclass or interface matching. Registering a provider JAR does
 * not automatically register its custom request handlers.
 *
 * <p>This registry performs no thread dispatch and is not thread-safe. Keep it local to its host or
 * window and serialize registration and interactions. Handlers should return validated, preferably
 * immutable settings and must not call {@code collectSettings} themselves.
 */
public final class ImportDialogs implements ImportWindow {
    private final Map<Class<?>, Function<ImportDialog<?>, Optional<?>>> handlers = new HashMap<>();

    /** Creates an empty registry; register supported request types before showing dialogs. */
    public ImportDialogs() {}

    /**
     * Installs a handler for one concrete request class.
     *
     * @param <R> settings returned by the handler
     * @param <D> request type declaring those settings
     * @param type exact runtime request class to support
     * @param handler callback returning accepted settings or an empty optional on cancellation
     * @return this registry for chained registration
     * @throws NullPointerException if either argument is null
     * @throws IllegalArgumentException if a handler is already registered for this class
     */
    public <R, D extends ImportDialog<R>> ImportDialogs register(
            Class<D> type, Function<? super D, Optional<R>> handler) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(handler, "handler");
        if (handlers.putIfAbsent(type, request -> handler.apply(type.cast(request))) != null)
            throw new IllegalArgumentException("Dialog handler already registered: " + type.getName());
        return this;
    }

    /**
     * Invokes the handler for the request's exact class on the calling thread.
     *
     * <p>Clears earlier settings before handler lookup. A successful result is recorded on the
     * dialog; cancellation, unsupported requests and handler failures leave no accepted settings.
     * Exceptions from the handler propagate unchanged.
     *
     * @param <R> settings type declared by the request
     * @param dialog request whose settings should be collected
     * @return handler result, empty for cancellation
     * @throws NullPointerException if the dialog or handler's returned optional is null
     * @throws UnsupportedOperationException if no handler supports the exact request class
     */
    @Override
    @SuppressWarnings("unchecked") // register binds each request class to its declared result type.
    public <R> Optional<R> show(ImportDialog<R> dialog) {
        Objects.requireNonNull(dialog, "dialog");
        return dialog.collectSettings(() -> {
            var handler = handlers.get(dialog.getClass());
            if (handler == null)
                throw new UnsupportedOperationException(
                        "Unsupported import dialog: " + dialog.getClass().getName());
            return (Optional<R>) handler.apply(dialog);
        });
    }
}
