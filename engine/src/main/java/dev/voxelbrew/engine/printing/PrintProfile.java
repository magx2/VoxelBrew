package dev.voxelbrew.engine.printing;

import java.util.List;
import java.util.Objects;

/** Fully resolved, frontend-neutral printer/nozzle/material/process contract. */
public record PrintProfile(Printer printer, Material material, ProcessSettings process) {
    public record Point(double x, double y) {
        public Point {
            if (!Double.isFinite(x) || !Double.isFinite(y))
                throw new IllegalArgumentException("nonfinite bed point");
        }
    }

    public record Printer(
            String id,
            String name,
            double nozzleMm,
            List<Point> bed,
            List<List<Point>> excludedZones,
            double usableHeight,
            String firmware,
            String outputFormat) {
        public Printer {
            Objects.requireNonNull(id);
            Objects.requireNonNull(name);
            Objects.requireNonNull(firmware);
            Objects.requireNonNull(outputFormat);
            bed = List.copyOf(bed);
            excludedZones = excludedZones.stream().map(List::copyOf).toList();
            if (bed.size() < 3 || excludedZones.stream().anyMatch(p -> p.size() < 3))
                throw new IllegalArgumentException(
                        "bed and exclusion polygons require at least three points");
            if (!Double.isFinite(nozzleMm)
                    || nozzleMm <= 0
                    || !Double.isFinite(usableHeight)
                    || usableHeight <= 0)
                throw new IllegalArgumentException("invalid nozzle or build height");
        }
    }

    public record Material(
            String id, double filamentDiameter, double nozzleTemperature, double bedTemperature) {
        public Material {
            Objects.requireNonNull(id);
            if (id.isBlank()
                    || !Double.isFinite(filamentDiameter)
                    || filamentDiameter <= 0
                    || !Double.isFinite(nozzleTemperature)
                    || nozzleTemperature <= 0
                    || !Double.isFinite(bedTemperature)
                    || bedTemperature < 0)
                throw new IllegalArgumentException("Invalid material profile");
        }
    }

    public PrintProfile {
        Objects.requireNonNull(printer);
        Objects.requireNonNull(material);
        Objects.requireNonNull(process);
    }

    public static PrintProfile resolve(PrintSetup setup) {
        if (!setup.printerId().equals("generic-marlin-200-0.4"))
            throw new IllegalArgumentException("Missing printer profile: " + setup.printerId());
        var printer =
                new Printer(
                        setup.printerId(),
                        "Generic Marlin 200 × 200 × 200 mm / 0.4 mm",
                        0.4,
                        List.of(
                                new Point(0, 0),
                                new Point(200, 0),
                                new Point(200, 200),
                                new Point(0, 200)),
                        List.of(),
                        200,
                        "marlin2",
                        "gcode");
        var material =
                switch (setup.materialId()) {
                    case "PLA" -> new Material("PLA", 1.75, 210, 60);
                    case "PETG" -> new Material("PETG", 1.75, 240, 80);
                    default ->
                            throw new IllegalArgumentException(
                                    "Missing material profile: " + setup.materialId());
                };
        return new PrintProfile(printer, material, setup.process());
    }
}
