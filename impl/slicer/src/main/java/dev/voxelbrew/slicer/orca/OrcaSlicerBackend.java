package dev.voxelbrew.slicer.orca;

import com.google.gson.*;
import dev.voxelbrew.engine.printing.*;
import dev.voxelbrew.slicer.api.SlicerBackend;
import dev.voxelbrew.slicer.internal.ProcessRunner;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;

/** Generates the actual Bambu package with Orca; never manufactures a ZIP around generic G-code. */
public final class OrcaSlicerBackend implements SlicerBackend {
    private static final Set<Capability> CAPABILITIES =
            Set.of(Capability.AUTOMATIC_SUPPORTS, Capability.BUILD_PLATE_SUPPORTS, Capability.BRIM, Capability.RAFT);
    private final Map<String, String> options;

    public OrcaSlicerBackend() {
        this(Map.of());
    }

    private OrcaSlicerBackend(Map<String, String> options) {
        this.options = Map.copyOf(options);
    }

    @Override
    public Descriptor descriptor() {
        return new Descriptor("orca", "OrcaSlicer (Bambu LAN)", API_VERSION, "2.x", CAPABILITIES, Set.of("gcode"));
    }

    @Override
    public Map<String, String> configurationOptions() {
        return Map.of("executable", "orca-slicer", "machine", "", "process", "", "filament", "");
    }

    @Override
    public SlicerBackend configure(Map<String, String> values) {
        if (!configurationOptions().keySet().containsAll(values.keySet()))
            throw new IllegalArgumentException("Unknown OrcaSlicer option");
        var merged = new HashMap<>(options);
        merged.putAll(values);
        return new OrcaSlicerBackend(merged);
    }

    @Override
    public Path executable() {
        return Path.of(options.getOrDefault("executable", "orca-slicer"));
    }

    @Override
    public InputFormat inputFormat() {
        return new InputFormat("3mf", "3mf");
    }

    private Path preset(String key) throws IOException {
        String value = options.getOrDefault(key, "");
        if (value.isBlank() || !Files.isRegularFile(Path.of(value)))
            throw new IOException("Configure OrcaSlicer " + key + " preset JSON");
        return Path.of(value).toAbsolutePath();
    }

    @Override
    public Probe probe(Path executable, Cancellation cancellation) throws IOException {
        for (String key : List.of("machine", "process", "filament")) preset(key);
        Path directory = Files.createTempDirectory("voxelbrew-orca-probe-");
        Path settings = directory.resolve("settings.json");
        try {
            ProcessRunner.run(
                    List.of(executable.toString(), "--export-settings", settings.toString()),
                    directory,
                    cancellation,
                    line -> {});
            if (!Files.isRegularFile(settings) || Files.size(settings) > 4 * 1024 * 1024)
                throw new IOException("Orca CLI did not export settings");
            JsonObject exported;
            try (var reader = Files.newBufferedReader(settings)) {
                exported = JsonParser.parseReader(reader).getAsJsonObject();
            }
            if (!exported.has("version")
                    || !"2.5.0".equals(exported.get("version").getAsString()))
                throw new IOException("Expected qualified OrcaSlicer 2.5.0");
            return new Probe("OrcaSlicer 2.5.0", CAPABILITIES);
        } finally {
            List<Path> cleanup;
            try (var paths = Files.walk(directory)) {
                cleanup = paths.sorted(Comparator.reverseOrder()).toList();
            }
            for (var path : cleanup) Files.deleteIfExists(path);
        }
    }

