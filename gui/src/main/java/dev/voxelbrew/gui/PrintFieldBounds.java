package dev.voxelbrew.gui;

/** Input bounds matching the persisted ProcessSettings contract. */
record PrintFieldBounds(double min, double max, boolean whole) {
    boolean accepts(double value) {
        return Double.isFinite(value) && value >= min && value <= max && (!whole || value == Math.rint(value));
    }

    static PrintFieldBounds forField(String id) {
        return switch (id) {
            case "print-layer-height" -> new PrintFieldBounds(0.05, 0.3, false);
            case "print-walls" -> new PrintFieldBounds(1, 20, true);
            case "print-infill" -> new PrintFieldBounds(0, 100, false);
            case "print-threshold" -> new PrintFieldBounds(0, 90, false);
            case "print-gap" -> new PrintFieldBounds(0, 2, false);
            case "print-interfaces", "print-raft" -> new PrintFieldBounds(0, 20, true);
            case "print-brim" -> new PrintFieldBounds(0, 50, false);
            default -> null;
        };
    }
}
