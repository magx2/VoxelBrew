package dev.voxelbrew.exporter.printing;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.mesh.*;
import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.engine.printing.*;
import dev.voxelbrew.exporter.DocumentWriter;
import dev.voxelbrew.exporter.ExportSelection;
import dev.voxelbrew.exporter.ExportService;
import dev.voxelbrew.exporter.ExporterRegistry;
import dev.voxelbrew.exporter.ModelViews;
import dev.voxelbrew.importer.DocumentReader;
import dev.voxelbrew.importer.GcodeReader;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Release tests use the pinned executable, never synthetic success output. */
class SlicerAcceptanceTest {
    private final Path artifacts = Path.of("build", "printing-artifacts");

    private static void box(
            ModelSession session,
            String id,
            double x,
            double y,
            double z,
            double width,
            double depth,
            double height) {
        session.add(
                new Feature.Sketch(
                        id + "-sketch",
                        id + ".profile",
                        new Feature.Plane(id + ".plane", z),
                        new Feature.Rectangle(x, y, width, depth)));
        session.add(
                new Feature.Extrude(
                        id, new Feature.ProfileRef(id + "-sketch", id + ".profile"), height));
    }

    static ModelSession shelf(boolean recessed) {
        var s = new ModelSession(CadDocument.empty());
        if (recessed) box(s, "lower", 0, 0, 0, 30, 40, 2);
        box(s, "leg", 0, 0, recessed ? 2 : 0, 10, 40, recessed ? 8 : 10);
        box(s, "roof", 0, 0, 10, 30, 40, 2);
        var instances =
                s.document().features().stream()
                        .filter(f -> f instanceof Feature.Extrude)
                        .map(
                                f ->
                                        new BodyInstance(
                                                f.id(),
                                                f.id() + ".body",
                                                Transform.translation(20, 30, 0)))
                        .toList();
        s.setPrintSetup(s.document().printSetup().withInstances(instances));
        return s;
    }

    static ModelSession cube() {
        var s = new ModelSession(CadDocument.empty());
        box(s, "cube", 0, 0, 0, 30, 30, 10);
        s.setPrintSetup(
                s.document()
                        .printSetup()
                        .withInstances(
                                List.of(
                                        new BodyInstance(
                                                "cube",
                                                "cube.body",
                                                Transform.translation(20, 30, 0)))));
        return s;
    }

    static void process(
            ModelSession s,
            int walls,
            double infill,
            boolean auto,
            boolean bed,
            double gap,
            int interfaces,
            double brim,
            int raft,
            String material) {
        s.setPrintSetup(
                s.document()
                        .printSetup()
                        .withProfile(
                                "prusa-slicer",
                                "generic-marlin-200-0.4",
                                material,
                                new ProcessSettings(
                                        .2,
                                        walls,
                                        infill,
                                        auto,
                                        bed,
                                        45,
                                        gap,
                                        interfaces,
                                        brim,
                                        raft)));
    }

    GcodeJob slice(ModelSession s, String name) throws Exception {
        Files.createDirectories(artifacts);
        DocumentWriter.save(s.document(), artifacts.resolve(name + ".jcad"), true);
        var messages = new ArrayList<String>();
        var result =
                SliceService.slice(
                        new PrusaSlicerBackend(),
                        ManufacturingModel.evaluate(s.document(), MeshOptions.DEFAULT),
                        PrusaSlicerBackend.defaultExecutable(),
                        artifacts.resolve(name + ".gcode"),
                        true,
                        new Cancellation(),
                        messages::add,
                        SliceService.DIRECT);
        Files.write(artifacts.resolve(name + ".log"), messages);
        System.out.println(
                name
                        + ": model layers="
                        + result.job().modelLayers().size()
                        + ", support levels="
                        + support(result.job()).stream()
                                .map(GcodeJob.Segment::z1)
                                .distinct()
                                .sorted()
                                .toList());
        return result.job();
    }

    static List<GcodeJob.Segment> support(GcodeJob job) {
        return job.segments().stream()
                .filter(
                        s ->
                                s.deposits()
                                        && (s.role() == GcodeJob.Role.SUPPORT
                                                || s.role() == GcodeJob.Role.INTERFACE))
                .toList();
    }

