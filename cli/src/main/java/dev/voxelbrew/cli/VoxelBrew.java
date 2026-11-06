package dev.voxelbrew.cli;

import dev.voxelbrew.engine.geometry.Solid;
import dev.voxelbrew.engine.mesh.MeshOptions;
import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.exporter.ExportSelection;
import dev.voxelbrew.exporter.ExportService;
import dev.voxelbrew.exporter.ExporterRegistry;
import dev.voxelbrew.exporter.api.DocumentSink;
import dev.voxelbrew.importer.api.DocumentSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
        name = "voxelbrew",
        description = "VoxelBrew parametric CAD; all dimensions are millimeters.",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class,
        subcommands = {
            VoxelBrew.Create.class,
            VoxelBrew.Rectangle.class,
            VoxelBrew.Circle.class,
            VoxelBrew.Extrude.class,
            VoxelBrew.Cut.class,
            VoxelBrew.Edit.class,
            VoxelBrew.Cursor.class,
            VoxelBrew.Repair.class,
            VoxelBrew.History.class,
            VoxelBrew.Inspect.class,
            VoxelBrew.NativeDemo.class
        })
@lombok.extern.slf4j.Slf4j
public final class VoxelBrew implements Runnable {

    @javax.inject.Inject
    VoxelBrew() {}

    @Spec private CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    @SuppressWarnings("try") // Keep the app image in place until the process has finished.
    public static void main(String[] args) {
        dev.voxelbrew.util.logging.Logging.initialize("cli");
        configureInstalledRuntime();
        int result;
        try (var lease =
                dev.voxelbrew.util.update.ApplicationImage.running(
                        dev.voxelbrew.util.update.ApplicationImage.Component.CLI)) {
            result = commandLine().execute(args);
        } catch (java.io.IOException failure) {
            System.err.println("ERROR: " + failure.getMessage());
            result = 1;
        }
        System.exit(result);
    }

    public static CommandLine commandLine() {
        return DaggerCliComponent.factory()
                .create(
                        dev.voxelbrew.util.di.ApplicationModule.system(
                                dev.voxelbrew.util.update.ApplicationImage.Component.CLI),
                        new dev.voxelbrew.exporter.di.FileIoModule())
                .commandLine();
    }

    static CommandLine commandLine(
            CommandLine.IFactory factory, dev.voxelbrew.util.i18n.Translations translations) {
        var cliCommand =
                new CommandLine(VoxelBrew.class, factory)
                        .addSubcommand("exporters", Exporters.class)
                        .addSubcommand("export", Export.class)
                        .setExecutionExceptionHandler(
                                (failure, command, parsed) -> {
                                    log.debug("Command failed", failure);
                                    command.getErr().println("ERROR: " + failure.getMessage());
                                    return 1;
                                });
        cliCommand
                .getCommandSpec()
                .usageMessage()
                .description(translations.text("app.description"));
        PrintingCommands.register(cliCommand);
        ImportCommands.register(cliCommand);
        FeatureCommands.register(cliCommand);
        SketchCommands.register(cliCommand);
        ParameterCommands.register(cliCommand);
        UpdateCommands.register(cliCommand);
        return cliCommand;
    }

    @Command(
            name = "exporters",
            mixinStandardHelpOptions = true,
            description = "List built-in and external exporters")
    static final class Exporters implements Callable<Integer> {
        private final dev.voxelbrew.exporter.ExporterRegistry.Factory exporters;

        @javax.inject.Inject
        Exporters(dev.voxelbrew.exporter.ExporterRegistry.Factory exporters) {
            this.exporters = exporters;
        }

        @Option(names = "--plugin-dir")
        Path pluginDirectory = ExporterRegistry.defaultDirectory();

        @Spec CommandSpec spec;

        @Override
        public Integer call() throws Exception {
            try (var registry = exporters.open(pluginDirectory)) {
                for (var descriptor : registry.descriptors()) {
                    spec.commandLine()
                            .getOut()
                            .println(
                                    descriptor.id()
                                            + "\t"
                                            + descriptor.name()
                                            + "\tAPI="
                                            + descriptor.apiVersion()
                                            + "\toptions="
                                            + descriptor.options());
                }
                registry.diagnostics().forEach(spec.commandLine().getErr()::println);
            }
            return 0;
        }
    }

