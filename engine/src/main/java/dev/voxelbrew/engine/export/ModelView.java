package dev.voxelbrew.engine.export;

import dev.voxelbrew.engine.mesh.MeshData;
import dev.voxelbrew.engine.mesh.MeshOptions;
import java.util.List;
import java.util.Objects;

/**
 * Read-only exporter input. Valid for the synchronous export call; returned meshes are detached.
 */
public interface ModelView {
    record Body(String id, String name) {
        public Body {
            Objects.requireNonNull(id);
            Objects.requireNonNull(name);
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
