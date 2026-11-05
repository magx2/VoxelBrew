package dev.voxelbrew.engine.parameters;

/** Display units; quantities are evaluated in millimeters and degrees. */
public enum ParameterUnit {
    MM("mm", 1, 1, 0),
    CM("cm", 10, 1, 0),
    M("m", 1000, 1, 0),
    IN("in", 25.4, 1, 0),
    FT("ft", 304.8, 1, 0),
    DEG("deg", 1, 0, 1),
    RAD("rad", 180 / Math.PI, 0, 1),
    NUMBER("unitless", 1, 0, 0);

    private final String symbol;
    private final double scale;
    private final int length, angle;

    ParameterUnit(String symbol, double scale, int length, int angle) {
        this.symbol = symbol;
        this.scale = scale;
        this.length = length;
        this.angle = angle;
    }

    public double scale() {
        return scale;
    }

    public int length() {
        return length;
    }

    public int angle() {
        return angle;
    }

    @Override
    public String toString() {
        return symbol;
    }

    public static ParameterUnit parse(String text) {
        for (var unit : values())
            if (unit.symbol.equalsIgnoreCase(text) || unit.name().equalsIgnoreCase(text))
                return unit;
        throw new IllegalArgumentException("Unknown unit: " + text);
    }
}
