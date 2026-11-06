package dev.voxelbrew.engine.model;

import dev.voxelbrew.engine.geometry.PlanarFace;
import java.util.ArrayList;
import java.util.List;

/**
 * A supporting output and an oriented plane group, never a persisted tessellation face ID. Parallel
 * planes are ordered by signed distance. Changed group counts require explicit repair.
 */
public record FaceSupport(
        Feature.BodyRef body, double nx, double ny, double nz, int rank, int count) {
    public FaceSupport {
        java.util.Objects.requireNonNull(body);
        CadDocument.identifier(body.featureId(), "support feature ID");
        CadDocument.identifier(body.outputName(), "support output");
        if (!Double.isFinite(nx)
                || !Double.isFinite(ny)
                || !Double.isFinite(nz)
                || Math.abs(Math.hypot(Math.hypot(nx, ny), nz) - 1) > 1e-9
                || count < 1
                || rank < 0
                || rank >= count)
            throw new IllegalArgumentException("Invalid supporting plane reference");
    }

    public static Feature.Plane attach(
            Feature.BodyRef body, List<PlanarFace> faces, int faceIndex) {
        var face =
                faces.stream()
                        .filter(f -> f.faceIndex() == faceIndex)
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Choose a flat body face"));
        var seed = new FaceSupport(body, face.nx(), face.ny(), face.nz(), 0, 1);
        var planes = seed.planes(faces);
        int rank = 0;
        while (Math.abs(planes.get(rank) - face.offset()) > 1e-6) rank++;
        return new Feature.Plane(
                "face-plane-" + java.util.UUID.randomUUID(),
                0,
                face.frame(),
                new FaceSupport(body, face.nx(), face.ny(), face.nz(), rank, planes.size()));
    }

    public Transform resolve(List<PlanarFace> faces, Transform previous) {
        var planes = planes(faces);
        if (planes.size() != count)
            throw new IllegalArgumentException(
                    "Supporting face planes changed; choose the sketch plane again");
        double offset = planes.get(rank);
        double[] m = previous.matrix();
        if (Math.abs(m[2] - nx) + Math.abs(m[6] - ny) + Math.abs(m[10] - nz) > 1e-8)
            throw new IllegalArgumentException("Sketch axes do not match the supporting face");
        m[3] = nx * offset;
        m[7] = ny * offset;
        m[11] = nz * offset;
        return new Transform(m);
    }

    private List<Double> planes(List<PlanarFace> faces) {
        var ordered =
                faces.stream()
                        .filter(
                                f ->
                                        Math.abs(f.nx() - nx)
                                                        + Math.abs(f.ny() - ny)
                                                        + Math.abs(f.nz() - nz)
                                                < 1e-8)
                        .map(PlanarFace::offset)
                        .sorted()
                        .toList();
        var unique = new ArrayList<Double>();
        for (double value : ordered)
            if (unique.isEmpty() || Math.abs(value - unique.getLast()) > 1e-6) unique.add(value);
        return unique;
    }
}