    @Command(
            name = "export",
            mixinStandardHelpOptions = true,
            description = "Export a complete document using a registered provider")
    static final class Export implements Callable<Integer> {
        private final DocumentSource documents;
        private final dev.voxelbrew.exporter.ExporterRegistry.Factory exporters;

        @javax.inject.Inject
        Export(
                DocumentSource documents,
                dev.voxelbrew.exporter.ExporterRegistry.Factory exporters) {
            this.documents = documents;
            this.exporters = exporters;
        }

        @Parameters(index = "0")
        Path file;

        @Option(names = "--format", required = true)
        String format;

        @Option(names = "--output", required = true)
        Path output;

        @Option(names = "--body")
        String body;

        @Option(
                names = "--instance",
                description = "Selected build instance ID; requires BUILD_PLATE space")
        String instance;

        @Option(
                names = "--space",
                defaultValue = "DESIGN",
                description = "DESIGN or BUILD_PLATE; all coordinates are millimeters")
        ExportSelection.Space space;

        @Option(
                names = "--selection",
                defaultValue = "MODEL",
                description =
                        "MODEL (default), SUPPORTS or ALL; support solids require BUILD_PLATE")
        dev.voxelbrew.engine.printing.ManufacturingModel.Selection contents;

        @Option(names = "--plugin-dir")
        Path pluginDirectory = ExporterRegistry.defaultDirectory();

        @Option(
                names = "--chord-tolerance",
                defaultValue = "0.02",
                description = "Absolute chordal tolerance (mm)")
        double chord;

        @Option(
                names = "--angular-tolerance",
                defaultValue = "5",
                description = "Angular tolerance (degrees)")
        double angle;

        @Option(names = "--option", description = "Provider option KEY=VALUE; repeatable")
        Map<String, String> options = new java.util.LinkedHashMap<>();

        @Option(names = "--overwrite")
        boolean overwrite;

        @Spec CommandSpec spec;

        @Override
        public Integer call() throws Exception {
            var document = documents.load(file);
            try (var evaluation = Evaluator.evaluate(document, pluginDirectory);
                    var registry = exporters.open(pluginDirectory)) {
                registry.diagnostics().forEach(spec.commandLine().getErr()::println);
                ModelView view =
                        new ExportSelection(space, contents, body, instance)
                                .view(document, evaluation, new MeshOptions(chord, angle));
                ExportService.export(
                        registry,
                        format,
                        view,
                        new MeshOptions(chord, angle),
                        options,
                        output,
                        overwrite);
                spec.commandLine()
                        .getOut()
                        .println("Exported " + format + " to " + output.toAbsolutePath());
            }
            return 0;
        }
    }

    private static void configureInstalledRuntime() {
        if (System.getProperty("voxelbrew.native.path") != null) {
            return;
        }
        try {
            Path jar =
                    Path.of(
                            VoxelBrew.class
                                    .getProtectionDomain()
                                    .getCodeSource()
                                    .getLocation()
                                    .toURI());
            Path runtime = jar.getParent().resolve("native");
            if (!Files.isDirectory(runtime))
                runtime = jar.getParent().getParent().resolve("native");
            if (Files.isDirectory(runtime)) {
                System.setProperty("voxelbrew.native.path", runtime.toString());
            }
        } catch (java.net.URISyntaxException ignored) {
            /* NativeKernel supplies the load diagnostic. */
        }
    }

    abstract static class FileCommand implements Callable<Integer> {
        final DocumentSource documents;
        final DocumentSink documentWriter;

        FileCommand(DocumentSource documents, DocumentSink documentWriter) {
            this.documents = documents;
            this.documentWriter = documentWriter;
        }

        @Parameters(index = "0", description = "Editable .jcad file")
        Path file;

        @Option(names = "--plugin-dir", description = "Trusted feature and service JAR directory")
        Path pluginDirectory = ExporterRegistry.defaultDirectory();

        @Spec CommandSpec spec;

        ModelSession session() throws java.io.IOException {
            return new ModelSession(documents.load(file));
        }

