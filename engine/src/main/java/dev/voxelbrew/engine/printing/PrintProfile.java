package dev.voxelbrew.engine.printing;

import static dev.voxelbrew.engine.units.Measurements.*;

import java.util.List;
import java.util.Objects;
import javax.measure.Quantity;
import javax.measure.quantity.Length;
import javax.measure.quantity.Temperature;

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
        public Quantity<Length> nozzleDiameterQuantity() {
            return quantity(nozzleMm, MILLIMETRE);
        }

        public Quantity<Length> usableHeightQuantity() {
            return quantity(usableHeight, MILLIMETRE);
        }

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
        public Material(
                String id,
                Quantity<Length> diameter,
                Quantity<Temperature> nozzle,
                Quantity<Temperature> bed) {
            this(id, value(diameter, MILLIMETRE), value(nozzle, CELSIUS), value(bed, CELSIUS));
        }

        public Quantity<Length> filamentDiameterQuantity() {
            return quantity(filamentDiameter, MILLIMETRE);
        }

        public Quantity<Temperature> nozzleTemperatureQuantity() {
            return quantity(nozzleTemperature, CELSIUS);
        }

        public Quantity<Temperature> bedTemperatureQuantity() {
            return quantity(bedTemperature, CELSIUS);
        }

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
                    case "PLA" ->
                            new Material(
                                    "PLA",
                                    quantity(1.75, MILLIMETRE),
                                    quantity(210, CELSIUS),
                                    quantity(60, CELSIUS));
                    case "PETG" ->
                            new Material(
                                    "PETG",
                                    quantity(1.75, MILLIMETRE),
                                    quantity(240, CELSIUS),
                                    quantity(80, CELSIUS));
                    default ->
                            throw new IllegalArgumentException(
                                    "Missing material profile: " + setup.materialId());
                };
        return new PrintProfile(printer, material, setup.process());
    }
}