    static boolean inside(GcodeJob.Segment s, double x0, double y0, double x1, double y1) {
        double x = (s.x0() + s.x1()) / 2, y = (s.y0() + s.y1()) / 2;
        return x > x0 && x < x1 && y > y0 && y < y1;
    }

    @Test
    void automaticManualAndBlockedSupportsSurviveProjectAndDocumentRoundTrip() throws Exception {
        var shelf = shelf(false);
        assertTrue(support(slice(shelf, "shelf-off")).isEmpty());
        process(shelf, 2, 20, true, false, .2, 3, 0, 0, "PLA");
        var automatic = slice(shelf, "shelf-auto");
        assertTrue(
                support(automatic).stream()
                        .anyMatch(s -> s.z1() > 4 && s.z1() < 6 && inside(s, 35, 33, 45, 67)));
        var cube = cube();
        process(cube, 2, 20, true, false, .2, 3, 0, 0, "PLA");
        assertTrue(support(slice(cube, "cube-auto")).isEmpty());
        process(shelf, 2, 20, false, false, .2, 3, 0, 0, "PLA");
        shelf.setPrintSetup(
                shelf.document()
                        .printSetup()
                        .withSupports(
                                List.of(
                                        new SupportVolume(
                                                "region-A",
                                                "roof",
                                                SupportVolume.Role.ENFORCER,
                                                10,
                                                0,
                                                0,
                                                20,
                                                20,
                                                12))));
        var enforced = slice(shelf, "shelf-enforced-A");
        assertTrue(
                support(enforced).stream()
                        .anyMatch(s -> s.z1() > 4 && s.z1() < 6 && inside(s, 35, 33, 45, 47)));
        assertFalse(
                support(enforced).stream()
                        .anyMatch(s -> s.z1() > 4 && s.z1() < 6 && inside(s, 35, 55, 45, 65)));
        process(shelf, 2, 20, true, false, .2, 3, 0, 0, "PLA");
        shelf.setPrintSetup(
                shelf.document()
                        .printSetup()
                        .withSupports(
                                List.of(
                                        new SupportVolume(
                                                "region-B",
                                                "roof",
                                                SupportVolume.Role.BLOCKER,
                                                10,
                                                20,
                                                0,
                                                20,
                                                20,
                                                12))));
        var blocked = slice(shelf, "shelf-blocked-B");
        assertFalse(
                support(blocked).stream()
                        .anyMatch(s -> s.z1() > 4 && s.z1() < 6 && inside(s, 35, 55, 45, 65)));
        var reopened =
                new ModelSession(DocumentReader.load(artifacts.resolve("shelf-blocked-B.jcad")));
        var again = slice(reopened, "shelf-reopened");
        assertEquals(support(blocked), support(again));
        var model = ManufacturingModel.evaluate(reopened.document(), MeshOptions.DEFAULT);
        Path project = artifacts.resolve("shelf-project.3mf");
        try (var output = Files.newOutputStream(project)) {
            PrusaProjectExporter.writeProject(model, output);
        }
        // Open the vendor project twice using the real receiver, without supplying a config file.
        for (int n = 1; n <= 2; n++) {
            var output = artifacts.resolve("project-reopen-" + n + ".gcode").toAbsolutePath();
            ProcessRunner.run(
                    List.of(
                            PrusaSlicerBackend.defaultExecutable().toString(),
                            "--dont-arrange",
                            "--no-ensure-on-bed",
                            "--merge",
                            "--export-gcode",
                            "--output",
                            output.toString(),
                            project.toAbsolutePath().toString()),
                    null,
                    new Cancellation(),
                    line -> {});
            var job = GcodeReader.read(output);
            assertEquals(support(blocked), support(job));
            assertEquals(blocked.modelLayers(), job.modelLayers());
        }
    }