        int save(ModelSession session) throws java.io.IOException {
            documentWriter.save(session.document(), file, true);
            try (Evaluation evaluation = session.evaluate(pluginDirectory)) {
                if (!evaluation.valid()) {
                    evaluation.diagnostics().stream()
                            .filter(
                                    d ->
                                            d.state() == Evaluation.State.FAILED
                                                    || d.state() == Evaluation.State.BLOCKED)
                            .forEach(
                                    d ->
                                            spec.commandLine()
                                                    .getErr()
                                                    .println(
                                                            d.featureId()
                                                                    + " "
                                                                    + d.state()
                                                                    + " "
                                                                    + d.message()));
                    return 1;
                }
            }
            return 0;
        }

        Feature.ProfileRef profileRef(ModelSession session, String sketchId) {
            return session.document().features().stream()
                    .filter(f -> f.id().equals(sketchId) && f instanceof Feature.Sketch)
                    .map(f -> (Feature.Sketch) f)
                    .map(s -> new Feature.ProfileRef(s.id(), s.profileId()))
                    .findFirst()
                    .orElseThrow(() -> new ModelException(sketchId, "sketch does not exist"));
        }
    }

    abstract static class AddFeature extends FileCommand {

        AddFeature(DocumentSource documents, DocumentSink documentWriter) {
            super(documents, documentWriter);
        }

        @Option(names = "--id", description = "Stable feature ID (default: generated UUID)")
        String id = UUID.randomUUID().toString();
    }

    abstract static class AddSketch extends AddFeature {

        AddSketch(DocumentSource documents, DocumentSink documentWriter) {
            super(documents, documentWriter);
        }

        @Option(names = "--offset-z", defaultValue = "0", description = "XY plane offset, mm")
        double z;

        @Option(
                names = "--plane-id",
                description =
                        "Stable plane ID (default: origin-xy or a sketch-specific offset plane)")
        String planeId;

        @Option(
                names = "--profile-id",
                description = "Stable profile ID (default: <feature-id>.profile)")
        String profileId;

        int sketch(Feature.Profile profile) throws java.io.IOException {
            var session = session();
            session.add(
                    new Feature.Sketch(
                            id,
                            profileId == null ? id + ".profile" : profileId,
                            new Feature.Plane(
                                    planeId == null
                                            ? (z == 0 ? "origin-xy" : id + ".plane")
                                            : planeId,
                                    z),
                            profile));
            return save(session);
        }
    }

    @Command(
            name = "new",
            description = "Create an empty editable document.",
            mixinStandardHelpOptions = true)
    static final class Create extends FileCommand {

        @javax.inject.Inject
        Create(DocumentSource documents, DocumentSink documentWriter) {
            super(documents, documentWriter);
        }

        @Option(
                names = "--overwrite",
                description = "Explicitly replace an existing supported document")
        boolean overwrite;

        @Override
        public Integer call() throws Exception {
            documentWriter.save(CadDocument.empty(), file, overwrite);
            return 0;
        }
    }

    @Command(
            name = "rectangle",
            description = "Add a dimensioned rectangle sketch on XY or an offset plane.",
            mixinStandardHelpOptions = true)
    static final class Rectangle extends AddSketch {

        @javax.inject.Inject
        Rectangle(DocumentSource documents, DocumentSink documentWriter) {
            super(documents, documentWriter);
        }

        @Option(names = "--x", defaultValue = "0")
        double x;

        @Option(names = "--y", defaultValue = "0")
        double y;

        @Option(names = "--width", required = true)
        double width;

        @Option(names = "--height", required = true)
        double height;

        @Override
        public Integer call() throws Exception {
            return sketch(new Feature.Rectangle(x, y, width, height));
        }
    }

    @Command(
            name = "circle",
            description = "Add a circle by center and diameter, mm.",
            mixinStandardHelpOptions = true)
    static final class Circle extends AddSketch {

        @javax.inject.Inject
        Circle(DocumentSource documents, DocumentSink documentWriter) {
            super(documents, documentWriter);
        }

        @Option(names = "--center-x", required = true)
        double x;

        @Option(names = "--center-y", required = true)
        double y;

        @Option(names = "--diameter", required = true)
        double diameter;

        @Override
        public Integer call() throws Exception {
            return sketch(new Feature.Circle(x, y, diameter));
        }
    }

    @Command(
            name = "extrude",
            description = "Extrude an earlier profile as a new body; distance is signed mm.",
            mixinStandardHelpOptions = true)
    static final class Extrude extends AddFeature {

        @javax.inject.Inject
        Extrude(DocumentSource documents, DocumentSink documentWriter) {
            super(documents, documentWriter);
        }

