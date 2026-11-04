package dev.voxelbrew.engine.mesh;

/** Absolute chordal tolerance in mm and angular tolerance in degrees. */
public record MeshOptions(double chordToleranceMm, double angularToleranceDegrees) {
    public static final MeshOptions DEFAULT = new MeshOptions(0.02, 5);

    public MeshOptions {
        if (!Double.isFinite(chordToleranceMm) || chordToleranceMm < 1e-6) {
            throw new IllegalArgumentException(
                    "Chordal tolerance must be finite and >= 0.000001 mm");
        }
        if (!Double.isFinite(angularToleranceDegrees)
                || angularToleranceDegrees <= 0
                || angularToleranceDegrees > 180) {
            throw new IllegalArgumentException("Angular tolerance must be in (0, 180] degrees");
        }
    }
}
