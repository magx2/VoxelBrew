package dev.voxelbrew.engine.model;

import dev.voxelbrew.engine.mesh.MeshData;
import dev.voxelbrew.engine.mesh.MeshOptions;
import java.util.List;
import java.util.Objects;

/**
 * Read-only exporter input. Valid for the synchronous export call; returned meshes are detached.
 */
public interface ModelView {
    enum Role {
        MODEL,
        SOLID_SUPPORT
    }

    record Body(String id, String name, Role role) {
        public Body(String id, String name) {
            this(id, name, Role.MODEL);
        }

        public Body {
            Objects.requireNonNull(id);
            Objects.requireNonNull(name);
            Objects.requireNonNull(role);
        }
    }

    record Instance(String id, String bodyId, Transform transform) {
        public Instance {
            Objects.requireNonNull(id);
            Objects.requireNonNull(bodyId);
            Objects.requireNonNull(transform);
        }
    }

    List<Body> bodies();

    List<Instance> instances();

    MeshData tessellate(String bodyId, MeshOptions options);
}
