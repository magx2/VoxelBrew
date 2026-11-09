package dev.voxelbrew.engine.geometry;

import static dev.voxelbrew.engine.units.Measurements.*;

import javax.measure.Quantity;
import javax.measure.quantity.Length;

/** Axis-aligned exact-solid bounds in millimeters. */
public record Bounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
    public Quantity<Length> width() {
        return quantity(maxX - minX, MILLIMETRE);
    }

    public Quantity<Length> height() {
        return quantity(maxY - minY, MILLIMETRE);
    }

    public Quantity<Length> depth() {
        return quantity(maxZ - minZ, MILLIMETRE);
    }
}
