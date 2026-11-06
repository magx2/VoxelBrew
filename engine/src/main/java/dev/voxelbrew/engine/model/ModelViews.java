package dev.voxelbrew.engine.model;

import dev.voxelbrew.engine.mesh.MeshData;
import dev.voxelbrew.engine.mesh.MeshOptions;
import java.util.HashSet;
import java.util.List;

public final class ModelViews {
    private ModelViews() {}

    public static ModelView from(Evaluation evaluation) {
        var bodies = evaluation.requireCompleteDocument();
        return placed(
                evaluation,
                bodies.stream()
                        .map(b -> new ModelView.Instance(b.id(), b.id(), Transform.IDENTITY))
                        .toList());
    }

    public static ModelView placed(Evaluation evaluation, List<ModelView.Instance> placements) {
        var bodies = evaluation.requireCompleteDocument();
        var instances = List.copyOf(placements);
        var ids = new HashSet<String>();
        for (var instance : instances) {
            if (!ids.add(instance.id())
                    || bodies.stream().noneMatch(b -> b.id().equals(instance.bodyId()))) {
                throw new IllegalArgumentException(
                        "Duplicate instance or unknown body: " + instance.id());
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
            public List<Instance> instances() {
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
                return source.bodies().stream().filter(b -> b.id().equals(bodyId)).toList();
            }

            @Override
            public List<Instance> instances() {
                return source.instances().stream().filter(i -> i.bodyId().equals(bodyId)).toList();
            }

            @Override
            public MeshData tessellate(String id, MeshOptions options) {
                if (!bodyId.equals(id))
                    throw new IllegalArgumentException("Body is not selected: " + id);
                return source.tessellate(id, options);
            }
        };
    }

    public static ModelView selectInstance(ModelView source, String instanceId) {
        var instance =
                source.instances().stream()
                        .filter(i -> i.id().equals(instanceId))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Unknown instance " + instanceId));
        return new ModelView() {
            @Override
            public List<Body> bodies() {
                return source.bodies().stream()
                        .filter(b -> b.id().equals(instance.bodyId()))
                        .toList();
            }

            @Override
            public List<Instance> instances() {
                return List.of(instance);
            }

            @Override
            public MeshData tessellate(String id, MeshOptions options) {
                if (!id.equals(instance.bodyId()))
                    throw new IllegalArgumentException("Body is not selected: " + id);
                return source.tessellate(id, options);
            }
        };
    }
}