    @Test
    void bedOnlyContactGapAndInterfaceSettingsChangeRealSupportPaths() throws Exception {
        var shelf = shelf(true);
        process(shelf, 2, 20, true, true, .2, 3, 0, 0, "PLA");
        var bed = slice(shelf, "recess-bed-only");
        assertFalse(support(bed).stream().anyMatch(s -> inside(s, 35, 35, 45, 65)));
        process(shelf, 2, 20, true, false, .2, 3, 0, 0, "PLA");
        var everywhere = slice(shelf, "recess-everywhere");
        var region = support(everywhere).stream().filter(s -> inside(s, 35, 35, 45, 65)).toList();
        assertFalse(region.isEmpty());
        double first = region.stream().mapToDouble(GcodeJob.Segment::z1).min().orElseThrow();
        assertTrue(first > 2 && first < 3, "support starts above lower shelf: " + first);
        var open = shelf(false);
        process(open, 2, 20, true, false, .2, 3, 0, 0, "PLA");
        var close = slice(open, "gap-02-interface-3");
        process(open, 2, 20, true, false, .6, 1, 0, 0, "PLA");
        var distant = slice(open, "gap-06-interface-1");
        double closeTop =
                support(close).stream().mapToDouble(GcodeJob.Segment::z1).max().orElseThrow();
        double distantTop =
                support(distant).stream().mapToDouble(GcodeJob.Segment::z1).max().orElseThrow();
        assertEquals(.2, 10 - closeTop, .001);
        assertEquals(.6, 10 - distantTop, .001);
        // With independent support heights, the pinned slicer emits three slab levels plus contact.
        assertEquals(
                4,
                support(close).stream()
                        .filter(s -> s.role() == GcodeJob.Role.INTERFACE)
                        .map(GcodeJob.Segment::z1)
                        .distinct()
                        .count());
        assertEquals(
                2,
                support(distant).stream()
                        .filter(s -> s.role() == GcodeJob.Role.INTERFACE)
                        .map(GcodeJob.Segment::z1)
                        .distinct()
                        .count());
    }

    @Test
    void wallsInfillPetgBrimAndRaftChangeActualDeposition() throws Exception {
        var cube = cube();
        process(cube, 2, 0, false, false, .2, 3, 0, 0, "PLA");
        var two = slice(cube, "cube-two-walls");
        process(cube, 4, 0, false, false, .2, 3, 0, 0, "PETG");
        var four = slice(cube, "cube-four-walls-petg");
        assertEquals(2, wallCrossings(two));
        assertEquals(4, wallCrossings(four));
        assertFalse(interior(two).stream().anyMatch(s -> s.role() == GcodeJob.Role.INFILL));
        assertEquals("PETG", four.report().get("material"));
        assertEquals("240.0", four.report().get("process.temperature"));
        process(cube, 4, 20, false, false, .2, 3, 0, 0, "PLA");
        var filled = slice(cube, "cube-filled");
        assertTrue(interior(filled).stream().anyMatch(s -> s.role() == GcodeJob.Role.INFILL));
        process(cube, 2, 20, false, false, .2, 3, 3, 0, "PLA");
        var brim = slice(cube, "cube-brim");
        assertTrue(
                brim.segments().stream()
                        .anyMatch(
                                s ->
                                        s.deposits()
                                                && s.role() == GcodeJob.Role.SKIRT_BRIM
                                                && s.x1() < 20));
        process(cube, 2, 20, false, false, .2, 3, 0, 3, "PLA");
        var raft = slice(cube, "cube-raft");
        assertTrue(raft.modelLayers().getFirst() > .2);
        assertTrue(support(raft).stream().anyMatch(s -> s.z1() < raft.modelLayers().getFirst()));
    }

