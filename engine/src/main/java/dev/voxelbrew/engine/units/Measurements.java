package dev.voxelbrew.engine.units;

import java.util.Map;
import javax.measure.Quantity;
import javax.measure.Unit;
import javax.measure.UnitConverter;
import javax.measure.quantity.*;
import tech.units.indriya.AbstractUnit;
import tech.units.indriya.function.MultiplyConverter;
import tech.units.indriya.quantity.Quantities;
import tech.units.indriya.unit.Units;

/** JSR 385 quantities and conversions at CAD, UI and manufacturing boundaries. */
public final class Measurements {
    private Measurements() {}

    public static final Unit<Length> METRE = Units.METRE;
    public static final Unit<Length> MILLIMETRE = javax.measure.MetricPrefix.MILLI(METRE);
    public static final Unit<Length> CENTIMETRE = javax.measure.MetricPrefix.CENTI(METRE);
    public static final Unit<Length> INCH = MILLIMETRE.multiply(254).divide(10);
    public static final Unit<Length> FOOT = INCH.multiply(12);
    public static final Unit<Angle> RADIAN = Units.RADIAN;
    public static final Unit<Angle> DEGREE =
            RADIAN.transform(
                    MultiplyConverter.ofPiExponent(1)
                            .concatenate(MultiplyConverter.ofRational(1, 180)));
    public static final Unit<Dimensionless> ONE = AbstractUnit.ONE;
    public static final Unit<Dimensionless> PERCENT = ONE.divide(100);
    public static final Unit<Area> SQUARE_MILLIMETRE = MILLIMETRE.pow(2).asType(Area.class);
    public static final Unit<Area> SQUARE_CENTIMETRE = CENTIMETRE.pow(2).asType(Area.class);
    public static final Unit<Area> SQUARE_METRE = Units.SQUARE_METRE;
    public static final Unit<Volume> CUBIC_MILLIMETRE = MILLIMETRE.pow(3).asType(Volume.class);
    public static final Unit<Volume> CUBIC_CENTIMETRE = CENTIMETRE.pow(3).asType(Volume.class);
    public static final Unit<Volume> CUBIC_METRE = Units.CUBIC_METRE;
    public static final Unit<Time> SECOND = Units.SECOND;
    public static final Unit<Time> MINUTE = SECOND.multiply(60);
    public static final Unit<Time> HOUR = MINUTE.multiply(60);
    public static final Unit<Mass> KILOGRAM = Units.KILOGRAM;
    public static final Unit<Mass> GRAM = javax.measure.MetricPrefix.MILLI(KILOGRAM);
    public static final Unit<Speed> MILLIMETRES_PER_SECOND =
            MILLIMETRE.divide(SECOND).asType(Speed.class);
    public static final Unit<Speed> METRES_PER_SECOND = Units.METRE_PER_SECOND;
    public static final Unit<Temperature> KELVIN = Units.KELVIN;
    public static final Unit<Temperature> CELSIUS = Units.CELSIUS;

    private static final UnitConverter TO_RADIANS = DEGREE.getConverterTo(RADIAN);
    private static final UnitConverter TO_DEGREES = RADIAN.getConverterTo(DEGREE);

    public static <Q extends Quantity<Q>> Quantity<Q> quantity(double value, Unit<Q> unit) {
        finite(value);
        return Quantities.getQuantity(value, unit);
    }

    public static double finite(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Quantity must be finite");
        return value;
    }

    /** CAD distinguishes angles from unitless values even though SI gives both dimension one. */
    public static boolean sameKind(Unit<?> a, Unit<?> b) {
        return a.getSystemUnit().equals(b.getSystemUnit());
    }

    public static double convert(double value, Unit<?> from, Unit<?> to) {
        finite(value);
        if (!sameKind(from, to))
            throw new IllegalArgumentException("Incompatible units: " + from + " and " + to);
        try {
            return finite(from.getConverterToAny(to).convert(value));
        } catch (javax.measure.IncommensurableException failure) {
            throw new IllegalArgumentException(
                    "Incompatible units: " + from + " and " + to, failure);
        }
    }

    public static double value(Quantity<?> quantity, Unit<?> unit) {
        return convert(quantity.getValue().doubleValue(), quantity.getUnit(), unit);
    }

    public static <Q extends Quantity<Q>> Quantity<Q> to(Quantity<?> quantity, Unit<Q> unit) {
        return quantity(value(quantity, unit), unit);
    }

    public static double radians(double degrees) {
        return finite(TO_RADIANS.convert(finite(degrees)));
    }

    public static double degrees(double radians) {
        return finite(TO_DEGREES.convert(finite(radians)));
    }

    /** Canonical model units: millimeters, degrees, seconds, grams and kelvin. */
    public static Unit<?> canonical(Unit<?> unit) {
        var system = unit.getSystemUnit();
        if (system.equals(METRE)) return MILLIMETRE;
        if (system.equals(RADIAN)) return DEGREE;
        if (system.equals(KILOGRAM)) return GRAM;
        var bases = system.getBaseUnits();
        if (bases == null || bases.isEmpty()) return system;
        Unit<?> result = ONE;
        for (var base : bases.entrySet())
            result = result.multiply(canonical(base.getKey()).pow(base.getValue()));
        return result;
    }

    public static Map<? extends Unit<?>, Integer> powers(Unit<?> unit) {
        var system = unit.getSystemUnit();
        if (system.equals(ONE)) return Map.of();
        var bases = system.getBaseUnits();
        return bases == null ? Map.of(system, 1) : bases;
    }

    public static Quantity<?> normalized(Quantity<?> quantity) {
        for (int power : powers(quantity.getUnit()).values())
            if (Math.abs(power) > 8)
                throw new IllegalArgumentException("Unsupported unit powers (limit 8)");
        return to(quantity, canonical(quantity.getUnit()));
    }
}
