package dev.voxelbrew.engine.geometry;

/** Axis-aligned exact-solid bounds in millimeters. */
public record Bounds(
        double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {}
