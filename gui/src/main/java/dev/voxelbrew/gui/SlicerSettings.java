package dev.voxelbrew.gui;

import dev.voxelbrew.slicer.api.SlicerBackend;
import dev.voxelbrew.slicer.api.SlicerCatalog;
import dev.voxelbrew.util.config.UserConfiguration;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Persistent provider settings; each invocation receives a new configured backend. */
@javax.inject.Singleton
@lombok.RequiredArgsConstructor(onConstructor_ = @javax.inject.Inject)
final class SlicerSettings {
    private final SlicerCatalog catalog;
    private final UserConfiguration configuration;

    Map<String, String> options(String id) throws IOException {
        var values = new TreeMap<>(catalog.require(id).configurationOptions());
        var saved = configuration.read();
        values.replaceAll((key, fallback) -> saved.getOrDefault("slicer." + id + "." + key, fallback));
        return Map.copyOf(values);
    }

    SlicerBackend backend(String id) throws IOException {
        return catalog.require(id).configure(options(id));
    }

    void save(Map<String, Map<String, String>> providers) throws IOException {
        var values = new TreeMap<String, String>();
        for (var entry : providers.entrySet()) {
            catalog.require(entry.getKey()).configure(entry.getValue());
            entry.getValue().forEach((key, value) -> values.put("slicer." + entry.getKey() + "." + key, value));
        }
        configuration.update(values, Set.of());
    }
}
