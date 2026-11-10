package dev.voxelbrew.gui;

/** Input bounds matching manufacturing value contracts. */
record PrintFieldBounds(double min, double max, boolean whole, double step) {
    PrintFieldBounds(double min, double max, boolean whole) {
        this(min, max, whole, whole ? 1 : 0.1);
    }

    String helpKey() {
        if (!Double.isFinite(min)) return "print.finite";
        if (!Double.isFinite(max)) return "print.atLeast";
        return whole ? "print.wholeRange" : "print.range";
    }

    boolean accepts(double value) {
        return Double.isFinite(value) && value >= min && value <= max && (!whole || value == Math.rint(value));
    }

    static PrintFieldBounds forField(String id) {
        return switch (id) {
            case "print-layer-height" -> new PrintFieldBounds(0.05, 0.3, false, 0.01);
            case "print-walls" -> new PrintFieldBounds(1, 20, true);
            case "print-infill" -> new PrintFieldBounds(0, 100, false, 1);
            case "print-threshold" -> new PrintFieldBounds(0, 90, false, 1);
            case "print-gap" -> new PrintFieldBounds(0, 2, false, 0.01);
            case "print-interfaces", "print-raft" -> new PrintFieldBounds(0, 20, true);
            case "print-brim" -> new PrintFieldBounds(0, 50, false);
            case "print-support-width", "print-support-depth", "print-support-height" ->
                new PrintFieldBounds(1e-6, Double.POSITIVE_INFINITY, false);
            case "print-x", "print-y", "print-z", "print-support-x", "print-support-y", "print-support-z" ->
                new PrintFieldBounds(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, false);
            case "print-rotate-x", "print-rotate-y", "print-rotate-z" ->
                new PrintFieldBounds(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, false, 1);
            default -> null;
        };
    }
}
