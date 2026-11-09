package dev.voxelbrew.exporter;

import dev.voxelbrew.engine.mesh.MeshData;
import dev.voxelbrew.engine.mesh.MeshOptions;
import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.engine.printing.*;
import dev.voxelbrew.exporter.api.*;
import java.util.*;
import lombok.NonNull;

public final class ModelViews {
    private ModelViews() {}

    /** The caller must keep the evaluation open until the synchronous export finishes. */
    public static ModelView from(Evaluation evaluation) {
        var bodies = evaluation.requireCompleteDocument();
        return placed(
                evaluation,
                bodies.stream()
                        .map(b -> new BodyInstance(b.id(), b.id(), Transform.IDENTITY))
                        .toList());
    }

    public static ModelView placed(Evaluation evaluation, List<BodyInstance> placements) {
        var bodies = evaluation.requireCompleteDocument();
        var instances = List.copyOf(placements);
        var ids = new HashSet<String>();
        for (var instance : instances) {
            if (!ids.add(instance.id()) || bodies.stream().noneMatch(b -> b.id().equals(instance.bodyId()))) {
                throw new IllegalArgumentException("Duplicate instance or unknown body: " + instance.id());
            }
        }
        return new ModelView() {
            @Override
            public List<Body> bodies() {
                evaluation.requireCompleteDocument();
                return bodies.stream()
                        .filter(b -> instances.stream().anyMatch(i -> i.bodyId().equals(b.id())))
                        .map(b -> new Body(b.id(), b.id()))
                        .toList();
            }

            @Override
            public List<BodyInstance> instances() {
                evaluation.requireCompleteDocument();
                return instances;
            }

            @Override
            public MeshData tessellate(String id, MeshOptions options) {
                evaluation.requireCompleteDocument();
                return bodies.stream()
                        .filter(b -> b.id().equals(id))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Unknown body " + id))
                        .solid()
                        .tessellate(options);
            }
        };
    }

    public static ModelView select(ModelView source, String bodyId) {
        if (source.bodies().stream().noneMatch(b -> b.id().equals(bodyId)))
            throw new IllegalArgumentException("Unknown body " + bodyId);
        return new ModelView() {
            @Override
            public List<Body> bodies() {
                return source.bodies().stream()
                        .filter(b -> b.id().equals(bodyId))
                        .toList();
            }

            @Override
            public List<BodyInstance> instances() {
                return source.instances().stream()
                        .filter(i -> i.bodyId().equals(bodyId))
                        .toList();
            }

            @Override
            public MeshData tessellate(String id, MeshOptions options) {
                if (!bodyId.equals(id)) throw new IllegalArgumentException("Body is not selected: " + id);
                return source.tessellate(id, options);
            }
        };
    }

    public static ModelView selectInstance(ModelView source, String instanceId) {
        var instance = source.instances().stream()
                .filter(i -> i.id().equals(instanceId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown instance " + instanceId));
        return new ModelView() {
            @Override
            public List<Body> bodies() {
                return source.bodies().stream()
                        .filter(b -> b.id().equals(instance.bodyId()))
                        .toList();
            }

            @Override
            public List<BodyInstance> instances() {
                return List.of(instance);
            }

            @Override
            public MeshData tessellate(String id, MeshOptions options) {
                if (!id.equals(instance.bodyId())) throw new IllegalArgumentException("Body is not selected: " + id);
                return source.tessellate(id, options);
            }
        };
    }

    /** Adapt an already detached snapshot without reevaluating or retessellating it. */
    public static ManufacturingView from(@NonNull ManufacturingModel model) {

        return new ManufacturingView() {
            @Override
            public ManufacturingModel manufacturingModel() {
                return model;
            }

            @Override
            public List<Body> bodies() {
                return model.meshes().keySet().stream()
                        .filter(id -> model.instances().stream()
                                .anyMatch(i -> i.bodyId().equals(id)))
                        .map(id -> new Body(id, id))
                        .toList();
            }

            @Override
            public List<BodyInstance> instances() {
                return model.instances();
            }

            @Override
            public MeshData tessellate(String bodyId, MeshOptions options) {
                return model.mesh(bodyId);
            }
        };
    }

    public static ModelView from(@NonNull ManufacturingModel model, @NonNull ExportSelection.Contents selection) {

        if (selection == ExportSelection.Contents.MODEL) return from(model);
        var selectedMeshes = new LinkedHashMap<String, MeshData>();
        var bodies = new ArrayList<ModelView.Body>();
        var instances = new ArrayList<BodyInstance>();
        if (selection == ExportSelection.Contents.ALL) {
            selectedMeshes.putAll(model.meshes());
            bodies.addAll(from(model).bodies());
            instances.addAll(model.instances());
        }
        for (var support : model.setup().supports())
            if (support.role() == SupportVolume.Role.SOLID_SUPPORT) {
                var owner = model.setup().instances().stream()
                        .filter(i -> i.id().equals(support.instanceId()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Support " + support.id() + " has missing instance " + support.instanceId()));
                String id = "support." + support.id();
                selectedMeshes.put(id, MeshGeometry.box(support));
                bodies.add(new ModelView.Body(id, support.id(), ModelView.Role.SOLID_SUPPORT));
                instances.add(new BodyInstance(id, id, owner.transform()));
            }
        return new ModelView() {
            @Override
            public List<ModelView.Body> bodies() {
                return List.copyOf(bodies);
            }

            @Override
            public List<BodyInstance> instances() {
                return List.copyOf(instances);
            }

            @Override
            public MeshData tessellate(String body, MeshOptions options) {
                var mesh = selectedMeshes.get(body);
                if (mesh == null) throw new IllegalArgumentException("Unknown selected body " + body);
                return mesh;
            }
        };
    }
}
