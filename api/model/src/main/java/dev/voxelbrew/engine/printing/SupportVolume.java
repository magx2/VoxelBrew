package dev.voxelbrew.engine.printing;

import dev.voxelbrew.engine.model.CadDocument;

/** Body-coordinate boxes attached to a stable build instance, never to generated face IDs. */
public record SupportVolume(
        String id,
        String instanceId,
        Role role,
        double x,
        double y,
        double z,
        double width,
        double depth,
        double height) {
    public enum Role {
        BLOCKER,
        ENFORCER,
        SOLID_SUPPORT
    }

    public SupportVolume {
        CadDocument.identifier(id, "support ID");
        CadDocument.identifier(instanceId, "support instance ID");
        java.util.Objects.requireNonNull(role);
        for (double value : new double[] {x, y, z, width, depth, height, x + width, y + depth, z + height})
            if (!Double.isFinite(value)) throw new IllegalArgumentException("support dimensions must be finite");
        if (width < 1e-6 || depth < 1e-6 || height < 1e-6)
            throw new IllegalArgumentException("support dimensions must be positive");
    }
}
