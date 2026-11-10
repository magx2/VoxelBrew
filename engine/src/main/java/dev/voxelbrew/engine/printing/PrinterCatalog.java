package dev.voxelbrew.engine.printing;

import com.google.gson.*;
import dev.voxelbrew.util.config.PrinterResources;
import java.util.*;

/** Validated, reloadable catalog. Saved documents never depend on this service. */
public final class PrinterCatalog {
    public record Entry(PrinterDefinition definition, String image, String source) {
        @Override
        public String toString() {
            return definition.manufacturer() + " " + definition.printer().name();
        }
    }

    public record Result(List<Entry> entries, List<String> diagnostics) {
        public Result {
            entries = List.copyOf(entries);
            diagnostics = List.copyOf(diagnostics);
        }

        public PrinterDefinition require(String id) {
            return entries.stream()
                    .filter(e -> e.definition().printer().id().equals(id))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Missing printer preset: " + id))
                    .definition();
        }
    }

    private final PrinterResources resources;

    public PrinterCatalog(PrinterResources resources) {
        this.resources = resources;
    }

    public java.nio.file.Path directory() {
        return resources.directory();
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
                                source.name()));
                if (source.bundled()) bundled.add(id);
            } catch (RuntimeException ex) {
                errors.add(source.name() + ": " + ex.getMessage());
            }
        }
        return new Result(
                entries.values().stream()
                        .sorted(Comparator.comparing(Entry::toString))
                        .toList(),
                errors);
    }

    private static PrinterDefinition parse(JsonObject p) {
        var zones = new ArrayList<List<PrintProfile.Point>>();
        for (var zone : p.getAsJsonArray("excludedZones")) zones.add(polygon(zone.getAsJsonArray()));
        return new PrinterDefinition(
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
