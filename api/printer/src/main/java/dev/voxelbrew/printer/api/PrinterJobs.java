package dev.voxelbrew.printer.api;

import dev.voxelbrew.engine.model.CadDocument;
import dev.voxelbrew.engine.printing.PrintArtifact;
import java.util.*;

/** Shared compatibility rules, usable by headless clients and UI. */
public final class PrinterJobs {
    private PrinterJobs() {}

    public static Optional<String> incompatibility(CadDocument document, String plate, PrinterDevice device) {
        if (document.gcode().getOrDefault(plate, List.of()).isEmpty()) return Optional.of("Slice this plate first");
        PrintArtifact artifact = document.printArtifacts().get(plate);
        double nozzle = document.printSetup().printerSnapshot() == null
                ? 0.4
                : document.printSetup().printerSnapshot().printer().nozzleMm();
        if (Math.abs(nozzle - device.nozzleMm()) > 0.0001)
            return Optional.of("Sliced nozzle diameter differs from the configured device");
        if (device.kind() == PrinterDevice.Kind.BAMBU) {
            if (artifact == null
                    || !artifact.format().equals("gcode.3mf")
                    || !"orca".equals(artifact.metadata().get("backend")))
                return Optional.of("Bambu requires an OrcaSlicer package; slice using the matching machine preset");
            if (!normalize(device.model()).equals(normalize(artifact.metadata().getOrDefault("model", ""))))
                return Optional.of("Sliced machine model differs from the selected device");
            if (!"0".equals(artifact.metadata().get("tool")))
                return Optional.of("Only single-material tool 0 jobs are supported");
            if (!Double.toString(device.nozzleMm()).equals(artifact.metadata().get("nozzle_mm")))
                return Optional.of("Package nozzle differs from the device");
        } else if (artifact != null && artifact.format().equals("gcode.3mf"))
            return Optional.of("A Bambu job cannot be sent to Moonraker");
        if (device.kind() == PrinterDevice.Kind.MOONRAKER
                && !device.model().isBlank()
                && !normalize(device.model())
                        .equals(normalize(document.printSetup().printerId())))
            return Optional.of("Slicer printer preset differs from the configured device model");
        return Optional.empty();
    }

    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT)
                .replace("bambu lab", "")
                .replace("bambu-lab-", "")
                .replaceAll("[^a-z0-9]", "");
    }
}
