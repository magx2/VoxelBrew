package dev.voxelbrew.engine.printing;

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
    public static final ProcessSettings DEFAULT =
            new ProcessSettings(0.2, 2, 20, false, false, 45, 0.2, 3, 0, 0);

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
