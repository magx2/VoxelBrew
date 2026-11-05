package dev.voxelbrew.engine.geometry;

/**
 * An immutable OCCT solid. The caller owns each returned Solid and must close it, preferably with
 * try-with-resources. Operations create independent owned results; closing the source never
 * invalidates the result. Access and close are serialized.
 */
public final class Solid implements AutoCloseable {
    private long token;

    private Solid(long token) {
        if (token == 0) {
            throw new GeometryException("Kernel returned an empty solid handle");
        }
        this.token = token;
    }

    public static String kernelVersion() {
        return NativeKernel.version();
    }

    /** Extrudes exact line, circle, arc, and cubic Bezier boundaries, with optional holes. */
    public static Solid profilePrism(PlanarProfile profile, double z, double distance) {
        finite("offsetZ", z);
        finite("distance", distance);
        positive("absolute distance", Math.abs(distance));
        return new Solid(
                NativeKernel.profilePrism(
                        profile.types(),
                        profile.sizes(),
                        profile.parameterSizes(),
                        profile.parameters(),
                        z,
                        distance));
    }

    public synchronized Solid cutThroughProfile(PlanarProfile profile) {
        requireOpen();
        return new Solid(
                NativeKernel.cutThroughProfile(
                        token,
                        profile.types(),
                        profile.sizes(),
                        profile.parameterSizes(),
                        profile.parameters()));
    }

    public static Solid circlePrism(
            double x, double y, double z, double diameter, double distance) {
        finite("centerX", x);
        finite("centerY", y);
        finite("offsetZ", z);
        positive("diameter", diameter);
        finite("distance", distance);
        if (Math.abs(distance) < 1e-6) {
            throw new IllegalArgumentException("distance must have magnitude >= 0.000001 mm");
        }
        return new Solid(NativeKernel.circlePrism(x, y, z, diameter, distance));
    }

    /** Extrudes an XY rectangle from z by a signed distance, in mm. */
    public static Solid rectanglePrism(
            double x, double y, double z, double width, double height, double distance) {
        finite("x", x);
        finite("y", y);
        finite("z", z);
        positive("width", width);
        positive("height", height);
        finite("distance", distance);
        if (Math.abs(distance) < 1e-6) {
            throw new IllegalArgumentException("distance must have magnitude >= 0.000001 mm");
        }
        return new Solid(NativeKernel.rectanglePrism(x, y, z, width, height, distance));
    }

    /**
     * Subtracts a circle swept through the full current Z extent, including future thickness edits.
     */
    public synchronized Solid cutThroughCircle(double x, double y, double diameter) {
        requireOpen();
        finite("centerX", x);
        finite("centerY", y);
        positive("diameter", diameter);
        return new Solid(NativeKernel.cutThroughCircle(token, x, y, diameter));
    }

    public synchronized double volume() {
        requireOpen();
        return NativeKernel.volume(token);
    }

    public record Split(Solid negative, Solid positive) implements AutoCloseable {
        @Override
        public void close() {
            negative.close();
            positive.close();
        }
    }

    /** Splits into independently owned solids on the negative and positive sides of a plane. */
    public synchronized Split split(double nx, double ny, double nz, double offset) {
        requireOpen();
        finite("normalX", nx);
        finite("normalY", ny);
        finite("normalZ", nz);
        finite("offset", offset);
        if (Math.abs(Math.hypot(Math.hypot(nx, ny), nz) - 1) > 1e-9)
            throw new IllegalArgumentException("split plane normal must have unit length");
        long[] result = NativeKernel.split(token, nx, ny, nz, offset);
        return new Split(new Solid(result[0]), new Solid(result[1]));
    }

    /**
     * Detached mesh; face IDs are local to this tessellation, never persistent feature references.
     */
    public synchronized dev.voxelbrew.engine.mesh.MeshData tessellate(
            dev.voxelbrew.engine.mesh.MeshOptions options) {
        requireOpen();
        return NativeKernel.tessellate(
                token,
                options.chordToleranceMm(),
                Math.toRadians(options.angularToleranceDegrees()));
    }

    public synchronized Bounds bounds() {
        requireOpen();
        double[] b = NativeKernel.bounds(token);
        return new Bounds(b[0], b[1], b[2], b[3], b[4], b[5]);
    }

    /** True for material interior or its boundary (OCCT classifier tolerance 1e-7 mm). */
    public synchronized boolean contains(double x, double y, double z) {
        requireOpen();
        finite("x", x);
        finite("y", y);
        finite("z", z);
        return NativeKernel.contains(token, x, y, z);
    }

    @Override
    public synchronized void close() {
        if (token != 0) {
            NativeKernel.release(token);
            token = 0;
        }
    }

    private void requireOpen() {
        if (token == 0) {
            throw new IllegalStateException("Solid has been closed");
        }
    }

    private static void finite(String field, double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(field + " must be finite");
        }
    }

    private static void positive(String field, double value) {
        finite(field, value);
        if (value < 1e-6) {
            throw new IllegalArgumentException(field + " must be >= 0.000001 mm");
        }
    }
}
