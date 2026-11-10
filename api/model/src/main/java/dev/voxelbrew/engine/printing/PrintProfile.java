package dev.voxelbrew.engine.printing;

import static dev.voxelbrew.engine.units.Measurements.*;

import java.util.List;
import javax.measure.Quantity;
import javax.measure.quantity.Length;
import javax.measure.quantity.Temperature;
import lombok.NonNull;

/** Fully resolved, frontend-neutral printer/nozzle/material/process contract. */
public record PrintProfile(
        @NonNull Printer printer,
        @NonNull Material material,
        @NonNull ProcessSettings process) {
    public record Point(double x, double y) {
        public Point {
            if (!Double.isFinite(x) || !Double.isFinite(y)) throw new IllegalArgumentException("nonfinite bed point");
        }
    }

    public record Printer(
            @NonNull String id,
            @NonNull String name,
            double nozzleMm,
            List<Point> bed,
            List<List<Point>> excludedZones,
            double usableHeight,
            @NonNull String firmware,
            @NonNull String outputFormat) {
        public Quantity<Length> nozzleDiameterQuantity() {
            return quantity(nozzleMm, MILLIMETRE);
        }

        public Quantity<Length> usableHeightQuantity() {
            return quantity(usableHeight, MILLIMETRE);
        }

        public Printer {

            bed = List.copyOf(bed);
            excludedZones = excludedZones.stream().map(List::copyOf).toList();
            if (bed.size() < 3 || excludedZones.stream().anyMatch(p -> p.size() < 3))
                throw new IllegalArgumentException("bed and exclusion polygons require at least three points");
            if (!Double.isFinite(nozzleMm) || nozzleMm <= 0 || !Double.isFinite(usableHeight) || usableHeight <= 0)
                throw new IllegalArgumentException("invalid nozzle or build height");
        }
    }

    public record Material(
            @NonNull String id, double filamentDiameter, double nozzleTemperature, double bedTemperature) {
        public Material(String id, Quantity<Length> diameter, Quantity<Temperature> nozzle, Quantity<Temperature> bed) {
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

            if (id.isBlank()
                    || !Double.isFinite(filamentDiameter)
                    || filamentDiameter <= 0
                    || !Double.isFinite(nozzleTemperature)
                    || nozzleTemperature <= 0
                    || !Double.isFinite(bedTemperature)
                    || bedTemperature < 0) throw new IllegalArgumentException("Invalid material profile");
        }
    }

    public PrintProfile {}

    /** Stable profile identity preserving a custom rectangular build volume in the document. */
    public static String rectangularPrinterId(double width, double depth, double height) {
        for (double value : new double[] {width, depth, height})
            if (!Double.isFinite(value) || value <= 0 || value > 10000)
                throw new IllegalArgumentException("Build dimensions must be greater than 0 and at most 10000 mm");
        return "generic-marlin-rect-" + width + "x" + depth + "x" + height + "-0.4";
    }

    public static PrintProfile resolve(PrintSetup setup) {
        if (setup.printerSnapshot() != null) {
            var snapshot = setup.printerSnapshot();
            var legacy = resolve(PrintSetup.empty()
                    .withProfile(setup.backendId(), "generic-marlin-200-0.4", setup.materialId(), setup.process()));
            return new PrintProfile(
                    snapshot.printer(),
                    new Material(
                            legacy.material().id(),
                            snapshot.filamentDiameter(),
                            legacy.material().nozzleTemperature(),
                            legacy.material().bedTemperature()),
                    setup.process());
        }
        double width = 200, depth = 200, height = 200;
        if (setup.printerId().startsWith("generic-marlin-rect-")
                && setup.printerId().endsWith("-0.4")) {
            String[] dimensions = setup.printerId()
                    .substring(
                            "generic-marlin-rect-".length(), setup.printerId().length() - 4)
                    .split("x");
            if (dimensions.length != 3) throw new IllegalArgumentException("Invalid rectangular printer profile");
            width = Double.parseDouble(dimensions[0]);
            depth = Double.parseDouble(dimensions[1]);
            height = Double.parseDouble(dimensions[2]);
            rectangularPrinterId(width, depth, height);
        } else if (!setup.printerId().equals("generic-marlin-200-0.4"))
            throw new IllegalArgumentException("Missing printer profile: " + setup.printerId());
        var printer = new Printer(
                setup.printerId(),
                "Generic Marlin " + width + " × " + depth + " × " + height + " mm / 0.4 mm",
                0.4,
                List.of(new Point(0, 0), new Point(width, 0), new Point(width, depth), new Point(0, depth)),
                List.of(),
                height,
                "marlin2",
                "gcode");
        var material = switch (setup.materialId()) {
            case "PLA" ->
                new Material("PLA", quantity(1.75, MILLIMETRE), quantity(210, CELSIUS), quantity(60, CELSIUS));
            case "PETG" ->
                new Material("PETG", quantity(1.75, MILLIMETRE), quantity(240, CELSIUS), quantity(80, CELSIUS));
            default -> throw new IllegalArgumentException("Missing material profile: " + setup.materialId());
        };
        return new PrintProfile(printer, material, setup.process());
    }
}
