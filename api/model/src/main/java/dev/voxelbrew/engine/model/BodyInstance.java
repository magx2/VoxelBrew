package dev.voxelbrew.engine.model;

import java.util.Objects;

/** A named placement of a CAD body, independent of any exchange format. */
public record BodyInstance(String id, String bodyId, Transform transform) {
    public BodyInstance {
        Objects.requireNonNull(id);
        Objects.requireNonNull(bodyId);
        Objects.requireNonNull(transform);
    }
}
