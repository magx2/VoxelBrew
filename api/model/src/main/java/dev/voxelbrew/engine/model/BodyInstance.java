package dev.voxelbrew.engine.model;

import lombok.NonNull;

/** A named placement of a CAD body, independent of any exchange format. */
public record BodyInstance(
        @NonNull String id, @NonNull String bodyId, @NonNull Transform transform) {
    public BodyInstance {}
}
