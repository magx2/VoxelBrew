package dev.voxelbrew.engine.printing;

import dev.voxelbrew.engine.geometry.Bounds;
import dev.voxelbrew.engine.mesh.MeshValidation;
import java.awt.BasicStroke;
import java.awt.geom.Area;
import java.util.*;

public final class PrintValidation {
    public record Diagnostic(String objectId, String code, String message) {}

    public record Result(List<Diagnostic> diagnostics, Map<String, MeshValidation.Report> meshes) {
        public Result {
            diagnostics = List.copyOf(diagnostics);
            meshes = Map.copyOf(meshes);
        }

        public boolean valid() {
            return diagnostics.isEmpty();
        }

        public void requireValid() {
            if (!valid())
                throw new IllegalArgumentException("Print setup is invalid: " + diagnostics);
        }
    }

    private PrintValidation() {}

    public static Result validate(ManufacturingModel model) {
        var diagnostics = new ArrayList<Diagnostic>();
        var reports = new LinkedHashMap<String, MeshValidation.Report>();
        var footprints = new LinkedHashMap<String, Area>();
        var bounds = new LinkedHashMap<String, Bounds>();
        if (model.instances().isEmpty())
            diagnostics.add(new Diagnostic("print", "EMPTY", "Add at least one build instance"));
        var bed = MeshGeometry.polygon(model.profile().printer().bed());
        bed.add(new Area(new BasicStroke(0.002f).createStrokedShape(bed)));
        for (var instance : model.instances()) {
            var mesh = model.meshes().get(instance.bodyId());
            if (mesh == null) {
                diagnostics.add(
                        new Diagnostic(
                                instance.id(),
                                "MISSING_BODY",
                                "Missing body " + instance.bodyId()));
                continue;
            }
            var report = MeshValidation.inspect(mesh);
            reports.put(instance.id(), report);
            if (!report.closed())
                diagnostics.add(new Diagnostic(instance.id(), "MESH", report.toString()));
            var placed = model.placedMesh(instance);
            var footprint = MeshGeometry.footprint(placed);
            var box = MeshGeometry.bounds(placed);
            footprints.put(instance.id(), footprint);
            bounds.put(instance.id(), box);
            checkBed(instance.id(), footprint, box, bed, model.profile(), diagnostics);
        }
        var ids = new ArrayList<>(footprints.keySet());
        for (int i = 0; i < ids.size(); i++)
            for (int j = i + 1; j < ids.size(); j++) {
                var a = bounds.get(ids.get(i));
                var b = bounds.get(ids.get(j));
                if (Math.min(a.maxZ(), b.maxZ()) - Math.max(a.minZ(), b.minZ()) <= 0.001) continue;
                var overlap = new Area(footprints.get(ids.get(i)));
                overlap.intersect(footprints.get(ids.get(j)));
                if (!overlap.isEmpty()) {
                    diagnostics.add(
                            new Diagnostic(
                                    ids.get(i), "OVERLAP", "Footprint overlaps " + ids.get(j)));
                    diagnostics.add(
                            new Diagnostic(
                                    ids.get(j), "OVERLAP", "Footprint overlaps " + ids.get(i)));
                }
            }
        for (var support : model.setup().supports()) {
            var owner =
                    model.instances().stream()
                            .filter(i -> i.id().equals(support.instanceId()))
                            .findFirst();
            if (owner.isEmpty() || !footprints.containsKey(support.instanceId())) {
                diagnostics.add(
                        new Diagnostic(
                                support.id(),
                                "MISSING_INSTANCE",
                                "Support attachment is missing: " + support.instanceId()));
                continue;
            }
            var mesh = MeshGeometry.transform(MeshGeometry.box(support), owner.get().transform());
            var footprint = MeshGeometry.footprint(mesh);
            var box = MeshGeometry.bounds(mesh);
            if (support.role() == SupportVolume.Role.SOLID_SUPPORT)
                checkBed(support.id(), footprint, box, bed, model.profile(), diagnostics);
            else {
                var overlap = new Area(footprint);
                overlap.intersect(footprints.get(support.instanceId()));
                var part = bounds.get(support.instanceId());
                if (overlap.isEmpty()
                        || box.maxZ() < part.minZ() - 0.001
                        || box.minZ() > part.maxZ() + 0.001)
                    diagnostics.add(
                            new Diagnostic(
                                    support.id(),
                                    "DETACHED_MODIFIER",
                                    "Support instruction no longer intersects its attached instance"));
            }
        }
        return new Result(diagnostics, reports);
    }

    private static void checkBed(
            String id,
            Area footprint,
            Bounds box,
            Area bed,
            PrintProfile profile,
            List<Diagnostic> diagnostics) {
        var outside = new Area(footprint);
        outside.subtract(bed);
        if (!outside.isEmpty())
            diagnostics.add(
                    new Diagnostic(id, "OUTSIDE_BED", "Footprint lies outside the bed polygon"));
        if (box.minZ() < -0.001)
            diagnostics.add(new Diagnostic(id, "BELOW_BED", "Material lies below Z=0"));
        if (box.maxZ() > profile.printer().usableHeight() + 0.001)
            diagnostics.add(
                    new Diagnostic(
                            id,
                            "OVER_HEIGHT",
                            "Exceeds usable height " + profile.printer().usableHeight() + " mm"));
        for (var zone : profile.printer().excludedZones()) {
            var intersection = new Area(footprint);
            intersection.intersect(MeshGeometry.polygon(zone));
            if (!intersection.isEmpty())
                diagnostics.add(
                        new Diagnostic(id, "EXCLUDED_ZONE", "Footprint enters an excluded zone"));
        }
    }
}
