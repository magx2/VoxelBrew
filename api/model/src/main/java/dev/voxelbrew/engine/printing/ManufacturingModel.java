package dev.voxelbrew.engine.printing;

import dev.voxelbrew.engine.mesh.*;
import dev.voxelbrew.engine.model.*;
import java.util.*;
import lombok.Getter;
import lombok.experimental.Accessors;

/** Detached immutable revision shared by validators, exporters, slicers and preview. */
@Getter
@Accessors(fluent = true)
public final class ManufacturingModel {
    private final String documentId, revision;
    private final PrintSetup setup;
    private final PrintProfile profile;
    private final Map<String, MeshData> meshes;

    public ManufacturingModel(
            String documentId, String revision, PrintSetup setup, PrintProfile profile, Map<String, MeshData> meshes) {
        this.documentId = Objects.requireNonNull(documentId);
        this.revision = Objects.requireNonNull(revision);
        this.setup = Objects.requireNonNull(setup);
        this.profile = Objects.requireNonNull(profile);
        this.meshes = Collections.unmodifiableMap(new LinkedHashMap<>(meshes));
    }

    public List<BodyInstance> instances() {
        return setup.instances();
    }

    public MeshData mesh(String bodyId) {
        var mesh = meshes.get(bodyId);
        if (mesh == null) throw new IllegalArgumentException("Missing body: " + bodyId);
        return mesh;
    }

    public MeshData placedMesh(BodyInstance instance) {
        return MeshGeometry.transform(mesh(instance.bodyId()), instance.transform());
    }
}
