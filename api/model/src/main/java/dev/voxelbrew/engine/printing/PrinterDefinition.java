package dev.voxelbrew.engine.printing;

import dev.voxelbrew.engine.model.CadDocument;
import java.util.List;
import lombok.NonNull;

/** Portable hardware snapshot, independent of catalog files and display assets. */
public record PrinterDefinition(
        @NonNull PrintProfile.Printer printer,
        @NonNull String manufacturer,
        double filamentDiameter,
        int toolCount,
        boolean genericGcode,
        boolean modified) {
    public PrinterDefinition {
        CadDocument.identifier(printer.id(), "printer ID");
        if (printer.name().isBlank()
                || manufacturer.isBlank()
                || printer.firmware().isBlank()
                || printer.outputFormat().isBlank()
                || !Double.isFinite(filamentDiameter)
                || filamentDiameter <= 0
                || filamentDiameter > 10
                || toolCount < 1
                || toolCount > 64) throw new IllegalArgumentException("Invalid printer hardware");
        validatePolygon(printer.bed());
        printer.excludedZones().forEach(PrinterDefinition::validatePolygon);
        if (printer.usableHeight() > 10000 || printer.nozzleMm() > 10)
            throw new IllegalArgumentException("Printer dimensions exceed supported limits");
        if (genericGcode
                && (toolCount != 1
                        || !printer.firmware().equals("marlin2")
                        || !printer.outputFormat().equals("gcode")))
            throw new IllegalArgumentException("Generic G-code requires one Marlin 2 tool and text G-code");
    }

    private static void validatePolygon(List<PrintProfile.Point> points) {
        double area = 0;
        for (int i = 0; i < points.size(); i++) {
            var a = points.get(i);
            var b = points.get((i + 1) % points.size());
            if (Math.abs(a.x()) > 10000 || Math.abs(a.y()) > 10000)
                throw new IllegalArgumentException("Bed coordinate exceeds supported limits");
            area += a.x() * b.y() - b.x() * a.y();
        }
        if (Math.abs(area) < 1e-6) throw new IllegalArgumentException("Degenerate bed polygon");
    }

    public PrinterDefinition rectangular(double width, double depth, double height) {
        PrintProfile.rectangularPrinterId(width, depth, height);
        return new PrinterDefinition(
                new PrintProfile.Printer(
                        printer.id(),
                        printer.name(),
                        printer.nozzleMm(),
                        List.of(
                                new PrintProfile.Point(0, 0), new PrintProfile.Point(width, 0),
                                new PrintProfile.Point(width, depth), new PrintProfile.Point(0, depth)),
                        printer.excludedZones(),
                        height,
                        printer.firmware(),
                        printer.outputFormat()),
                manufacturer,
                filamentDiameter,
                toolCount,
                genericGcode,
                true);
    }
}
