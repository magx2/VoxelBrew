package dev.voxelbrew.exporter.api;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** Frontend-neutral interaction with exporter-defined settings.
 * Each frontend registers a handler for the dialog type with its window host.
 * Use a dialog instance for one interaction at a time.
 */
public abstract class ExportDialog<R> {
    private R settings;

    public abstract String title();

    /** Returns confirmed settings; unavailable before confirmation or after cancellation/failure. */
    public final R getSettings() {
        if (settings == null) throw new IllegalStateException("Export dialog has no confirmed settings");
        return settings;
    }

    /** Host lifecycle hook. Clears previous settings before rendering or collecting CLI arguments. */
    public final Optional<R> collectSettings(Supplier<Optional<R>> interaction) {
        settings = null;
        var result = Objects.requireNonNull(interaction.get(), "dialog result");
        result.ifPresent(value -> settings = value);
        return result;
    }
}
