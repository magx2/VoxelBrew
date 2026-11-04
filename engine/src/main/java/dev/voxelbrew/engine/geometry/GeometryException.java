package dev.voxelbrew.engine.geometry;

/** A diagnostic from the real geometry kernel; no native exception crosses JNI. */
public final class GeometryException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public GeometryException(String message) {
        super(message);
    }
}