        @Option(names = "--sketch", required = true)
        String sketch;

        @Option(names = "--distance", required = true)
        double distance;

        @Override
        public Integer call() throws Exception {
            var session = session();
            session.add(new Feature.Extrude(id, profileRef(session, sketch), distance));
            return save(session);
        }
    }

    @Command(
            name = "cut",
            description = "Cut a circular profile through the complete current Z extent.",
            mixinStandardHelpOptions = true)
    static final class Cut extends AddFeature {

        @javax.inject.Inject
        Cut(DocumentSource documents, DocumentSink documentWriter) {
            super(documents, documentWriter);
        }

        @Option(names = "--sketch", required = true)
        String sketch;

        @Option(
                names = "--body-feature",
                required = true,
                description = "Earlier feature providing the body output")
        String body;

        @Option(
                names = "--body-output",
                defaultValue = "body",
                description = "Named body output, including negative/positive split halves")
        String output;

        @Override
        public Integer call() throws Exception {
            var session = session();
            session.add(
                    new Feature.ThroughCut(
                            id, profileRef(session, sketch), new Feature.BodyRef(body, output)));
            return save(session);
        }
    }

    @Command(
            name = "edit",
            description = "Change one dimension as a single engine transaction.",
            mixinStandardHelpOptions = true)
    static final class Edit extends FileCommand {

        @javax.inject.Inject
        Edit(DocumentSource documents, DocumentSink documentWriter) {
            super(documents, documentWriter);
        }

        @Option(names = "--feature", required = true)
        String feature;

        @Option(
                names = "--field",
                required = true,
                description = "width, height, x, y, centerX, centerY, diameter, offsetZ, distance")
        String field;

        @Option(names = "--value")
        Double value;

        @Option(
                names = "--expression",
                description = "Formula or named parameter (instead of --value)")
        String expression;

        @Override
        public Integer call() throws Exception {
            var session = session();
            if ((value == null) == (expression == null))
                throw new IllegalArgumentException("Choose exactly one of --value or --expression");
            if (expression != null) session.bindExpression(feature, field, expression);
            else session.editDimension(feature, field, value, pluginDirectory);
            return save(session);
        }
    }

    @Command(
            name = "cursor",
            description = "Set evaluated feature count without changing the feature list.",
            mixinStandardHelpOptions = true)
    static final class Cursor extends FileCommand {

        @javax.inject.Inject
        Cursor(DocumentSource documents, DocumentSink documentWriter) {
            super(documents, documentWriter);
        }

        @Option(
                names = "--position",
                required = true,
                description = "Number of features to evaluate (0 through history size)")
        int position;

        @Override
        public Integer call() throws Exception {
            var session = session();
            session.setCursor(position);
            return save(session);
        }
    }

    @Command(
            name = "repair",
            description =
                    "Repair an extrusion/cut sketch reference or cut body input, preserving IDs.",
            mixinStandardHelpOptions = true)
    static final class Repair extends FileCommand {

        @javax.inject.Inject
        Repair(DocumentSource documents, DocumentSink documentWriter) {
            super(documents, documentWriter);
        }

        @Option(names = "--feature", required = true)
        String feature;

        @Option(names = "--sketch")
        String sketch;

        @Option(names = "--body-feature")
        String body;

        @Option(names = "--body-output", description = "Named output: body, negative or positive")
        String bodyOutput;

        @Override
        public Integer call() throws Exception {
            var session = session();
            Feature original =
                    session.document().features().stream()
                            .filter(f -> f.id().equals(feature))
                            .findFirst()
                            .orElseThrow(
                                    () -> new ModelException(feature, "feature does not exist"));
            if (original instanceof Feature.Extrude e && sketch != null && body == null) {
                session.replace(
                        new Feature.Extrude(e.id(), profileRef(session, sketch), e.distance()));
            } else if (original instanceof Feature.ThroughCut c
                    && (sketch != null || body != null || bodyOutput != null)) {
                session.replace(
                        new Feature.ThroughCut(
                                c.id(),
                                sketch == null ? c.profile() : profileRef(session, sketch),
                                new Feature.BodyRef(
                                        body == null ? c.body().featureId() : body,
                                        bodyOutput == null ? c.body().outputName() : bodyOutput)));
            } else if (original instanceof Feature.SplitBody s
                    && (body != null || bodyOutput != null)) {
                session.replace(
                        new Feature.SplitBody(
                                s.id(),
                                new Feature.BodyRef(
                                        body == null ? s.body().featureId() : body,
                                        bodyOutput == null ? s.body().outputName() : bodyOutput),
                                s.plane()));
            } else {
                throw new ModelException(
                        feature, "specify a supported --sketch or --body-feature repair");
            }
            return save(session);
        }
    }

