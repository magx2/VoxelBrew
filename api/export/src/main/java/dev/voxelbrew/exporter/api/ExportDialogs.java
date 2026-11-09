package dev.voxelbrew.exporter.api;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** Per-host typed dialog handlers. Desktop handlers render controls; CLI handlers use arguments
 * or console prompts. Configure at the composition root before invoking exporters.
 */
public final class ExportDialogs implements ExportWindow {
    private final Map<Class<?>, Function<ExportDialog<?>, Optional<?>>> handlers = new HashMap<>();

    public <R, D extends ExportDialog<R>> ExportDialogs register(
            Class<D> type, Function<? super D, Optional<R>> handler) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(handler, "handler");
        if (handlers.putIfAbsent(type, request -> handler.apply(type.cast(request))) != null)
            throw new IllegalArgumentException("Dialog handler already registered: " + type.getName());
        return this;
    }

    @Override
    @SuppressWarnings("unchecked") // register binds each request class to its declared result type.
    public <R> Optional<R> show(ExportDialog<R> dialog) {
        Objects.requireNonNull(dialog, "dialog");
        return dialog.collectSettings(() -> {
            var handler = handlers.get(dialog.getClass());
            if (handler == null)
                throw new UnsupportedOperationException(
                        "Unsupported export dialog: " + dialog.getClass().getName());
            return (Optional<R>) handler.apply(dialog);
        });
    }
}
