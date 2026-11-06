package dev.voxelbrew.exporter;

import dev.voxelbrew.exporter.api.ExporterDescriptor;
import dev.voxelbrew.exporter.api.ModelExporter;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/** Loads trusted JARs only. Provider errors are isolated and exposed as diagnostics. */
@lombok.extern.slf4j.Slf4j
public final class ExporterRegistry implements AutoCloseable {
    @FunctionalInterface
    public interface Factory {
        ExporterRegistry open(Path directory) throws IOException;
    }

    public record Entry(ExporterDescriptor descriptor, ModelExporter provider) {}

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final List<String> diagnostics = new ArrayList<>();
    private final URLClassLoader loader;

    public static Path defaultDirectory() {
        return dev.voxelbrew.util.config.ApplicationPaths.plugins();
    }

    public ExporterRegistry(Path directory) throws IOException {
        var urls = new ArrayList<URL>();
        if (Files.exists(directory)) {
            if (!Files.isDirectory(directory))
                throw new IOException("Plugin path is not a directory: " + directory);
            try (var paths = Files.list(directory)) {
                for (var jar :
                        paths.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".jar"))
                                .sorted()
                                .toList()) urls.add(jar.toUri().toURL());
            }
        }
        loader = new URLClassLoader(urls.toArray(URL[]::new), ModelExporter.class.getClassLoader());
        var iterator = ServiceLoader.load(ModelExporter.class, loader).iterator();
        int failures = 0;
        while (true) {
            try {
                if (!iterator.hasNext()) break;
                register(iterator.next());
                failures = 0;
            } catch (ServiceConfigurationError
                    | LinkageError
                    | RuntimeException
                    | AssertionError failure) {
                diagnostics.add("Provider load failed: " + failure);
                if (++failures >= 100) {
                    diagnostics.add("Stopped after 100 consecutive provider failures");
                    break;
                }
            }
        }
        log.debug("Loaded {} exporters from {}", entries.size(), directory);
        diagnostics.forEach(message -> log.warn("Exporter discovery: {}", message));
    }

    private void register(ModelExporter provider) {
        var descriptor = provider.descriptor();
        if (descriptor.apiVersion() != ModelExporter.API_VERSION) {
            diagnostics.add(
                    "Incompatible exporter "
                            + descriptor.id()
                            + ": API "
                            + descriptor.apiVersion());
            return;
        }
        if (entries.putIfAbsent(descriptor.id(), new Entry(descriptor, provider)) != null)
            diagnostics.add("Duplicate exporter ID: " + descriptor.id());
    }

    public List<ExporterDescriptor> descriptors() {
        return entries.values().stream()
                .map(Entry::descriptor)
                .sorted(Comparator.comparing(ExporterDescriptor::id))
                .toList();
    }

    public List<String> diagnostics() {
        return List.copyOf(diagnostics);
    }

    public Entry require(String id) {
        var entry = entries.get(id);
        if (entry == null)
            throw new IllegalArgumentException(
                    "Unknown exporter '" + id + "'. Available: " + entries.keySet());
        return entry;
    }

    @Override
    public void close() throws IOException {
        loader.close();
    }
}
