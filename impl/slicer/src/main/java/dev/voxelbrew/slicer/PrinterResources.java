package dev.voxelbrew.slicer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Bundled and custom printer resources owned by the slicer runtime. */
public final class PrinterResources {
    public static final String ROOT = "/dev/voxelbrew/slicer/printers/";

    public record Source(String name, String json, Path directory, boolean bundled) {}

    public record Result(List<Source> sources, List<String> diagnostics) {
        public Result {
            sources = List.copyOf(sources);
            diagnostics = List.copyOf(diagnostics);
        }
    }

    private final Path directory;

    public PrinterResources(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
    }

    public Path directory() {
        return directory;
    }

    public Result load() {
        var sources = new ArrayList<Source>();
        var diagnostics = new ArrayList<String>();
        try (var input = PrinterResources.class.getResourceAsStream(ROOT + "index.txt")) {
            if (input == null) throw new IOException("Missing bundled printer index");
            for (String name : new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .toList()) {
                if (name.isBlank()) continue;
                try (var preset = PrinterResources.class.getResourceAsStream(ROOT + name)) {
                    if (preset == null) throw new IOException("Missing resource " + name);
                    sources.add(
                            new Source(name, new String(preset.readAllBytes(), StandardCharsets.UTF_8), null, true));
                } catch (IOException ex) {
                    diagnostics.add(name + ": " + ex.getMessage());
                }
            }
        } catch (IOException ex) {
            diagnostics.add(ex.getMessage());
        }
        if (Files.exists(directory)) {
            try (var files = Files.list(directory)) {
                for (var file : files.filter(p -> p.getFileName().toString().endsWith(".json"))
                        .sorted()
                        .toList()) {
                    try {
                        if (Files.size(file) > 1024 * 1024) throw new IOException("Preset exceeds 1 MiB");
                        sources.add(
                                new Source(file.getFileName().toString(), Files.readString(file), directory, false));
                    } catch (IOException ex) {
                        diagnostics.add(file + ": " + ex.getMessage());
                    }
                }
            } catch (IOException ex) {
                diagnostics.add(directory + ": " + ex.getMessage());
            }
        }
        return new Result(sources, diagnostics);
    }

    public static String image(Source source, String name) {
        if (source.bundled()) {
            if (name == null || !name.matches("[a-z0-9-]+\\.png")) return null;
            var url = PrinterResources.class.getResource(ROOT + name);
            return url == null ? null : url.toExternalForm();
        }
        if (!source.name().endsWith(".json")) return null;
        String stem = source.name().substring(0, source.name().length() - ".json".length());
        var extensions = List.of("png", "jpg", "jpeg", "webp", "gif", "bmp");
        try (var files = Files.list(source.directory())) {
            var candidates = files.filter(path -> {
                        String filename = path.getFileName().toString();
                        int dot = filename.lastIndexOf('.');
                        return dot >= 0
                                && filename.substring(0, dot).equals(stem)
                                && extensions.contains(
                                        filename.substring(dot + 1).toLowerCase(Locale.ROOT));
                    })
                    .sorted(Comparator.<Path>comparingInt(path -> {
                                String filename = path.getFileName().toString();
                                return extensions.indexOf(filename.substring(filename.lastIndexOf('.') + 1)
                                        .toLowerCase(Locale.ROOT));
                            })
                            .thenComparing(path -> path.getFileName().toString()))
                    .toList();
            for (var path : candidates) {
                try {
                    if (Files.isRegularFile(path)
                            && Files.isReadable(path)
                            && path.toRealPath().startsWith(source.directory().toRealPath())
                            && Files.size(path) <= 5 * 1024 * 1024)
                        return path.toUri().toString();
                } catch (IOException ignored) {
                    // A removed or inaccessible candidate must not hide another supported image.
                }
            }
        } catch (IOException | RuntimeException ex) {
            return null;
        }
        return null;
    }
}
