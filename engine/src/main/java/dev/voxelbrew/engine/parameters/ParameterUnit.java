package dev.voxelbrew.engine.parameters;

import static dev.voxelbrew.engine.units.Measurements.*;

import dev.voxelbrew.engine.units.Measurements;
import javax.measure.Quantity;
import javax.measure.Unit;

/** Stable persisted names and UI labels for standard JSR 385 units. */
public enum ParameterUnit {
    MM("mm", MILLIMETRE),
    CM("cm", CENTIMETRE),
    M("m", METRE),
    IN("in", INCH),
    FT("ft", FOOT),
    DEG("deg", DEGREE),
    RAD("rad", RADIAN),
    NUMBER("unitless", ONE);

    private final String symbol;
    private final Unit<?> unit;

    ParameterUnit(String symbol, Unit<?> unit) {
        this.symbol = symbol;
        this.unit = unit;
    }

    public Unit<?> unit() {
        return unit;
    }

    public Unit<?> canonicalUnit() {
        return canonical(unit);
    }

    public Quantity<?> quantity(double value) {
        return Measurements.quantity(value, unit);
    }

    public double displayValue(Quantity<?> value) {
        return Measurements.value(value, unit);
    }

    public double modelValue(Quantity<?> value) {
        return Measurements.value(value, canonicalUnit());
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
