package dev.voxelbrew.exporter.printing;

import dev.voxelbrew.engine.printing.*;
import dev.voxelbrew.exporter.api.printing.SlicerBackend;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

/** Trusted provider JARs use a dedicated loader; one bad provider does not disable its peers. */
@lombok.extern.slf4j.Slf4j
public final class SlicerRegistry implements AutoCloseable {
    @FunctionalInterface
    public interface Factory {
        SlicerRegistry open(Path directory) throws IOException;
    }

    private final URLClassLoader loader;

    private record Entry(SlicerBackend provider, SlicerBackend.Descriptor descriptor) {}

    private final Map<String, Entry> providers = new LinkedHashMap<>();
    private final List<String> diagnostics = new ArrayList<>();

    public SlicerRegistry(Path directory) throws IOException {
        var urls = new ArrayList<URL>();
        if (Files.isDirectory(directory))
            try (var files = Files.list(directory)) {
                for (var file : files.filter(p -> p.getFileName().toString().endsWith(".jar"))
                        .sorted()
                        .toList()) urls.add(file.toUri().toURL());
            }
        loader = new URLClassLoader(urls.toArray(URL[]::new), SlicerBackend.class.getClassLoader());
        var services = ServiceLoader.load(SlicerBackend.class, loader).iterator();
        int attempts = 0;
        while (attempts++ < 1000) {
            try {
                if (!services.hasNext()) break;
                var backend = services.next();
                var descriptor = backend.descriptor();
                if (descriptor.apiVersion() != SlicerBackend.API_VERSION) {
                    diagnostics.add("Incompatible slicer " + descriptor.id() + ": API " + descriptor.apiVersion());
                    continue;
                }
                if (!descriptor.id().matches("[a-z0-9][a-z0-9.-]*")) {
                    diagnostics.add("Invalid slicer ID: " + descriptor.id());
                    continue;
                }
                if (providers.putIfAbsent(descriptor.id(), new Entry(backend, descriptor)) != null)
                    diagnostics.add("Duplicate slicer ID: " + descriptor.id());
            } catch (ServiceConfigurationError | LinkageError | RuntimeException failure) {
                diagnostics.add("Slicer provider failed: " + failure);
            }
        }
        log.debug("Loaded {} slicers from {}", providers.size(), directory);
        diagnostics.forEach(message -> log.warn("Slicer discovery: {}", message));
    }

    public List<SlicerBackend.Descriptor> descriptors() {
        return providers.values().stream().map(Entry::descriptor).toList();
    }

    public List<String> diagnostics() {
        return List.copyOf(diagnostics);
    }

    public SlicerBackend require(String id) {
        var backend = providers.get(id);
        if (backend == null) throw new IllegalArgumentException("Slicer backend unavailable: " + id);
        return backend.provider();
    }

    @Override
    public void close() throws IOException {
        loader.close();
    }
}