    @Command(
            name = "history",
            description = "List stable feature IDs, definitions and rebuild diagnostics.",
            mixinStandardHelpOptions = true)
    static final class History extends FileCommand {

        @javax.inject.Inject
        History(DocumentSource documents, DocumentSink documentWriter) {
            super(documents, documentWriter);
        }

        @Override
        public Integer call() throws Exception {
            var session = session();
            spec.commandLine()
                    .getOut()
                    .printf(
                            "document=%s units=mm cursor=%d features=%d%n",
                            session.document().id(),
                            session.document().cursor(),
                            session.document().features().size());
            try (var evaluation = session.evaluate(pluginDirectory)) {
                for (int i = 0; i < session.document().features().size(); i++) {
                    var diagnostic = evaluation.diagnostics().get(i);
                    spec.commandLine()
                            .getOut()
                            .printf(
                                    "%d %s %s %s%n",
                                    i + 1,
                                    diagnostic.state(),
                                    session.document().features().get(i),
                                    diagnostic.message());
                }
                return evaluation.valid() ? 0 : 1;
            }
        }
    }

    @Command(
            name = "inspect",
            description = "Rebuild and measure active solids, or classify a point (in mm).",
            mixinStandardHelpOptions = true)
    static final class Inspect extends FileCommand {

        @javax.inject.Inject
        Inspect(DocumentSource documents, DocumentSink documentWriter) {
            super(documents, documentWriter);
        }

        @Option(
                names = "--point",
                split = ",",
                arity = "1",
                description = "Classify x,y,z in each body")
        double[] point;

        @Override
        public Integer call() throws Exception {
            if (point != null && point.length != 3) {
                throw new IllegalArgumentException("--point requires x,y,z");
            }
            var session = session();
            try (var evaluation = session.evaluate(pluginDirectory)) {
                spec.commandLine()
                        .getOut()
                        .printf(
                                "document=%s kernel=%s units=mm cursor=%d%n",
                                session.document().id(),
                                Solid.kernelVersion(),
                                session.document().cursor());
                for (var body : evaluation.bodies()) {
                    var b = body.solid().bounds();
                    spec.commandLine()
                            .getOut()
                            .printf(
                                    Locale.ROOT,
                                    "body=%s output=%s/%s volume=%.12f bounds=%.9f,%.9f,%.9f,%.9f,%.9f,%.9f%n",
                                    body.id(),
                                    body.output().featureId(),
                                    body.output().outputName(),
                                    body.solid().volume(),
                                    b.minX(),
                                    b.minY(),
                                    b.minZ(),
                                    b.maxX(),
                                    b.maxY(),
                                    b.maxZ());
                    if (point != null) {
                        spec.commandLine()
                                .getOut()
                                .println(
                                        "contains="
                                                + body.solid()
                                                        .contains(point[0], point[1], point[2]));
                    }
                }
                spec.commandLine().getOut().println("bodyCount=" + evaluation.bodies().size());
                return 0;
            }
        }
    }

    @Command(
            name = "native-demo",
            description = "Run the M0 real-kernel reference smoke demo.",
            mixinStandardHelpOptions = true)
    static final class NativeDemo implements Callable<Integer> {

        @javax.inject.Inject
        NativeDemo() {}

        @Override
        public Integer call() {
            try (Solid plate = Solid.rectanglePrism(0, 0, 0, 60, 40, 8);
                    Solid drilled = plate.cutThroughCircle(10, 10, 4)) {
                System.out.printf(
                        Locale.ROOT,
                        "OCCT %s%nvolume=%.12f mm^3%nbounds=%s%nthroughHole=%s%n",
                        Solid.kernelVersion(),
                        drilled.volume(),
                        drilled.bounds(),
                        !drilled.contains(10, 10, 4));
            }
            return 0;
        }
    }
}
