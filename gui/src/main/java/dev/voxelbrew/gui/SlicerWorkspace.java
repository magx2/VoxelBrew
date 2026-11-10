package dev.voxelbrew.gui;

import java.util.*;
import java.util.function.*;

/** Captures editable tab state separately from the applied manufacturing definition. */
final class SlicerWorkspace {
    private record Value(Supplier<String> read, Consumer<String> write) {}

    private final Map<String, Value> values = new LinkedHashMap<>();
    private Map<String, String> defaults = Map.of();
    private Map<String, String> baseline = Map.of();
    private Map<String, String> saved = Map.of();
    private Object documentIdentity;

    void add(String key, Supplier<String> read, Consumer<String> write) {
        values.put(key, new Value(read, write));
    }

    void initialize() {
        defaults = read();
        baseline = defaults;
    }

    Map<String, String> capture() {
        var current = read();
        return current.equals(baseline) && saved.isEmpty() ? saved : current;
    }

    boolean belongsTo(Object identity) {
        return documentIdentity == identity;
    }

    Map<String, String> pending(Object identity) {
        if (identity != documentIdentity) {
            write(defaults);
            return Map.of();
        }
        var edits = new LinkedHashMap<String, String>();
        read().forEach((key, value) -> {
            if (!Objects.equals(value, baseline.get(key))) edits.put(key, value);
        });
        return edits;
    }

    void restore(Object identity, Map<String, String> settings, Map<String, String> pending) {
        if (identity != documentIdentity || !settings.equals(saved)) {
            write(settings);
            saved = Map.copyOf(settings);
            documentIdentity = identity;
        }
        baseline = read();
        write(pending);
    }

    private Map<String, String> read() {
        var result = new TreeMap<String, String>(saved);
        values.forEach(
                (key, value) -> result.put(key, Objects.toString(value.read().get(), "")));
        return Map.copyOf(result);
    }

    private void write(Map<String, String> settings) {
        settings.forEach((key, value) -> {
            var target = values.get(key);
            if (target != null) target.write().accept(value);
        });
    }
}
