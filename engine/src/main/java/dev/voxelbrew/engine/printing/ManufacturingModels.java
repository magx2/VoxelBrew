package dev.voxelbrew.engine.printing;

import dev.voxelbrew.engine.mesh.*;
import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.engine.persistence.DocumentCodec;
import java.util.*;

/** Engine evaluation and tessellation adapters for detached manufacturing models. */
public final class ManufacturingModels {
    private ManufacturingModels() {}

    public static ManufacturingModel evaluate(CadDocument document, MeshOptions options) {
        return evaluate(document, options, dev.voxelbrew.engine.feature.FeatureRegistry.defaultDirectory());
    }

    public static ManufacturingModel evaluate(
            CadDocument document, MeshOptions options, java.nio.file.Path pluginDirectory) {
        try (var evaluation = Evaluator.evaluate(document, pluginDirectory)) {
            return from(document, evaluation, options);
        }
    }

    public static ManufacturingModel from(CadDocument document, Evaluation evaluation, MeshOptions options) {
        var meshes = new LinkedHashMap<String, MeshData>();
        var bodies = evaluation.requireCompleteDocument();
        var detached = dev.voxelbrew.util.concurrent.CpuWork.map(
                bodies, body -> body.solid().tessellate(options));
        for (int i = 0; i < bodies.size(); i++) meshes.put(bodies.get(i).id(), detached.get(i));
        return new ManufacturingModel(
                document.id(),
                DocumentCodec.fingerprint(document),
                document.printSetup(),
                PrintProfile.resolve(document.printSetup()),
                meshes);
    }
}
