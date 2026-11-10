package dev.voxelbrew.engine.printing;

import dev.voxelbrew.engine.model.BodyInstance;
import dev.voxelbrew.engine.model.CadDocument;
import java.util.HashSet;
import java.util.List;
import lombok.NonNull;

/** Persistent manufacturing definitions; toolpaths remain disposable derived output. */
public record PrintSetup(
        String id,
        int version,
        String backendId,
        String printerId,
        String materialId,
        @NonNull ProcessSettings process,
        List<PrintPlate> plates,
        String activePlateId) {
    public PrintSetup(
            String id,
            int version,
            String backendId,
            String printerId,
            String materialId,
            ProcessSettings process,
            List<BodyInstance> instances,
            List<SupportVolume> supports) {
        this(
                id,
                version,
                backendId,
                printerId,
                materialId,
                process,
                List.of(new PrintPlate("plate-1", instances, supports)),
                "plate-1");
    }

    public PrintSetup {
        CadDocument.identifier(id, "print setup ID");
        if (version != 1 && version != 2 && version != 3)
            throw new IllegalArgumentException("unsupported print setup version " + version);
        CadDocument.identifier(backendId, "slicer backend ID");
        CadDocument.identifier(printerId, "printer profile ID");
        CadDocument.identifier(materialId, "material profile ID");
        plates = List.copyOf(plates);
        if (plates.isEmpty()) throw new IllegalArgumentException("At least one plate is required");
        if (version == 1 && (plates.size() != 1 || !activePlateId.equals("plate-1")))
            throw new IllegalArgumentException("Multiple plates require print setup version 2");
        if (version < 3 && plates.stream().anyMatch(p -> !p.name().isEmpty()))
            throw new IllegalArgumentException("Named plates require print setup version 3");
        var ids = new HashSet<String>();
        for (var plate : plates)
            if (!ids.add(plate.id())) throw new IllegalArgumentException("Duplicate plate " + plate.id());
        if (!ids.contains(activePlateId)) throw new IllegalArgumentException("Missing active plate " + activePlateId);
    }

    public PrintPlate activePlate() {
        return plates.stream()
                .filter(p -> p.id().equals(activePlateId))
                .findFirst()
                .orElseThrow();
    }

    /** Only the selected plate is handed to existing validators, exporters and slicers. */
    public List<BodyInstance> instances() {
        return activePlate().instances();
    }

    public List<SupportVolume> supports() {
        return activePlate().supports();
    }

    public PrintSetup selectPlate(String plateId) {
        return new PrintSetup(id, version, backendId, printerId, materialId, process, plates, plateId);
    }

    public PrintSetup addPlate(String plateId) {
        var next = new java.util.ArrayList<>(plates);
        next.add(new PrintPlate(plateId, List.of(), List.of()));
        return new PrintSetup(id, Math.max(2, version), backendId, printerId, materialId, process, next, plateId);
    }

    public PrintSetup renamePlate(String plateId, String name) {
        var plate = plates.stream()
                .filter(p -> p.id().equals(plateId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Missing plate " + plateId));
        var renamed = plate.withName(name);
        if (plate.equals(renamed)) return this;
        return new PrintSetup(
                id,
                3,
                backendId,
                printerId,
                materialId,
                process,
                plates.stream().map(p -> p.id().equals(plateId) ? renamed : p).toList(),
                activePlateId);
    }

    public PrintSetup moveInstance(String instanceId, String targetPlateId) {
        if (targetPlateId.equals(activePlateId)) return this;
        var target = plates.stream()
                .filter(p -> p.id().equals(targetPlateId))
                .findFirst()
                .orElseThrow();
        var instance = instances().stream()
                .filter(i -> i.id().equals(instanceId))
                .findFirst()
                .orElseThrow();
        var targetInstances = new java.util.ArrayList<>(target.instances());
        targetInstances.add(instance);
        var targetSupports = new java.util.ArrayList<>(target.supports());
        targetSupports.addAll(supports().stream()
                .filter(s -> s.instanceId().equals(instanceId))
                .toList());
        var destination = new PrintPlate(targetPlateId, target.name(), targetInstances, targetSupports);
        var source = new PrintPlate(
                activePlateId,
                activePlate().name(),
                instances().stream().filter(i -> !i.id().equals(instanceId)).toList(),
                supports().stream()
                        .filter(s -> !s.instanceId().equals(instanceId))
                        .toList());
        return new PrintSetup(
                id,
                version,
                backendId,
                printerId,
                materialId,
                process,
                plates.stream()
                        .map(p ->
                                p.id().equals(targetPlateId) ? destination : p.id().equals(activePlateId) ? source : p)
                        .toList(),
                activePlateId);
    }

    public PrintSetup removeActivePlate() {
        if (plates.size() == 1) throw new IllegalArgumentException("Cannot remove the last plate");
        var next = plates.stream().filter(p -> !p.id().equals(activePlateId)).toList();
        return new PrintSetup(
                id,
                version,
                backendId,
                printerId,
                materialId,
                process,
                next,
                next.getFirst().id());
    }

    private PrintSetup replaceActive(PrintPlate plate) {
        return new PrintSetup(
                id,
                version,
                backendId,
                printerId,
                materialId,
                process,
                plates.stream()
                        .map(p -> p.id().equals(activePlateId) ? plate : p)
                        .toList(),
                activePlateId);
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
        return replaceActive(new PrintPlate(activePlateId, activePlate().name(), next, supports()));
    }

    public PrintSetup withSupports(List<SupportVolume> next) {
        return replaceActive(new PrintPlate(activePlateId, activePlate().name(), instances(), next));
    }

    public PrintSetup withProfile(String backend, String printer, String material, ProcessSettings settings) {
        return new PrintSetup(id, version, backend, printer, material, settings, plates, activePlateId);
    }
}
