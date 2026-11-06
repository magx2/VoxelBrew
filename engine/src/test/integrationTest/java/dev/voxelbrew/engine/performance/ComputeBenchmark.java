package dev.voxelbrew.engine.performance;

import static dev.voxelbrew.engine.sketch.SketchGeometry.*;

import dev.voxelbrew.engine.mesh.*;
import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.engine.printing.*;
import dev.voxelbrew.engine.sketch.*;
import dev.voxelbrew.util.concurrent.CpuWork;
import java.util.*;
import java.util.function.Supplier;

/** Opt-in, warmed wall-clock measurements; never a timing assertion in the regression suite. */
public final class ComputeBenchmark {
    private ComputeBenchmark() {}

    private static volatile Object sink;

    public static CadDocument document(int bodies, int holes) {
        var features = new ArrayList<Feature>();
        var placements = new ArrayList<ModelView.Instance>();
        for (int body = 0; body < bodies; body++) {
            String prefix = "part" + body;
            var plane = new Feature.Plane("origin-xy", 0);
            features.add(
                    new Feature.Sketch(
                            prefix, "profile", plane, new Feature.Rectangle(0, 0, 40, 30)));
            String output = prefix + "extrude";
            features.add(new Feature.Extrude(output, new Feature.ProfileRef(prefix, "profile"), 8));
            placements.add(
                    new ModelView.Instance(
                            prefix,
                            output + ".body",
                            Transform.translation((body % 4) * 45, (body / 4) * 35, 0)));
            for (int hole = 0; hole < holes; hole++) {
                String sketch = prefix + "hole" + hole;
                features.add(
                        new Feature.Sketch(
                                sketch,
                                "profile",
                                plane,
                                new Feature.Circle(5 + (hole % 4) * 9, 5 + (hole / 4) * 9, 4)));
                String cut = prefix + "cut" + hole;
                features.add(
                        new Feature.ThroughCut(
                                cut,
                                new Feature.ProfileRef(sketch, "profile"),
                                new Feature.BodyRef(output, "body")));
                output = cut;
            }
        }
        return new CadDocument(
                "compute-benchmark",
                features,
                features.size(),
                PrintSetup.empty().withInstances(placements));
    }

    private static SketchGeometry sketch(int lines) {
        var entities = new ArrayList<Entity>();
        for (int i = 0; i < lines; i++)
            entities.add(Sketches.entity("line" + i, Kind.LINE, false, 0, i * 3, 9, i * 3 + 1));
        var geometry = new SketchGeometry(entities, List.of(), List.of());
        for (var entity : entities) {
            geometry =
                    geometry.add(
                            SketchConstraints.create(
                                    geometry,
                                    entity.id() + "fixed",
                                    Type.FIX,
                                    List.of(entity.ref(Part.START)),
                                    0));
            geometry =
                    geometry.add(
                            SketchConstraints.create(
                                    geometry,
                                    entity.id() + "horizontal",
                                    Type.HORIZONTAL,
                                    List.of(entity.ref()),
                                    0));
            geometry =
                    geometry.add(
                            SketchConstraints.create(
                                    geometry,
                                    entity.id() + "length",
                                    Type.LENGTH,
                                    List.of(entity.ref()),
                                    10));
        }
        return geometry;
    }

    private static void measure(String name, Supplier<?> operation) {
        for (int i = 0; i < (name.startsWith("sketch") ? 24 : 4); i++) sink = operation.get();
        double[] times = new double[9];
        for (int i = 0; i < times.length; i++) {
            long start = System.nanoTime();
            sink = operation.get();
            times[i] = (System.nanoTime() - start) / 1e6;
        }
        Arrays.sort(times);
        System.out.printf(
                Locale.ROOT,
                "BENCH %s threads=%d median_ms=%.3f min_ms=%.3f max_ms=%.3f%n",
                name,
                CpuWork.parallelism(),
                times[4],
                times[0],
                times[8]);
    }

    public static void main(String[] args) {
        System.out.printf(
                "PLATFORM os=%s arch=%s java=%s processors=%d threads=%d%n",
                System.getProperty("os.name"),
                System.getProperty("os.arch"),
                System.getProperty("java.version"),
                Runtime.getRuntime().availableProcessors(),
                CpuWork.parallelism());
        if (args.length > 0 && args[0].equals("sketch")) {
            for (int lines : new int[] {24, 48}) {
                var input = sketch(lines);
                measure("sketch_" + (lines * 4) + "_variables", () -> SketchSolver.solve(input));
            }
            return;
        }
        var document = document(12, 12);
        try (var evaluation = Evaluator.evaluate(document)) {
            var serial =
                    evaluation.requireCompleteDocument().stream()
                            .map(b -> b.solid().tessellate(MeshOptions.DEFAULT))
                            .toList();
            var detached = ManufacturingModel.from(document, evaluation, MeshOptions.DEFAULT);
            for (int i = 0; i < serial.size(); i++) {
                var mesh = detached.meshes().get(evaluation.bodies().get(i).id());
                if (!Arrays.equals(serial.get(i).vertices(), mesh.vertices())
                        || !Arrays.equals(serial.get(i).triangles(), mesh.triangles()))
                    throw new AssertionError("Non-deterministic mesh");
            }
            measure(
                    "mesh_12_bodies_144_holes",
                    () -> ManufacturingModel.from(document, evaluation, MeshOptions.DEFAULT));
            PrintValidation.validate(detached).requireValid();
            measure("print_12_instances", () -> PrintValidation.validate(detached));
        }
        var sketch = sketch(48);
        SketchSolver.solve(sketch).requireSolved();
        measure("sketch_192_variables", () -> SketchSolver.solve(sketch));
    }
}
