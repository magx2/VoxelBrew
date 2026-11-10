package dev.voxelbrew.engine.printing;

import dev.voxelbrew.engine.model.BodyInstance;
import dev.voxelbrew.engine.model.CadDocument;
import java.util.HashSet;
import java.util.List;

/** One independent build plate in the shared printer/process setup. */
public record PrintPlate(String id, List<BodyInstance> instances, List<SupportVolume> supports) {
    public PrintPlate {
        CadDocument.identifier(id, "plate ID");
        instances = List.copyOf(instances);
        supports = List.copyOf(supports);
        var ids = new HashSet<String>();
        for (var instance : instances) {
            CadDocument.identifier(instance.id(), "instance ID");
            CadDocument.identifier(instance.bodyId(), "instance body ID");
            if (!ids.add(instance.id())) throw new IllegalArgumentException("Duplicate instance " + instance.id());
        }
        ids.clear();
        for (var support : supports)
            if (!ids.add(support.id())) throw new IllegalArgumentException("Duplicate support " + support.id());
    }
}