    @Override
    public RunResult slice(Request request) throws IOException {
        if (!request.model().setup().supports().isEmpty())
            throw new IOException("Orca support volumes are not supported; remove explicit supports before slicing");
        JsonObject machine;
        try (var reader = Files.newBufferedReader(preset("machine"))) {
            machine = JsonParser.parseReader(reader).getAsJsonObject();
        }
        if (!machine.has("printer_model") || !machine.has("nozzle_diameter"))
            throw new IOException("Export a complete Orca machine preset including printer_model and nozzle_diameter");
        String model = machine.get("printer_model").getAsString();
        if (!model.startsWith("Bambu Lab ")) throw new IOException("Select a Bambu Lab machine preset");
        var nozzles = machine.getAsJsonArray("nozzle_diameter");
        double nozzle = nozzles.get(0).getAsDouble();
        if (Math.abs(nozzle - request.model().profile().printer().nozzleMm()) > 0.0001)
            throw new IOException("Orca machine nozzle differs from selected slicer printer");
        String target = request.model().profile().printer().id();
        if (!normalize(model).equals(normalize(target)))
            throw new IOException("Orca machine model differs from selected slicer printer: " + model);
        var process = request.model().profile().process();
        var overrides = new LinkedHashMap<String, String>();
        overrides.put("layer-height", Double.toString(process.layerHeight()));
        overrides.put("initial-layer-print-height", Double.toString(process.layerHeight()));
        overrides.put("enable-arc-fitting", "0");
        overrides.put("wall-loops", Integer.toString(process.walls()));
        overrides.put("sparse-infill-density", process.infillPercent() + "%");
        overrides.put("enable-support", process.automaticSupports() ? "1" : "0");
        overrides.put("support-on-build-plate-only", process.supportsOnBedOnly() ? "1" : "0");
        overrides.put("support-threshold-angle", Double.toString(process.supportThreshold()));
        overrides.put("support-top-z-distance", Double.toString(process.supportContactGap()));
        overrides.put("support-interface-top-layers", Integer.toString(process.interfaceLayers()));
        overrides.put("brim-width", Double.toString(process.brimWidth()));
        overrides.put("brim-type", process.brimWidth() > 0 ? "outer_only" : "no_brim");
        overrides.put("raft-layers", Integer.toString(process.raftLayers()));
        var material = request.model().profile().material();
        overrides.put("nozzle-temperature", Double.toString(material.nozzleTemperature()));
        overrides.put("nozzle-temperature-initial-layer", Double.toString(material.nozzleTemperature()));
        overrides.put("hot-plate-temp", Double.toString(material.bedTemperature()));
        overrides.put("hot-plate-temp-initial-layer", Double.toString(material.bedTemperature()));
        overrides.put("textured-plate-temp", Double.toString(material.bedTemperature()));
        overrides.put("textured-plate-temp-initial-layer", Double.toString(material.bedTemperature()));
        overrides.put("inner-wall-filament-id", "0");
        overrides.put("outer-wall-filament-id", "0");
        overrides.put("sparse-infill-filament-id", "0");
        overrides.put("internal-solid-filament-id", "0");
        overrides.put("support-filament", "1");
        overrides.put("support-interface-filament", "1");
        overrides.put("enable-prime-tower", "0");
        Path packageFile = request.workingDirectory().resolve("plate.gcode.3mf");
        var command = new ArrayList<>(List.of(
                request.executable().toString(),
                "--load-settings",
                preset("machine") + ";" + preset("process"),
                "--load-filaments",
                preset("filament").toString(),
                "--arrange",
                "0",
                "--orient",
                "0",
                "--slice",
                "1",
                "--export-3mf",
                packageFile.toString()));
        overrides.forEach((key, value) -> command.add("--" + key + "=" + value));
        command.add(request.input().toString());
        ProcessRunner.run(command, request.workingDirectory(), request.cancellation(), request.diagnostics());
        request.cancellation().check();
        if (!Files.isRegularFile(packageFile) || Files.size(packageFile) > 48L * 1024 * 1024)
            throw new IOException("Missing or oversized Orca package");
        try (var zip = new ZipFile(packageFile.toFile())) {
            var entry = zip.getEntry("Metadata/plate_1.gcode");
            if (entry == null || entry.getSize() < 1 || entry.getSize() > 64L * 1024 * 1024)
                throw new IOException("Orca package lacks bounded plate 1 G-code");
            try (var input = zip.getInputStream(entry)) {
                byte[] content = input.readNBytes(64 * 1024 * 1024 + 1);
                if (content.length > 64 * 1024 * 1024) throw new IOException("Oversized packaged G-code");
                Files.write(request.output(), content);
            }
        }
        var metadata = Map.of(
                "model",
                model,
                "printer",
                target,
                "nozzle_mm",
                Double.toString(nozzle),
                "tool",
                "0",
                "backend",
                "orca",
                "revision",
                request.model().revision());
        return new RunResult(overrides, PrintArtifact.of("gcode.3mf", Files.readAllBytes(packageFile), metadata));
    }

    private static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
