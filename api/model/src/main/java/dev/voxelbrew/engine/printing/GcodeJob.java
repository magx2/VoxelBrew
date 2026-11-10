package dev.voxelbrew.engine.printing;

import java.util.*;

/** Detached printer-job preview; file parsing lives in the import module. */
public record GcodeJob(
        List<Segment> segments,
        List<Double> modelLayers,
        OptionalDouble filamentMm,
        Optional<String> estimatedTime,
        Map<String, String> report,
        List<String> lines) {
    public GcodeJob(
            List<Segment> segments,
            List<Double> modelLayers,
            OptionalDouble filamentMm,
            Optional<String> estimatedTime,
            Map<String, String> report) {
        this(segments, modelLayers, filamentMm, estimatedTime, report, List.of());
    }

    public enum Role {
        PERIMETER,
        EXTERNAL_PERIMETER,
        INFILL,
        SOLID_INFILL,
        BRIDGE,
        GAP_FILL,
        SUPPORT,
        INTERFACE,
        SKIRT_BRIM,
        IRONING,
        TRAVEL,
        UNKNOWN;

        public boolean model() {
            return this == PERIMETER
                    || this == EXTERNAL_PERIMETER
                    || this == INFILL
                    || this == SOLID_INFILL
                    || this == BRIDGE
                    || this == GAP_FILL
                    || this == IRONING;
        }
    }

    public record Segment(
            double x0,
            double y0,
            double z0,
            double x1,
            double y1,
            double z1,
            double extrusion,
            double width,
            double height,
            Role role) {
        public double length() {
            return Math.hypot(Math.hypot(x1 - x0, y1 - y0), z1 - z0);
        }

        public javax.measure.Quantity<javax.measure.quantity.Length> lengthQuantity() {
            return dev.voxelbrew.engine.units.Measurements.quantity(
                    length(), dev.voxelbrew.engine.units.Measurements.MILLIMETRE);
        }

        public boolean deposits() {
            return extrusion > 1e-8 && role != Role.TRAVEL && length() > 1e-8;
        }
    }

    public GcodeJob {
        lines = List.copyOf(lines);
        segments = List.copyOf(segments);
        modelLayers = List.copyOf(modelLayers);
        report = Map.copyOf(report);
    }
}
