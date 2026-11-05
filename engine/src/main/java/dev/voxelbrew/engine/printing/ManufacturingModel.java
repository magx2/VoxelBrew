package dev.voxelbrew.engine.printing;

import dev.voxelbrew.engine.export.*;
import dev.voxelbrew.engine.mesh.*;
import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.engine.persistence.DocumentStore;
import java.util.*;

/** Detached immutable revision shared by validators, exporters, slicers and preview. */
public final class ManufacturingModel implements ModelView {
    public enum Selection {
        MODEL,
        SUPPORTS,
        ALL
    }

    private final String documentId, revision;
    private final PrintSetup setup;
    private final PrintProfile profile;
    private final Map<String, MeshData> meshes;

    public ManufacturingModel(
            String documentId,
            String revision,
            PrintSetup setup,
            PrintProfile profile,
            Map<String, MeshData> meshes) {
        this.documentId = Objects.requireNonNull(documentId);
        this.revision = Objects.requireNonNull(revision);
        this.setup = Objects.requireNonNull(setup);
        this.profile = Objects.requireNonNull(profile);
        this.meshes = Collections.unmodifiableMap(new LinkedHashMap<>(meshes));
    }

    public static ManufacturingModel evaluate(CadDocument document, MeshOptions options) {
        return evaluate(
                document, options, dev.voxelbrew.engine.feature.FeatureRegistry.defaultDirectory());
    }

    public static ManufacturingModel evaluate(
            CadDocument document, MeshOptions options, java.nio.file.Path pluginDirectory) {
        try (var evaluation = Evaluator.evaluate(document, pluginDirectory)) {
            return from(document, evaluation, options);
        }
    }

    public static ManufacturingModel from(
            CadDocument document, Evaluation evaluation, MeshOptions options) {
        var meshes = new LinkedHashMap<String, MeshData>();
        var bodies = evaluation.requireCompleteDocument();
        var detached =
                dev.voxelbrew.engine.concurrent.CpuWork.map(
                        bodies, body -> body.solid().tessellate(options));
        for (int i = 0; i < bodies.size(); i++) meshes.put(bodies.get(i).id(), detached.get(i));
        return new ManufacturingModel(
                document.id(),
                DocumentStore.fingerprint(document),
                document.printSetup(),
                PrintProfile.resolve(document.printSetup()),
                meshes);
    }

    public String documentId() {
        return documentId;
    }

    public String revision() {
        return revision;
    }

    public PrintSetup setup() {
        return setup;
    }

    public PrintProfile profile() {
        return profile;
    }

    public Map<String, MeshData> meshes() {
        return meshes;
    }

    @Override
    public List<Body> bodies() {
        return meshes.keySet().stream()
                .filter(id -> instances().stream().anyMatch(i -> i.bodyId().equals(id)))
                .map(id -> new Body(id, id))
                .toList();
    }

    @Override
    public List<Instance> instances() {
        return setup.instances();
    }

    @Override
    public MeshData tessellate(String body, MeshOptions options) {
        var mesh = meshes.get(body);
        if (mesh == null) throw new IllegalArgumentException("Missing body: " + body);
        return mesh;
    }

    public MeshData placedMesh(Instance instance) {
        return MeshGeometry.transform(
                tessellate(instance.bodyId(), MeshOptions.DEFAULT), instance.transform());
    }

    public ModelView selection(Selection selection) {
        if (selection == Selection.MODEL) return this;
        var selectedMeshes = new LinkedHashMap<String, MeshData>();
        var bodies = new ArrayList<Body>();
        var instances = new ArrayList<Instance>();
        if (selection == Selection.ALL) {
            selectedMeshes.putAll(meshes);
            bodies.addAll(bodies());
            instances.addAll(instances());
        }
        for (var support : setup.supports())
            if (support.role() == SupportVolume.Role.SOLID_SUPPORT) {
                var owner =
                        setup.instances().stream()
                                .filter(i -> i.id().equals(support.instanceId()))
                                .findFirst()
                                .orElseThrow(
                                        () ->
                                                new IllegalArgumentException(
                                                        "Support "
                                                                + support.id()
                                                                + " has missing instance "
                                                                + support.instanceId()));
                String id = "support." + support.id();
                selectedMeshes.put(id, MeshGeometry.box(support));
                bodies.add(new Body(id, support.id(), Role.SOLID_SUPPORT));
                instances.add(new Instance(id, id, owner.transform()));
            }
        return new ModelView() {
            @Override
            public List<Body> bodies() {
                return List.copyOf(bodies);
            }

            @Override
            public List<Instance> instances() {
                return List.copyOf(instances);
            }

            @Override
            public MeshData tessellate(String body, MeshOptions options) {
                var mesh = selectedMeshes.get(body);
                if (mesh == null)
                    throw new IllegalArgumentException("Unknown selected body " + body);
                return mesh;
            }
        };
    }
}
