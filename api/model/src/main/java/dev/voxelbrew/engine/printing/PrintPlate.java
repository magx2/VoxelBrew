package dev.voxelbrew.engine.printing;

import dev.voxelbrew.engine.model.BodyInstance;
import dev.voxelbrew.engine.model.CadDocument;
import java.util.HashSet;
import java.util.List;

/** One independent build plate in the shared printer/process setup. */
public record PrintPlate(String id, String name, List<BodyInstance> instances, List<SupportVolume> supports) {
    public PrintPlate(String id, List<BodyInstance> instances, List<SupportVolume> supports) {
        this(id, "", instances, supports);
    }

    public PrintPlate withName(String value) {
        String normalized = java.util.Objects.requireNonNull(value).strip();
        CadDocument.requireBodyName(normalized);
        return new PrintPlate(id, normalized, instances, supports);
    }

    public PrintPlate {
        CadDocument.identifier(id, "plate ID");
        java.util.Objects.requireNonNull(name, "plate name");
        if (!name.isEmpty()) CadDocument.requireBodyName(name);
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
