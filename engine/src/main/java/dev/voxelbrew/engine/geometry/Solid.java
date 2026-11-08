package dev.voxelbrew.engine.geometry;

import static dev.voxelbrew.engine.units.Measurements.*;

import javax.measure.Quantity;
import javax.measure.quantity.Length;
import javax.measure.quantity.Volume;

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

    public static Solid profilePrism(PlanarProfile profile, Quantity<Length> z, Quantity<Length> distance) {
        return profilePrism(profile, value(z, MILLIMETRE), value(distance, MILLIMETRE));
    }

    public static Solid circlePrism(
            Quantity<Length> x,
            Quantity<Length> y,
            Quantity<Length> z,
            Quantity<Length> diameter,
            Quantity<Length> distance) {
        return circlePrism(
                value(x, MILLIMETRE),
                value(y, MILLIMETRE),
                value(z, MILLIMETRE),
                value(diameter, MILLIMETRE),
                value(distance, MILLIMETRE));
    }

    public static Solid rectanglePrism(
            Quantity<Length> x,
            Quantity<Length> y,
            Quantity<Length> z,
            Quantity<Length> width,
            Quantity<Length> height,
            Quantity<Length> distance) {
        return rectanglePrism(
                value(x, MILLIMETRE),
                value(y, MILLIMETRE),
                value(z, MILLIMETRE),
                value(width, MILLIMETRE),
                value(height, MILLIMETRE),
                value(distance, MILLIMETRE));
    }

    public Solid cutThroughCircle(Quantity<Length> x, Quantity<Length> y, Quantity<Length> diameter) {
        return cutThroughCircle(value(x, MILLIMETRE), value(y, MILLIMETRE), value(diameter, MILLIMETRE));
    }

    public Quantity<Volume> volumeQuantity() {
        return quantity(volume(), CUBIC_MILLIMETRE);
    }

    public Split split(double nx, double ny, double nz, Quantity<Length> offset) {
        return split(nx, ny, nz, value(offset, MILLIMETRE));
    }

    public boolean contains(Quantity<Length> x, Quantity<Length> y, Quantity<Length> z) {
        return contains(value(x, MILLIMETRE), value(y, MILLIMETRE), value(z, MILLIMETRE));
    }

    /** Extrudes exact line, circle, arc, and cubic Bezier boundaries, with optional holes. */
    public static Solid profilePrism(PlanarProfile profile, double z, double distance) {
        finite("offsetZ", z);
        finite("distance", distance);
        positive("absolute distance", Math.abs(distance));
        return new Solid(NativeKernel.profilePrism(
                profile.types(), profile.sizes(), profile.parameterSizes(), profile.parameters(), z, distance));
    }

    public synchronized Solid cutThroughProfile(PlanarProfile profile) {
        requireOpen();
        return new Solid(NativeKernel.cutThroughProfile(
                token, profile.types(), profile.sizes(), profile.parameterSizes(), profile.parameters()));
    }

    public static Solid circlePrism(double x, double y, double z, double diameter, double distance) {
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
    public static Solid rectanglePrism(double x, double y, double z, double width, double height, double distance) {
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

    public synchronized Solid edgeFinish(boolean fillet, double size, int[] edges) {
        requireOpen();
        positive("size", size);
        if (edges.length == 0) throw new IllegalArgumentException("Select at least one edge");
        return new Solid(NativeKernel.edgeFinish(token, fillet, size, edges.clone()));
    }

    /** Pairs of zero-based face index and one-based edge index from the same source topology. */
    public synchronized int[] faceEdges() {
        requireOpen();
        return NativeKernel.faceEdges(token);
    }

    /** Nine sample points per OCCT edge, each encoded as edge index and XYZ coordinates. */
    public synchronized double[] edgeSamples() {
        requireOpen();
        return NativeKernel.edgeSamples(token);
    }

    public synchronized Solid shell(double thickness, int removedFace) {
        requireOpen();
        positive("thickness", thickness);
        if (removedFace < 0) throw new IllegalArgumentException("removedFace must be nonnegative");
        return new Solid(NativeKernel.shell(token, thickness, removedFace));
    }

    /** Independently owned rigid copy; the source geometry is never changed. */
    public synchronized Solid transformed(dev.voxelbrew.engine.model.Transform placement) {
        requireOpen();
        return new Solid(NativeKernel.transform(token, placement.matrix()));
    }

    /** Detached exact planes, using the same face order as tessellation. */
    public synchronized java.util.List<PlanarFace> planarFaces() {
        requireOpen();
        double[] data = NativeKernel.planarFaces(token);
        var result = new java.util.ArrayList<PlanarFace>();
        for (int i = 0; i < data.length; i += 5)
            result.add(new PlanarFace((int) data[i], data[i + 1], data[i + 2], data[i + 3], data[i + 4]));
        return java.util.List.copyOf(result);
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
    public synchronized dev.voxelbrew.engine.mesh.MeshData tessellate(dev.voxelbrew.engine.mesh.MeshOptions options) {
        requireOpen();
        return NativeKernel.tessellate(token, options.chordToleranceMm(), value(options.angularTolerance(), RADIAN));
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