    @Test
    void separateModelAndSupportStlsReimportAsOneUncenteredAssembly() throws Exception {
        Files.createDirectories(artifacts);
        var session = shelf(false);
        session.setPrintSetup(
                session.document()
                        .printSetup()
                        .withSupports(
                                List.of(
                                        new SupportVolume(
                                                "pillar-A",
                                                "roof",
                                                SupportVolume.Role.SOLID_SUPPORT,
                                                15,
                                                5,
                                                0,
                                                4,
                                                4,
                                                9.8))));
        double volume;
        try (var evaluation = session.evaluate()) {
            volume = evaluation.bodies().stream().mapToDouble(b -> b.solid().volume()).sum();
        }
        assertEquals(6400, volume, .01);
        var model = ManufacturingModel.evaluate(session.document(), MeshOptions.DEFAULT);
        Path roof = artifacts.resolve("handoff-model.stl"),
                leg = artifacts.resolve("handoff-leg.stl"),
                pillar = artifacts.resolve("handoff-supports.stl");
        try (var registry = new ExporterRegistry(artifacts.resolve("no-plugins"))) {
            ExportService.export(
                    registry,
                    "stl",
                    ModelViews.selectInstance(ModelViews.from(model), "roof"),
                    MeshOptions.DEFAULT,
                    Map.of(),
                    roof,
                    true);
            ExportService.export(
                    registry,
                    "stl",
                    ModelViews.selectInstance(ModelViews.from(model), "leg"),
                    MeshOptions.DEFAULT,
                    Map.of(),
                    leg,
                    true);
            ExportService.export(
                    registry,
                    "stl",
                    ModelViews.from(model, ExportSelection.Contents.SUPPORTS),
                    MeshOptions.DEFAULT,
                    Map.of(),
                    pillar,
                    true);
            ExportService.export(
                    registry,
                    "3mf",
                    ModelViews.from(model, ExportSelection.Contents.ALL),
                    MeshOptions.DEFAULT,
                    Map.of(),
                    artifacts.resolve("handoff-assembly.3mf"),
                    true);
        }
        Path config = artifacts.resolve("handoff.ini"),
                jobFile = artifacts.resolve("handoff.gcode").toAbsolutePath();
        // Independent STL readback, then a multipart project. Prusa's CLI --merge retains
        // separate objects, so it cannot replace the receiver's multipart import operation.
        var importedMeshes = new LinkedHashMap<String, MeshData>();
        importedMeshes.put("roof", readStl(roof));
        importedMeshes.put("leg", readStl(leg));
        importedMeshes.put("pillar", readStl(pillar));
        var setup =
                model.setup()
                        .withSupports(List.of())
                        .withInstances(
                                importedMeshes.keySet().stream()
                                        .map(id -> new BodyInstance(id, id, Transform.IDENTITY))
                                        .toList());
        var imported =
                new ManufacturingModel(
                        "stl-readback", "handoff", setup, model.profile(), importedMeshes);
        Path assembly = artifacts.resolve("handoff-reimported-project.3mf");
        try (var output = Files.newOutputStream(assembly)) {
            PrusaProjectExporter.writeProject(imported, output);
        }
        Files.writeString(config, PrusaConfiguration.ini(model));
        ProcessRunner.run(
                List.of(
                        PrusaSlicerBackend.defaultExecutable().toString(),
                        "--load",
                        config.toAbsolutePath().toString(),
                        "--merge",
                        "--dont-arrange",
                        "--no-ensure-on-bed",
                        "--export-gcode",
                        "--output",
                        jobFile.toString(),
                        assembly.toAbsolutePath().toString()),
                null,
                new Cancellation(),
                line -> {});
        var job = GcodeReader.read(jobFile);
        assertEquals(60, job.modelLayers().size());
        var underRoof =
                job.segments().stream()
                        .filter(
                                s ->
                                        s.deposits()
                                                && s.role().model()
                                                && s.z1() < 10
                                                && inside(s, 34.9, 34.9, 39.1, 39.1))
                        .toList();
        assertFalse(underRoof.isEmpty());
        assertEquals(
                9.8,
                underRoof.stream().mapToDouble(GcodeJob.Segment::z1).max().orElseThrow(),
                .001);
        assertTrue(underRoof.stream().anyMatch(s -> Math.abs(s.z1() - 5) < 1e-6));
        for (var s : job.segments())
            if (s.deposits() && s.role().model()) {
                assertTrue(s.x1() >= 19.999 && s.x1() <= 50.001, s.toString());
                assertTrue(s.y1() >= 29.999 && s.y1() <= 70.001, s.toString());
            }
        try (var evaluation = session.evaluate()) {
            assertEquals(
                    volume,
                    evaluation.bodies().stream().mapToDouble(b -> b.solid().volume()).sum(),
                    .001);
        }
    }

