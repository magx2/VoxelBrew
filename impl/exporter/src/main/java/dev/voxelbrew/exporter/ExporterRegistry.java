package dev.voxelbrew.exporter;

import dev.voxelbrew.exporter.api.*;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

/** Runtime providers from trusted JARs, with Dagger-supplied built-ins and isolated diagnostics. */
@lombok.extern.slf4j.Slf4j
public final class ExporterRegistry implements dev.voxelbrew.exporter.api.ExporterCatalog {
    private record Jar(Path path, long size, java.nio.file.attribute.FileTime modified) {}

    private final Path directory;
    private final List<ExporterProvider> builtins;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    private final List<String> diagnostics = new ArrayList<>();
    // Retain earlier generations until window shutdown; in-flight exports may still use them.
    private final List<URLClassLoader> loaders = new ArrayList<>();
    private List<Jar> loadedJars;
    private boolean closed;

    public static Path defaultDirectory() {
        return dev.voxelbrew.util.config.ApplicationPaths.plugins();
    }

    public Path directory() {
        return directory;
    }

    /** Standalone composition; application graphs supply built-ins using Dagger multibindings. */
    public ExporterRegistry(Path directory) throws IOException {
        this(
                directory,
                List.of(
                        new VoxelBrewExporterProvider(),
                        new JsonExporterProvider(),
                        new GcodeExporterProvider(),
                        new PrintPackageExporterProvider(),
                        new StepExporterProvider(),
                        new GeometryExporterProvider(
                                new ExporterDescriptor("stl", "Binary STL"), BinaryStlExporter::new),
                        new GeometryExporterProvider(
                                new ExporterDescriptor("3mf", "Portable 3MF Core"), ThreeMfExporter::new),
                        new GeometryExporterProvider(new ExporterDescriptor("obj", "Wavefront OBJ"), ObjExporter::new),
                        new GeometryExporterProvider(
                                new ExporterDescriptor("prusa-project", "PrusaSlicer project (3MF)"),
                                dev.voxelbrew.exporter.printing.PrusaProjectExporter::new)));
    }

    public ExporterRegistry(Path directory, Collection<ExporterProvider> builtins) throws IOException {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.builtins = List.copyOf(builtins);
        refresh();
    }

    /** Discovers new JARs when File > Export opens. No application rebuild is needed. */
    public synchronized void refresh() throws IOException {
        if (closed) throw new IllegalStateException("Exporter registry is closed");
        var jars = new ArrayList<Jar>();
        if (Files.exists(directory)) {
            if (!Files.isDirectory(directory)) throw new IOException("Plugin path is not a directory: " + directory);
            try (var paths = Files.list(directory)) {
                for (var jar : paths.filter(
                                p -> Files.isRegularFile(p) && p.toString().endsWith(".jar"))
                        .sorted()
                        .toList()) {
                    jars.add(new Jar(jar, Files.size(jar), Files.getLastModifiedTime(jar)));
                }
            }
        }
        if (jars.equals(loadedJars)) return;
        var urls = new ArrayList<URL>();
        for (var jar : jars) urls.add(jar.path().toUri().toURL());
        var loader = new URLClassLoader(urls.toArray(URL[]::new), ExporterProvider.class.getClassLoader());
        loaders.add(loader);
        entries.clear();

        diagnostics.clear();
        for (var provider : builtins) {
            try {
                register(provider);
            } catch (RuntimeException | LinkageError | AssertionError failure) {
                diagnostics.add("Built-in exporter failed: " + failure);
            }
        }
        discover(ExporterProvider.class, loader, this::register);

        loadedJars = List.copyOf(jars);
        diagnostics.forEach(message -> log.warn("Exporter discovery: {}", message));
    }

    private <P> void discover(Class<P> type, ClassLoader loader, Consumer<P> register) {
        var iterator = ServiceLoader.load(type, loader).iterator();
        int failures = 0;
        while (true) {
            try {
                if (!iterator.hasNext()) break;
                register.accept(iterator.next());
                failures = 0;
            } catch (ServiceConfigurationError | LinkageError | RuntimeException | AssertionError failure) {
                diagnostics.add("Provider load failed: " + failure);
                if (++failures >= 100) {
                    diagnostics.add("Stopped after 100 consecutive provider failures");
                    break;
                }
            }
        }
    }

    private void register(ExporterProvider provider) {
        var descriptor = Objects.requireNonNull(provider.descriptor(), "descriptor");
        if (entries.putIfAbsent(descriptor.id(), new Entry(descriptor, provider)) != null)
            diagnostics.add("Duplicate exporter ID: " + descriptor.id());
    }

    public synchronized List<ExporterDescriptor> descriptors() {
        return entries.values().stream()
                .map(Entry::descriptor)
                .sorted(Comparator.comparing(ExporterDescriptor::id))
                .toList();
    }

    public synchronized boolean contains(String id) {
        return entries.containsKey(id);
    }

    public synchronized List<String> diagnostics() {
        return List.copyOf(diagnostics);
    }

    public synchronized Entry require(String id) {
        var entry = entries.get(id);
        if (entry == null)
            throw new IllegalArgumentException("Unknown exporter '" + id + "'. Available: " + entries.keySet());
        return entry;
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        IOException failure = null;
        for (var loader : loaders) {
            try {
                loader.close();
            } catch (IOException error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
    }
}
