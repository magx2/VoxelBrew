package dev.voxelbrew.engine.feature;

import dev.voxelbrew.engine.geometry.Solid;
import dev.voxelbrew.engine.model.Feature;
import dev.voxelbrew.engine.model.ModelException;
import java.io.IOException;
import java.net.*;
import java.nio.file.*;
import java.util.*;

/** Isolated class loader for trusted feature JARs; a broken provider does not disable others. */
@lombok.extern.slf4j.Slf4j
public final class FeatureRegistry implements AutoCloseable {
    @FunctionalInterface
    public interface Factory {
        FeatureRegistry open(Path directory) throws IOException;
    }

    private record Entry(FeatureDescriptor descriptor, FeatureProvider provider) {}

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final List<String> diagnostics = new ArrayList<>();
    private final URLClassLoader loader;

    public static Path defaultDirectory() {
        return dev.voxelbrew.util.config.ApplicationPaths.plugins();
    }

    public FeatureRegistry(Path directory) throws IOException {
        var urls = new ArrayList<URL>();
        if (Files.exists(directory)) {
            if (!Files.isDirectory(directory))
                throw new IOException("Plugin path is not a directory: " + directory);
            try (var files = Files.list(directory)) {
                for (var jar :
                        files.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".jar"))
                                .sorted()
                                .toList()) urls.add(jar.toUri().toURL());
            }
        }
        loader =
                new URLClassLoader(
                        urls.toArray(URL[]::new), FeatureProvider.class.getClassLoader());
        var providers = ServiceLoader.load(FeatureProvider.class, loader).iterator();
        int failures = 0;
        while (true) {
            try {
                if (!providers.hasNext()) break;
                var provider = providers.next();
                var descriptor = Objects.requireNonNull(provider.descriptor());
                if (descriptor.apiVersion() != FeatureProvider.API_VERSION)
                    diagnostics.add(
                            "Incompatible feature "
                                    + descriptor.key()
                                    + ": API "
                                    + descriptor.apiVersion());
                else if (entries.putIfAbsent(descriptor.key(), new Entry(descriptor, provider))
                        != null) diagnostics.add("Duplicate feature ID: " + descriptor.key());
                failures = 0;
            } catch (ServiceConfigurationError
                    | LinkageError
                    | RuntimeException
                    | AssertionError failure) {
                diagnostics.add("Feature provider load failed: " + failure);
                if (++failures >= 100) {
                    diagnostics.add("Stopped after 100 consecutive provider failures");
                    break;
                }
            }
        }
        log.debug("Loaded {} feature providers from {}", entries.size(), directory);
        diagnostics.forEach(message -> log.warn("Feature discovery: {}", message));
    }

    public List<FeatureDescriptor> descriptors() {
        return entries.values().stream()
                .map(Entry::descriptor)
                .sorted(Comparator.comparing(FeatureDescriptor::key))
                .toList();
    }

    public List<String> diagnostics() {
        return List.copyOf(diagnostics);
    }

    private Entry require(String provider, String type) {
        var entry = entries.get(provider + "/" + type);
        if (entry == null)
            throw new IllegalArgumentException(
                    "Missing feature provider '"
                            + provider
                            + "/"
                            + type
                            + "'. Install a compatible trusted JAR and rebuild; the saved payload is preserved."
                            + (diagnostics.isEmpty() ? "" : " " + String.join("; ", diagnostics)));
        return entry;
    }

    public FeatureDescriptor descriptor(Feature.Custom feature) {
        return require(feature.providerId(), feature.type()).descriptor();
    }

    public Feature.Custom create(
            String id, String provider, String type, Map<String, Double> values) {
        try {
            var entry = require(provider, type);
            return encoded(id, entry, values);
        } catch (RuntimeException
                | LinkageError
                | ServiceConfigurationError
                | AssertionError failure) {
            throw failure(id, failure);
        }
    }

    private Feature.Custom encoded(String id, Entry entry, Map<String, Double> values) {
        var descriptor = entry.descriptor();
        var normalized = normalize(descriptor, values, true);
        var feature =
                new Feature.Custom(
                        id,
                        descriptor.providerId(),
                        descriptor.type(),
                        descriptor.payloadVersion(),
                        entry.provider().encode(normalized));
        // A provider must be able to reopen exactly the parameters it just encoded.
        if (!normalized.equals(parameters(feature)))
            throw new IllegalArgumentException("Feature codec did not preserve parameters");
        return feature;
    }

    public Map<String, Double> parameters(Feature.Custom feature) {
        try {
            var entry = require(feature.providerId(), feature.type());
            return normalize(
                    entry.descriptor(),
                    entry.provider().decode(feature.payloadVersion(), feature.payload()),
                    false);
        } catch (RuntimeException
                | LinkageError
                | ServiceConfigurationError
                | AssertionError failure) {
            throw failure(feature.id(), failure);
        }
    }

    public Feature.Custom edit(Feature.Custom feature, Map<String, Double> changes) {
        try {
            var values = new LinkedHashMap<>(parameters(feature));
            values.putAll(changes);
            return encoded(feature.id(), require(feature.providerId(), feature.type()), values);
        } catch (RuntimeException
                | LinkageError
                | ServiceConfigurationError
                | AssertionError failure) {
            throw failure(feature.id(), failure);
        }
    }

    public Solid evaluate(Feature.Custom feature) {
        try {
            parameters(feature); // Validate version, payload and field ranges before native work.
            var solid =
                    Objects.requireNonNull(
                            require(feature.providerId(), feature.type())
                                    .provider()
                                    .evaluate(feature.payloadVersion(), feature.payload()),
                            "Provider returned no solid");
            try {
                double volume = solid.volume();
                if (!Double.isFinite(volume) || volume <= 0)
                    throw new IllegalArgumentException("Provider returned an invalid volume");
                return solid;
            } catch (RuntimeException
                    | LinkageError
                    | ServiceConfigurationError
                    | AssertionError failure) {
                solid.close();
                throw failure;
            }
        } catch (RuntimeException
                | LinkageError
                | ServiceConfigurationError
                | AssertionError failure) {
            throw failure(feature.id(), failure);
        }
    }

    private static Map<String, Double> normalize(
            FeatureDescriptor descriptor, Map<String, Double> values, boolean defaults) {
        var result = new LinkedHashMap<String, Double>();
        for (var parameter : descriptor.parameters()) {
            Double value = values.get(parameter.key());
            if (value == null && defaults && !values.containsKey(parameter.key()))
                value = parameter.defaultValue();
            if (value == null)
                throw new IllegalArgumentException("Missing parameter " + parameter.key());
            parameter.validate(value);
            result.put(parameter.key(), value);
        }
        for (var key : values.keySet())
            if (!result.containsKey(key))
                throw new IllegalArgumentException("Unknown parameter " + key);
        return Collections.unmodifiableMap(result);
    }

    private static ModelException failure(String id, Throwable failure) {
        return failure instanceof ModelException model
                ? model
                : new ModelException(id, "Feature provider: " + failure);
    }

    @Override
    public void close() throws IOException {
        loader.close();
    }
}
