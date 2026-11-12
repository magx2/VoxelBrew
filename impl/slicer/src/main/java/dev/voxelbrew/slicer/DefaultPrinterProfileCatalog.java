package dev.voxelbrew.slicer;

import com.google.gson.*;
import dev.voxelbrew.engine.printing.*;
import dev.voxelbrew.slicer.api.PrinterProfileCatalog;
import java.util.*;

/** Validated, reloadable catalog. Saved documents never depend on this service. */
public final class DefaultPrinterProfileCatalog implements PrinterProfileCatalog {
    private final PrinterResources resources;

    public DefaultPrinterProfileCatalog(PrinterResources resources) {
        this.resources = resources;
    }

    @Override
    public String fallbackImage() {
        var url = PrinterResources.class.getResource(PrinterResources.ROOT + "generic.png");
        return url == null ? null : url.toExternalForm();
    }

    public java.nio.file.Path directory() {
        return resources.directory();
    }

    /** Creates editable copies without overwriting existing user presets or images. */
    public int exportDefaults(Set<String> selectedIds) throws java.io.IOException {
        selectedIds = Set.copyOf(selectedIds);
        if (selectedIds.isEmpty()) return 0;
        java.nio.file.Files.createDirectories(directory());
        int exported = 0;
        for (var source : resources.load().sources()) {
            if (!source.bundled()) continue;
            var json = JsonParser.parseString(source.json()).getAsJsonObject();
            if (!selectedIds.contains(json.get("id").getAsString())) continue;
            String stem = "custom-" + json.get("id").getAsString();
            var target = directory().resolve(stem + ".json");
            if (java.nio.file.Files.exists(target)) continue;
            String image = json.has("image") ? json.get("image").getAsString() : null;
            if (image != null) {
                String resource = PrinterResources.image(source, image);
                if (resource != null) {
                    var destination = directory().resolve(stem + ".png");
                    if (!java.nio.file.Files.exists(destination)) {
                        try (var input = java.net.URI.create(resource).toURL().openStream()) {
                            copyNew(input, destination);
                        }
                    }
                }
            }
            json.addProperty("id", stem);
            json.remove("image");
            try (var input = new java.io.ByteArrayInputStream(
                    (new GsonBuilder().setPrettyPrinting().create().toJson(json) + "\n")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                if (copyNew(input, target)) exported++;
            }
        }
        for (var name : List.of("PHOTO-LICENSE.txt", "photo-sources.json")) {
            try (var input = PrinterResources.class.getResourceAsStream(PrinterResources.ROOT + name)) {
                if (input != null) copyNew(input, directory().resolve(name.replace(".json", ".txt")));
            }
        }
        return exported;
    }

    private static boolean copyNew(java.io.InputStream input, java.nio.file.Path target) throws java.io.IOException {
        if (java.nio.file.Files.exists(target)) return false;
        var temporary = java.nio.file.Files.createTempFile(target.getParent(), ".printer-export-", ".tmp");
        try {
            java.nio.file.Files.copy(input, temporary, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            try {
                java.nio.file.Files.move(temporary, target);
                return true;
            } catch (java.nio.file.FileAlreadyExistsException ex) {
                return false;
            }
        } finally {
            java.nio.file.Files.deleteIfExists(temporary);
        }
    }

    public Result load() {
        var loaded = resources.load();
        var errors = new ArrayList<>(loaded.diagnostics());
        var entries = new LinkedHashMap<String, Entry>();
        var duplicateCustom = new HashSet<String>();
        var bundled = new HashSet<String>();
        for (var source : loaded.sources()) {
            try {
                var json = JsonParser.parseString(source.json()).getAsJsonObject();
                if (json.get("version").getAsBigDecimal().intValueExact() != 1)
                    throw new IllegalArgumentException("Unsupported printer JSON version");
                var p = parse(json);
                String id = p.printer().id();
                if (entries.containsKey(id) || duplicateCustom.contains(id)) {
                    if (!bundled.contains(id)) {
                        entries.remove(id);
                        duplicateCustom.add(id);
                    }
                    throw new IllegalArgumentException("Duplicate printer ID: " + id);
                }
                entries.put(
                        id,
                        new Entry(
                                p,
                                PrinterResources.image(
                                        source,
                                        source.bundled() && json.has("image")
                                                ? json.get("image").getAsString()
                                                : null),
                                source.name(),
                                source.bundled()));
                if (source.bundled()) bundled.add(id);
            } catch (RuntimeException ex) {
                errors.add(source.name() + ": " + ex.getMessage());
            }
        }
        return new Result(
                entries.values().stream()
                        .sorted(Comparator.comparing(Entry::bundled).thenComparing(Entry::toString))
                        .toList(),
                errors);
    }

    private static PrinterProfile parse(JsonObject p) {
        var zones = new ArrayList<List<PrintProfile.Point>>();
        for (var zone : p.getAsJsonArray("excludedZones")) zones.add(polygon(zone.getAsJsonArray()));
        return new PrinterProfile(
                new PrintProfile.Printer(
                        p.get("id").getAsString(),
                        p.get("name").getAsString(),
                        p.get("nozzleMm").getAsDouble(),
                        polygon(p.getAsJsonArray("bed")),
                        zones,
                        p.get("usableHeightMm").getAsDouble(),
                        p.get("firmware").getAsString(),
                        p.get("outputFormat").getAsString()),
                p.get("manufacturer").getAsString(),
                p.get("filamentDiameterMm").getAsDouble(),
                p.get("toolCount").getAsBigDecimal().intValueExact(),
                p.get("genericGcode").getAsBoolean(),
                false);
    }

    private static List<PrintProfile.Point> polygon(JsonArray points) {
        if (points.size() > 1024) throw new IllegalArgumentException("Too many polygon points");
        var result = new ArrayList<PrintProfile.Point>();
        for (var point : points) {
            var pair = point.getAsJsonArray();
            if (pair.size() != 2) throw new IllegalArgumentException("Expected [x, y] point");
            result.add(new PrintProfile.Point(
                    pair.get(0).getAsDouble(), pair.get(1).getAsDouble()));
        }
        return List.copyOf(result);
    }
}
