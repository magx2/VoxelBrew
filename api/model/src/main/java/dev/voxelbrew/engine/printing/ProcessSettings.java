package dev.voxelbrew.engine.printing;

import static dev.voxelbrew.engine.units.Measurements.*;

import javax.measure.Quantity;
import javax.measure.quantity.*;

/** Explicit FDM process settings, in millimeters, degrees from horizontal, and percent. */
public record ProcessSettings(
        double layerHeight,
        int walls,
        double infillPercent,
        boolean automaticSupports,
        boolean supportsOnBedOnly,
        double supportThreshold,
        double supportContactGap,
        int interfaceLayers,
        double brimWidth,
        int raftLayers) {
    public static final ProcessSettings DEFAULT = new ProcessSettings(
            quantity(0.2, MILLIMETRE),
            2,
            quantity(20, PERCENT),
            false,
            false,
            quantity(45, DEGREE),
            quantity(0.2, MILLIMETRE),
            3,
            quantity(0, MILLIMETRE),
            0);

    public ProcessSettings(
            Quantity<Length> layerHeight,
            int walls,
            Quantity<Dimensionless> infill,
            boolean automaticSupports,
            boolean supportsOnBedOnly,
            Quantity<Angle> supportThreshold,
            Quantity<Length> contactGap,
            int interfaceLayers,
            Quantity<Length> brimWidth,
            int raftLayers) {
        this(
                value(layerHeight, MILLIMETRE),
                walls,
                value(infill, PERCENT),
                automaticSupports,
                supportsOnBedOnly,
                value(supportThreshold, DEGREE),
                value(contactGap, MILLIMETRE),
                interfaceLayers,
                value(brimWidth, MILLIMETRE),
                raftLayers);
    }

    public Quantity<Length> layerHeightQuantity() {
        return quantity(layerHeight, MILLIMETRE);
    }

    public Quantity<Dimensionless> infillQuantity() {
        return quantity(infillPercent, PERCENT);
    }

    public Quantity<Angle> supportThresholdQuantity() {
        return quantity(supportThreshold, DEGREE);
    }

    public Quantity<Length> supportContactGapQuantity() {
        return quantity(supportContactGap, MILLIMETRE);
    }

    public Quantity<Length> brimWidthQuantity() {
        return quantity(brimWidth, MILLIMETRE);
    }

    public ProcessSettings {
        range("layer height", layerHeight, 0.05, 0.3);
        range("perimeter walls", walls, 1, 20);
        range("infill percent", infillPercent, 0, 100);
        range("support threshold (degrees from horizontal)", supportThreshold, 0, 90);
        range("support contact gap", supportContactGap, 0, 2);
        range("interface layers", interfaceLayers, 0, 20);
        range("brim width", brimWidth, 0, 50);
        range("raft layers", raftLayers, 0, 20);
    }

    private static void range(String field, double value, double min, double max) {
        if (!Double.isFinite(value) || value < min || value > max)
            throw new IllegalArgumentException(field + " must be between " + min + " and " + max);
    }
}
