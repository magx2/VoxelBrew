package dev.voxelbrew.engine.mesh;

import static dev.voxelbrew.engine.units.Measurements.*;

import javax.measure.Quantity;
import javax.measure.quantity.Angle;
import javax.measure.quantity.Length;

/** Absolute chordal tolerance in mm and angular tolerance in degrees. */
public record MeshOptions(double chordToleranceMm, double angularToleranceDegrees) {
    public static final MeshOptions DEFAULT = new MeshOptions(0.02, 5);

    public MeshOptions(Quantity<Length> chordTolerance, Quantity<Angle> angularTolerance) {
        this(value(chordTolerance, MILLIMETRE), value(angularTolerance, DEGREE));
    }

    public Quantity<Length> chordTolerance() {
        return quantity(chordToleranceMm, MILLIMETRE);
    }

    public Quantity<Angle> angularTolerance() {
        return quantity(angularToleranceDegrees, DEGREE);
    }

    public MeshOptions {
        if (!Double.isFinite(chordToleranceMm) || chordToleranceMm < 1e-6) {
            throw new IllegalArgumentException("Chordal tolerance must be finite and >= 0.000001 mm");
        }
        if (!Double.isFinite(angularToleranceDegrees)
                || angularToleranceDegrees <= 0
                || angularToleranceDegrees > 180) {
            throw new IllegalArgumentException("Angular tolerance must be in (0, 180] degrees");
        }
    }
}
