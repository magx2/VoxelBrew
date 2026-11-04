package dev.voxelbrew.engine.printing;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/** Pinned, fully resolved base profile plus explicit document-owned overrides. */
public final class PrusaConfiguration {
    private PrusaConfiguration() {}

    public static SortedMap<String, String> settings(ManufacturingModel model) throws IOException {
        var result = new TreeMap<String, String>();
        try (var input =
                        Objects.requireNonNull(
                                PrusaConfiguration.class.getResourceAsStream("prusa-2.7.2.ini"));
                var reader =
                        new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null; ) {
                if (line.startsWith("#") || line.startsWith(";") || !line.contains("=")) continue;
                int at = line.indexOf('=');
                result.put(line.substring(0, at).strip(), line.substring(at + 1).strip());
            }
        }
        var profile = model.profile();
        var p = profile.process();
        var material = profile.material();
        result.put(
                "bed_shape",
                profile.printer().bed().stream()
                        .map(v -> v.x() + "x" + v.y())
                        .collect(Collectors.joining(",")));
        result.put("max_print_height", Double.toString(profile.printer().usableHeight()));
        result.put("nozzle_diameter", Double.toString(profile.printer().nozzleMm()));
        result.put("gcode_flavor", profile.printer().firmware());
        result.put("filament_type", material.id());
        result.put("filament_diameter", Double.toString(material.filamentDiameter()));
        result.put("filament_density", material.id().equals("PETG") ? "1.27" : "1.24");
        for (var key : List.of("temperature", "first_layer_temperature"))
            result.put(key, Double.toString(material.nozzleTemperature()));
        for (var key : List.of("bed_temperature", "first_layer_bed_temperature"))
            result.put(key, Double.toString(material.bedTemperature()));
        result.put("layer_height", Double.toString(p.layerHeight()));
        result.put("first_layer_height", Double.toString(p.layerHeight()));
        result.put("perimeters", Integer.toString(p.walls()));
        result.put("fill_density", p.infillPercent() + "%");
        boolean enforce =
                model.setup().supports().stream()
                        .anyMatch(s -> s.role() == SupportVolume.Role.ENFORCER);
        result.put("support_material", p.automaticSupports() || enforce ? "1" : "0");
        result.put("support_material_auto", p.automaticSupports() ? "1" : "0");
        result.put("support_material_buildplate_only", p.supportsOnBedOnly() ? "1" : "0");
        result.put("support_material_threshold", Double.toString(p.supportThreshold()));
        result.put("support_material_contact_distance", Double.toString(p.supportContactGap()));
        result.put(
                "support_material_bottom_contact_distance", Double.toString(p.supportContactGap()));
        result.put("support_material_interface_layers", Integer.toString(p.interfaceLayers()));
        result.put(
                "support_material_bottom_interface_layers", Integer.toString(p.interfaceLayers()));
        result.put("brim_width", Double.toString(p.brimWidth()));
        result.put("raft_layers", Integer.toString(p.raftLayers()));
        result.put("printer_settings_id", profile.printer().id());
        result.put("filament_settings_id", material.id());
        result.put("print_settings_id", "VoxelBrew-explicit-process");
        return Collections.unmodifiableSortedMap(result);
    }

    public static String ini(ManufacturingModel model) throws IOException {
        var out = new StringBuilder("# VoxelBrew / PrusaSlicer 2.7.2 / millimeters\n");
        for (var entry : settings(model).entrySet())
            out.append(entry.getKey()).append(" = ").append(entry.getValue()).append('\n');
        return out.toString();
    }
}
