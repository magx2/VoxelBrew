package dev.voxelbrew.exporter.api;

import dev.voxelbrew.engine.mesh.MeshData;
import dev.voxelbrew.engine.mesh.MeshOptions;
import dev.voxelbrew.engine.model.BodyInstance;
import java.util.List;
import lombok.NonNull;

/**
 * Read-only exporter input. Valid for the synchronous export call; returned meshes are detached.
 */
public interface ModelView {
    enum Role {
        MODEL,
        SOLID_SUPPORT
    }

    record Body(
            @NonNull String id,
            @NonNull String name,
            @NonNull Role role) {
        public Body(String id, String name) {
            this(id, name, Role.MODEL);
        }

        public Body {}
    }

    List<Body> bodies();

    List<BodyInstance> instances();

    MeshData tessellate(String bodyId, MeshOptions options);
}
