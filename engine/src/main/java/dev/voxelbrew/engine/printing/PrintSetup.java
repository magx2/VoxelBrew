package dev.voxelbrew.engine.printing;

import dev.voxelbrew.engine.model.BodyInstance;
import dev.voxelbrew.engine.model.CadDocument;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Persistent manufacturing definitions; toolpaths remain disposable derived output. */
public record PrintSetup(
        String id,
        int version,
        String backendId,
        String printerId,
        String materialId,
        ProcessSettings process,
        List<BodyInstance> instances,
        List<SupportVolume> supports) {
    public PrintSetup {
        CadDocument.identifier(id, "print setup ID");
        if (version != 1)
            throw new IllegalArgumentException("unsupported print setup version " + version);
        CadDocument.identifier(backendId, "slicer backend ID");
        CadDocument.identifier(printerId, "printer profile ID");
        CadDocument.identifier(materialId, "material profile ID");
        Objects.requireNonNull(process);
        instances = List.copyOf(instances);
        supports = List.copyOf(supports);
        var ids = new HashSet<String>();
        for (var instance : instances) {
            CadDocument.identifier(instance.id(), "instance ID");
            CadDocument.identifier(instance.bodyId(), "instance body ID");
            if (!ids.add(instance.id()))
                throw new IllegalArgumentException("duplicate instance " + instance.id());
        }
        ids.clear();
        for (var support : supports)
            if (!ids.add(support.id()))
                throw new IllegalArgumentException("duplicate support " + support.id());
        // Missing bodies/instances are diagnosed by manufacturing validation, so definitions can be
        // repaired.
    }

    public static PrintSetup empty() {
        return new PrintSetup(
                "print",
                1,
                "prusa-slicer",
                "generic-marlin-200-0.4",
                "PLA",
                ProcessSettings.DEFAULT,
                List.of(),
                List.of());
    }

    public PrintSetup withInstances(List<BodyInstance> next) {
        return new PrintSetup(
                id, version, backendId, printerId, materialId, process, next, supports);
    }

    public PrintSetup withSupports(List<SupportVolume> next) {
        return new PrintSetup(
                id, version, backendId, printerId, materialId, process, instances, next);
    }

    public PrintSetup withProfile(
            String backend, String printer, String material, ProcessSettings settings) {
        return new PrintSetup(
                id, version, backend, printer, material, settings, instances, supports);
    }
}
