package dev.voxelbrew.importer.api;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Frontend-neutral request for settings needed by an importer.
 *
 * <p>Subclass this type to describe an interaction, such as choosing a source, download account
 * or project. Put defaults and available choices in the subclass and use a typed, preferably
 * immutable settings value for the result. The request contains no frontend controls: each host
 * must register a handler that renders or resolves that specific request type.
 *
 * <p>The importer passes the request to {@link ImportWindow#show(ImportDialog)} and reads
 * {@link #getSettings()} only after confirmation. Hosts own the {@link #collectSettings(Supplier)}
 * lifecycle. Instances are mutable and not thread-safe; use one interaction at a time and capture
 * the accepted value before scheduling background work.
 *
 * @param <R> settings accepted by the user or supplied by a headless host
 */
public abstract class ImportDialog<R> {
    private R settings;

    /** Creates a request with no confirmed settings. */
    public ImportDialog() {}

    /**
     * Supplies a human-readable description for the host's dialog or prompt.
     *
     * @return title describing the requested interaction
     */
    public abstract String title();

    /**
     * Returns the settings accepted by the most recent successful interaction.
     *
     * @return accepted value, without copying it
     * @throws IllegalStateException if no interaction has confirmed settings, or the latest
     *     interaction is pending, was cancelled or failed
     */
    public final R getSettings() {
        if (settings == null) throw new IllegalStateException("Import dialog has no confirmed settings");
        return settings;
    }

    /**
     * Runs a host interaction synchronously and records its accepted settings.
     *
     * <p>Custom {@link ImportWindow} implementations must wrap the entire interaction, including
     * handler lookup, in this hook. Previous settings are cleared before invoking the supplier.
     * An empty result means cancellation; a present result becomes available through
     * {@link #getSettings()}. Supplier exceptions propagate unchanged and leave settings unavailable.
     * This method does not perform rendering, validation or thread dispatch itself.
     *
     * @param interaction supplier that obtains validated settings or returns an empty optional
     * @return the supplier's result
     * @throws NullPointerException if the supplier or its returned optional is null
     */
    public final Optional<R> collectSettings(Supplier<Optional<R>> interaction) {
        settings = null;
        var result = Objects.requireNonNull(interaction.get(), "dialog result");
        result.ifPresent(value -> settings = value);
        return result;
    }
}
