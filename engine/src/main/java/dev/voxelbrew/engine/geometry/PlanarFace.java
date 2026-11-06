package dev.voxelbrew.engine.geometry;

import dev.voxelbrew.engine.model.Transform;

/** Exact analytic support plane. faceIndex belongs only to the current evaluated solid/mesh. */
public record PlanarFace(int faceIndex, double nx, double ny, double nz, double offset) {
    public PlanarFace {
        if (faceIndex < 0
                || !Double.isFinite(offset)
                || !Double.isFinite(nx)
                || !Double.isFinite(ny)
                || !Double.isFinite(nz)
                || Math.abs(Math.hypot(Math.hypot(nx, ny), nz) - 1) > 1e-9)
            throw new IllegalArgumentException("Invalid analytic face plane");
    }

    /** Deterministic right-handed axes, with the origin projected from the model origin. */
    public Transform frame() {
        double ax = Math.abs(nx) < .9 ? 1 : 0, ay = Math.abs(nx) < .9 ? 0 : 1;
        double dot = ax * nx + ay * ny;
        double ux = ax - dot * nx, uy = ay - dot * ny, uz = -dot * nz;
        double length = Math.hypot(Math.hypot(ux, uy), uz);
        ux /= length;
        uy /= length;
        uz /= length;
        double vx = ny * uz - nz * uy, vy = nz * ux - nx * uz, vz = nx * uy - ny * ux;
        return new Transform(
                ux, vx, nx, nx * offset, uy, vy, ny, ny * offset, uz, vz, nz, nz * offset);
    }
}
