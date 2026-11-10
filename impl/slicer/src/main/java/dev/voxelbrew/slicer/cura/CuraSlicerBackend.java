package dev.voxelbrew.slicer.cura;

import dev.voxelbrew.engine.printing.*;
import dev.voxelbrew.slicer.api.SlicerBackend;
import dev.voxelbrew.slicer.internal.ProcessRunner;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Headless CuraEngine adapter using matching, user-supplied machine definitions. */
public final class CuraSlicerBackend implements SlicerBackend {
    private final Map<String, String> configuration;

    public CuraSlicerBackend() {
        this(Map.of());
    }

    private CuraSlicerBackend(Map<String, String> configuration) {
        this.configuration = Map.copyOf(configuration);
    }

    @Override
    public Descriptor descriptor() {
        return new Descriptor("cura", "CuraEngine", API_VERSION, "5.x", Set.of(), Set.of("gcode"));
    }

    @Override
    public Map<String, String> configurationOptions() {
        return Map.of("executable", "CuraEngine", "definition", "", "settings", "");
    }

    @Override
    public SlicerBackend configure(Map<String, String> options) {
        if (!configurationOptions().keySet().containsAll(options.keySet()))
            throw new IllegalArgumentException("Unknown Cura option");
        var merged = new TreeMap<>(configuration);
        merged.putAll(options);
        return new CuraSlicerBackend(merged);
    }

    @Override
    public Path executable() {
        String value = configuration.getOrDefault("executable", "CuraEngine");
        return Path.of(value.isBlank() ? "CuraEngine" : value);
    }

    @Override
    public Probe probe(Path executable, Cancellation cancellation) throws IOException {
        String definition = configuration.getOrDefault("definition", "");
        if (definition.isBlank() || !Files.isRegularFile(Path.of(definition)))
            throw new IOException("Configure slicer.cura.definition with a CuraEngine definition JSON file");
        String settings = configuration.getOrDefault("settings", "");
        if (!settings.isBlank() && !Files.isReadable(Path.of(settings)))
            throw new IOException("Cannot read CuraEngine settings: " + settings);
        String output = ProcessRunner.run(List.of(executable.toString(), "help"), null, cancellation, line -> {});
        var version = java.util.regex.Pattern.compile("Cura_SteamEngine version (5\\.[^\\s]+)")
                .matcher(output);
        if (!version.find()) throw new IOException("Expected CuraEngine 5.x; executable reported: " + output);
        return new Probe("CuraEngine " + version.group(1), Set.of());
    }

    Map<String, String> settings(ManufacturingModel model) throws IOException {
        var profile = model.profile();
        var process = profile.process();
        if (!profile.printer().firmware().equals("marlin2")
                || !profile.printer().outputFormat().equals("gcode"))
            throw new IOException("Cura adapter requires Marlin 2 text G-code");
        if (!model.setup().supports().isEmpty()
                || process.automaticSupports()
                || process.brimWidth() > 0
                || process.raftLayers() > 0)
            throw new IOException("Cura support volumes, automatic supports, brim and raft are not yet supported");
        var result = new TreeMap<String, String>();
        String settings = configuration.getOrDefault("settings", "");
        if (!settings.isBlank()) {
            var properties = new Properties();
            try (var reader = Files.newBufferedReader(Path.of(settings))) {
                properties.load(reader);
            }
            properties.forEach((key, value) -> result.put(key.toString(), value.toString()));
        }
        result.put("machine_gcode_flavor", "RepRap (Marlin/Sprinter)");
        result.put("machine_extruder_count", "1");
        result.put("machine_nozzle_size", Double.toString(profile.printer().nozzleMm()));
        for (var key : List.of(
                "line_width",
                "wall_line_width",
                "wall_line_width_0",
                "wall_line_width_x",
                "infill_line_width",
                "skin_line_width"))
            result.put(key, Double.toString(profile.printer().nozzleMm()));
        result.put("material_diameter", Double.toString(profile.material().filamentDiameter()));
        result.put(
                "material_print_temperature", Double.toString(profile.material().nozzleTemperature()));
        result.put(
                "material_print_temperature_layer_0",
                Double.toString(profile.material().nozzleTemperature()));
        result.put(
                "material_bed_temperature", Double.toString(profile.material().bedTemperature()));
        result.put(
                "material_bed_temperature_layer_0",
                Double.toString(profile.material().bedTemperature()));
        result.put("layer_height", Double.toString(process.layerHeight()));
        result.put("layer_height_0", Double.toString(process.layerHeight()));
        result.put("wall_line_count", Integer.toString(process.walls()));
        result.put("infill_sparse_density", Double.toString(process.infillPercent()));
        result.put("infill_pattern", "lines");
        result.put(
                "infill_line_distance",
                process.infillPercent() == 0
                        ? "0"
                        : Double.toString(profile.printer().nozzleMm() * 100 / process.infillPercent()));
        result.put("support_enable", "false");
        result.put("adhesion_type", "none");
        result.put("mesh_position_x", "0");
        result.put("mesh_position_y", "0");
        result.put("mesh_position_z", "0");
        result.put("center_object", "false");
        return Collections.unmodifiableMap(result);
    }

    @Override
    public RunResult slice(Request request) throws IOException {
        String definition = configuration.getOrDefault("definition", "");
        if (definition.isBlank() || !Files.isRegularFile(Path.of(definition)))
            throw new IOException("Configure slicer.cura.definition with a CuraEngine definition JSON file");
        var settings = settings(request.model());
        var arguments = new ArrayList<>(List.of(
                request.executable().toString(),
                "slice",
                "-j",
                Path.of(definition).toAbsolutePath().toString()));
        settings.forEach((key, value) -> arguments.addAll(List.of("-s", key + "=" + value)));
        arguments.addAll(
                List.of("-o", request.output().toString(), "-l", request.input().toString()));
        ProcessRunner.run(arguments, request.workingDirectory(), request.cancellation(), request.diagnostics());
        return new RunResult(settings);
    }
}
