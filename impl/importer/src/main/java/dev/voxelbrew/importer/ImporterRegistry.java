package dev.voxelbrew.importer;

import dev.voxelbrew.importer.api.ImporterDescriptor;
import dev.voxelbrew.importer.api.ImporterProvider;
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
public final class ImporterRegistry implements dev.voxelbrew.importer.api.ImporterCatalog {
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final List<String> diagnostics = new ArrayList<>();
    private final URLClassLoader loader;

    public static Path defaultDirectory() {
        return dev.voxelbrew.util.config.ApplicationPaths.plugins();
    }

    public ImporterRegistry(Path directory) throws IOException {
        this(directory, List.of(new VoxelBrewImporterProvider(), new JsonImporterProvider()));
    }

    public ImporterRegistry(Path directory, java.util.Collection<ImporterProvider> builtins) throws IOException {
        builtins.forEach(this::register);
        var urls = new ArrayList<URL>();
        if (Files.exists(directory)) {
            if (!Files.isDirectory(directory)) throw new IOException("Plugin path is not a directory: " + directory);
            try (var paths = Files.list(directory)) {
                for (var jar : paths.filter(
                                p -> Files.isRegularFile(p) && p.toString().endsWith(".jar"))
                        .sorted()
                        .toList()) urls.add(jar.toUri().toURL());
            }
        }
        loader = new URLClassLoader(urls.toArray(URL[]::new), ImporterProvider.class.getClassLoader());
        var iterator = ServiceLoader.load(ImporterProvider.class, loader).iterator();
        int failures = 0;
        while (true) {
            try {
                if (!iterator.hasNext()) break;
                register(iterator.next());
                failures = 0;
            } catch (ServiceConfigurationError | LinkageError | RuntimeException | AssertionError failure) {
                diagnostics.add("Provider load failed: " + failure);
                if (++failures >= 100) {
                    diagnostics.add("Stopped after 100 consecutive provider failures");
                    break;
                }
            }
        }
        log.debug("Loaded {} importers from {}", entries.size(), directory);
        diagnostics.forEach(message -> log.warn("Importer discovery: {}", message));
    }

    private void register(ImporterProvider provider) {
        var descriptor = provider.descriptor();
        if (descriptor.apiVersion() != ImporterProvider.API_VERSION) {
            diagnostics.add("Incompatible importer " + descriptor.id() + ": API " + descriptor.apiVersion());
            return;
        }
        if (entries.putIfAbsent(descriptor.id(), new Entry(descriptor, provider)) != null)
            diagnostics.add("Duplicate importer ID: " + descriptor.id());
    }

    public List<ImporterDescriptor> descriptors() {
        return entries.values().stream()
                .map(Entry::descriptor)
                .sorted(Comparator.comparing(ImporterDescriptor::id))
                .toList();
    }

    public List<String> diagnostics() {
        return List.copyOf(diagnostics);
    }

    public Entry require(String id) {
        var entry = entries.get(id);
        if (entry == null)
            throw new IllegalArgumentException("Unknown importer '" + id + "'. Available: " + entries.keySet());
        return entry;
    }

    @Override
    public void close() throws IOException {
        loader.close();
    }
}