    @Test
    void portableCoreInstancesRetainScaleRotationAndHolesInRealReceiver() throws Exception {
        Files.createDirectories(artifacts);
        var session = PrintingTest.reference();
        var first = session.document().printSetup().instances().getFirst();
        session.setPrintSetup(
                session.document()
                        .printSetup()
                        .withInstances(
                                List.of(
                                        first,
                                        new BodyInstance(
                                                "rotated",
                                                first.bodyId(),
                                                Transform.translation(150, 30, 0)
                                                        .compose(Transform.rotation(0, 0, 90))))));
        var model = ManufacturingModel.evaluate(session.document(), MeshOptions.DEFAULT);
        Path core = artifacts.resolve("portable-two-instances.3mf");
        try (var registry = new ExporterRegistry(artifacts.resolve("no-plugins"))) {
            ExportService.export(
                    registry,
                    "3mf",
                    ModelViews.from(model),
                    MeshOptions.DEFAULT,
                    Map.of(),
                    core,
                    true);
        }
        Path config = artifacts.resolve("portable.ini");
        Files.writeString(config, PrusaConfiguration.ini(model));
        Path output = artifacts.resolve("portable-two-instances.gcode");
        ProcessRunner.run(
                List.of(
                        PrusaSlicerBackend.defaultExecutable().toString(),
                        "--load",
                        config.toAbsolutePath().toString(),
                        "--dont-arrange",
                        "--no-ensure-on-bed",
                        "--export-gcode",
                        "--output",
                        output.toAbsolutePath().toString(),
                        core.toAbsolutePath().toString()),
                null,
                new Cancellation(),
                line -> {});
        var job = GcodeReader.read(output);
        assertEquals(40, job.modelLayers().size());
        var layer =
                job.segments().stream()
                        .filter(
                                s ->
                                        s.deposits()
                                                && s.role().model()
                                                && Math.abs(s.z1() - 4) < 1e-6)
                        .toList();
        for (int part = 0; part < 2; part++) {
            final boolean rotated = part == 1;
            var paths = layer.stream().filter(s -> (s.x1() > 100) == rotated).toList();
            assertFalse(paths.isEmpty());
            double minX = rotated ? 110 : 20, maxX = rotated ? 150 : 80, maxY = rotated ? 90 : 70;
            assertEquals(
                    minX, paths.stream().mapToDouble(GcodeJob.Segment::x1).min().orElseThrow(), .3);
            assertEquals(
                    maxX, paths.stream().mapToDouble(GcodeJob.Segment::x1).max().orElseThrow(), .3);
            assertEquals(
                    30, paths.stream().mapToDouble(GcodeJob.Segment::y1).min().orElseThrow(), .3);
            assertEquals(
                    maxY, paths.stream().mapToDouble(GcodeJob.Segment::y1).max().orElseThrow(), .3);
            double holeX = rotated ? 140 : 30;
            for (var s : paths) {
                double dx = s.x1() - s.x0(), dy = s.y1() - s.y0();
                double t =
                        Math.max(
                                0,
                                Math.min(
                                        1,
                                        ((holeX - s.x0()) * dx + (40 - s.y0()) * dy)
                                                / (dx * dx + dy * dy)));
                assertTrue(
                        Math.hypot(s.x0() + t * dx - holeX, s.y0() + t * dy - 40) - s.width() / 2
                                >= 1.95,
                        "deposited bead enters transformed hole: " + s);
            }
        }
    }

    private static MeshData readStl(Path file) throws Exception {
        var data =
                java.nio.ByteBuffer.wrap(Files.readAllBytes(file))
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        data.position(80);
        int count = data.getInt();
        assertEquals(84 + 50 * count, data.capacity());
        double[] vertices = new double[9 * count];
        int[] triangles = new int[3 * count];
        for (int t = 0; t < count; t++) {
            data.position(data.position() + 12);
            for (int n = 0; n < 9; n++) vertices[9 * t + n] = data.getFloat();
            for (int n = 0; n < 3; n++) triangles[3 * t + n] = 3 * t + n;
            data.getShort();
        }
        var mesh = new MeshData(vertices, triangles, new int[count]);
        MeshValidation.requireClosed(mesh);
        return mesh;
    }

    static List<GcodeJob.Segment> interior(GcodeJob job) {
        return job.segments().stream()
                .filter(s -> s.deposits() && Math.abs(s.z1() - 5) < 1e-6)
                .toList();
    }

    static long wallCrossings(GcodeJob job) {
        return interior(job).stream()
                .filter(
                        s ->
                                (s.role() == GcodeJob.Role.PERIMETER
                                                || s.role() == GcodeJob.Role.EXTERNAL_PERIMETER)
                                        && s.x0() < 25
                                        && s.x1() < 25
                                        && Math.min(s.y0(), s.y1()) < 45
                                        && Math.max(s.y0(), s.y1()) > 45)
                .count();
    }
}
