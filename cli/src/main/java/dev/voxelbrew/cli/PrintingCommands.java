package dev.voxelbrew.cli;

import dev.voxelbrew.engine.mesh.MeshOptions;
import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.engine.printing.*;
import dev.voxelbrew.exporter.DocumentOutput;
import dev.voxelbrew.importer.api.DocumentSource;
import dev.voxelbrew.slicer.api.PrinterProfileCatalog;
import dev.voxelbrew.slicer.api.SlicerCatalog;
import dev.voxelbrew.slicer.api.Slicing;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import picocli.CommandLine;
import picocli.CommandLine.*;

final class PrintingCommands {
    private PrintingCommands() {}

    static void register(CommandLine cli) {
        cli.addSubcommand("split", Split.class);
        cli.addSubcommand("place", Place.class);
        cli.addSubcommand("duplicate", Duplicate.class);
        cli.addSubcommand("lay-on-face", LayOnFace.class);
        cli.addSubcommand("print-settings", Settings.class);
        cli.addSubcommand("support", Support.class);
        cli.addSubcommand("remove-instance", RemoveInstance.class);
        cli.addSubcommand("remove-support", RemoveSupport.class);
        cli.addSubcommand("print-setup", Setup.class);
        cli.addSubcommand("validate", Validate.class);
        cli.addSubcommand("slicers", Slicers.class);
        cli.addSubcommand("printer-profiles", Printers.class);
        cli.addSubcommand("slice", Slice.class);
        cli.addSubcommand("inspect-job", InspectJob.class);
    }

    @Command(
            name = "split",
            mixinStandardHelpOptions = true,
            description = "Split an earlier body with a plane; outputs are negative and positive.")
    static final class Split extends VoxelBrew.AddFeature {

        @javax.inject.Inject
        Split(DocumentSource documents, DocumentOutput documentWriter) {
            super(documents, documentWriter);
        }

        @Option(names = "--body-feature", required = true)
        String body;

        @Option(names = "--body-output", defaultValue = "body")
        String output;

        @Option(names = "--axis", defaultValue = "X", description = "Plane normal: X, Y or Z")
        Axis axis;

        @Option(names = "--offset", required = true, description = "Plane offset in design-space mm")
        double offset;

        enum Axis {
            X,
            Y,
            Z
        }

        @Override
        public Integer call() throws Exception {
            var session = session();
            session.add(new Feature.SplitBody(
                    id,
                    new Feature.BodyRef(body, output),
                    new Feature.SplitPlane(
                            axis == Axis.X ? 1 : 0, axis == Axis.Y ? 1 : 0, axis == Axis.Z ? 1 : 0, offset)));
            return save(session);
        }
    }

    @Command(
            name = "place",
            mixinStandardHelpOptions = true,
            description =
                    "Add or replace a build instance; rigid placement is separate from design coordinates. Units: mm/degrees.")
    static final class Place extends VoxelBrew.FileCommand {

        @javax.inject.Inject
        Place(DocumentSource documents, DocumentOutput documentWriter) {
            super(documents, documentWriter);
        }

        @Option(names = "--instance", required = true)
        String id;

        @Option(names = "--body", required = true)
        String body;

        @Option(names = "--x", defaultValue = "0")
        double x;

        @Option(names = "--y", defaultValue = "0")
        double y;

        @Option(names = "--z", defaultValue = "0")
        double z;

        @Option(names = "--rotate-x", defaultValue = "0")
        double rx;

        @Option(names = "--rotate-y", defaultValue = "0")
        double ry;

        @Option(names = "--rotate-z", defaultValue = "0")
        double rz;

        @Override
        public Integer call() throws Exception {
            var session = session();
            var setup = session.document().printSetup();
            var instances = new ArrayList<>(setup.instances());
            instances.removeIf(i -> i.id().equals(id));
            instances.add(
                    new BodyInstance(id, body, Transform.translation(x, y, z).compose(Transform.rotation(rx, ry, rz))));
            session.setPrintSetup(setup.withInstances(instances));
            return save(session);
        }
    }

    @Command(
            name = "duplicate",
            mixinStandardHelpOptions = true,
            description = "Duplicate a build instance and its attached support instructions.")
    static final class Duplicate extends VoxelBrew.FileCommand {

        @javax.inject.Inject
        Duplicate(DocumentSource documents, DocumentOutput documentWriter) {
            super(documents, documentWriter);
        }

        @Option(names = "--instance", required = true)
        String source;

        @Option(names = "--id", required = true)
        String id;

        @Option(names = "--offset-x", defaultValue = "70", description = "Translation relative to source (mm)")
        double x;

        @Option(names = "--offset-y", defaultValue = "0")
        double y;

        @Option(names = "--offset-z", defaultValue = "0")
        double z;

        @Override
        public Integer call() throws Exception {
            var session = session();
            var setup = session.document().printSetup();
            var original = instance(setup, source);
            var instances = new ArrayList<>(setup.instances());
            instances.add(new BodyInstance(
                    id, original.bodyId(), Transform.translation(x, y, z).compose(original.transform())));
            var supports = new ArrayList<>(setup.supports());
            for (var s : setup.supports())
                if (s.instanceId().equals(source))
                    supports.add(new SupportVolume(
                            "support-" + session.document().uuidGenerator().next(),
                            id,
                            s.role(),
                            s.x(),
                            s.y(),
                            s.z(),
                            s.width(),
                            s.depth(),
                            s.height()));
            session.setPrintSetup(setup.withInstances(instances).withSupports(supports));
            return save(session);
        }
    }

    @Command(
            name = "lay-on-face",
            mixinStandardHelpOptions = true,
            description = "Lay an evaluated planar face on bed Z=0. Face IDs belong only to the current revision.")
    static final class LayOnFace extends VoxelBrew.FileCommand {

        @javax.inject.Inject
        LayOnFace(DocumentSource documents, DocumentOutput documentWriter) {
            super(documents, documentWriter);
        }

        @Option(names = "--instance", required = true)
        String id;

        @Option(names = "--face", required = true)
        int face;

        @Override
        public Integer call() throws Exception {
            var session = session();
            var setup = session.document().printSetup();
            var selected = instance(setup, id);
            var model = dev.voxelbrew.engine.printing.ManufacturingModels.evaluate(
                    session.document(), MeshOptions.DEFAULT, pluginDirectory);
            var transform = MeshGeometry.layOnFace(model.mesh(selected.bodyId()), face, selected.transform());
            session.setPrintSetup(setup.withInstances(setup.instances().stream()
                    .map(i -> i.id().equals(id) ? new BodyInstance(i.id(), i.bodyId(), transform) : i)
                    .toList()));
            return save(session);
        }
    }

    @Command(
            name = "print-settings",
            mixinStandardHelpOptions = true,
            description = "Edit the persisted printer, material and process. Unspecified values are retained.")
    static final class Settings extends VoxelBrew.FileCommand {

        @javax.inject.Inject
        Settings(DocumentSource documents, DocumentOutput documentWriter, PrinterProfileCatalog printers) {
            super(documents, documentWriter);
            this.printers = printers;
        }

        private final PrinterProfileCatalog printers;

        @Option(names = "--backend")
        String backend;

        @Option(names = "--printer-profile")
        String printer;

        @Option(names = "--material", description = "PLA or PETG")
        String material;

        @Option(names = "--layer-height", description = "First and subsequent layer height (mm)")
        Double height;

        @Option(names = "--walls")
        Integer walls;

        @Option(names = "--infill", description = "Infill percent")
        Double infill;

        @Option(names = "--supports", negatable = true)
        Boolean supports;

        @Option(names = "--bed-only", negatable = true)
        Boolean bedOnly;

        @Option(names = "--support-threshold", description = "Overhang threshold in degrees from horizontal")
        Double threshold;

        @Option(names = "--contact-gap", description = "Support contact gap (mm)")
        Double gap;

        @Option(names = "--interface-layers")
        Integer interfaces;

        @Option(names = "--brim", description = "Brim width (mm)")
        Double brim;

        @Option(names = "--raft-layers")
        Integer raft;

        @Override
        public Integer call() throws Exception {
            var session = session();
            var setup = session.document().printSetup();
            var p = setup.process();
            var next = new ProcessSettings(
                    or(height, p.layerHeight()),
                    or(walls, p.walls()),
                    or(infill, p.infillPercent()),
                    or(supports, p.automaticSupports()),
                    or(bedOnly, p.supportsOnBedOnly()),
                    or(threshold, p.supportThreshold()),
                    or(gap, p.supportContactGap()),
                    or(interfaces, p.interfaceLayers()),
                    or(brim, p.brimWidth()),
                    or(raft, p.raftLayers()));
            session.setPrintSetup(setup.withProfile(
                    or(backend, setup.backendId()),
                    or(printer, setup.printerId()),
                    or(material, setup.materialId()),
                    next));
            if (printer != null && !printer.startsWith("generic-marlin-rect-"))
                session.setPrintSetup(session.document()
                        .printSetup()
                        .withPrinter(printers.load().require(printer)));
            PrintProfile.resolve(session.document().printSetup());
            return save(session);
        }
    }

    @Command(
            name = "support",
            mixinStandardHelpOptions = true,
            description =
                    "Add or replace a body-coordinate blocker, enforcer or explicit support pillar attached to a build instance.")
    static final class Support extends VoxelBrew.FileCommand {

        @javax.inject.Inject
        Support(DocumentSource documents, DocumentOutput documentWriter) {
            super(documents, documentWriter);
        }

        @Option(names = "--id", required = true)
        String id;

        @Option(names = "--instance", required = true)
        String instance;

        @Option(names = "--role", required = true, description = "BLOCKER, ENFORCER or SOLID_SUPPORT")
        SupportVolume.Role role;

        @Option(names = "--x", defaultValue = "0")
        double x;

        @Option(names = "--y", defaultValue = "0")
        double y;

        @Option(names = "--z", defaultValue = "0")
        double z;

        @Option(names = "--width", required = true)
        double width;

        @Option(names = "--depth", required = true)
        double depth;

        @Option(names = "--height", required = true)
        double height;

        @Override
        public Integer call() throws Exception {
            var session = session();
            var setup = session.document().printSetup();
            var volumes = new ArrayList<>(setup.supports());
            volumes.removeIf(s -> s.id().equals(id));
            volumes.add(new SupportVolume(id, instance, role, x, y, z, width, depth, height));
            session.setPrintSetup(setup.withSupports(volumes));
            return save(session);
        }
    }

    @Command(
            name = "remove-instance",
            mixinStandardHelpOptions = true,
            description = "Remove a build instance; keep attached instructions available for repair.")
    static final class RemoveInstance extends VoxelBrew.FileCommand {

        @javax.inject.Inject
        RemoveInstance(DocumentSource documents, DocumentOutput documentWriter) {
            super(documents, documentWriter);
        }

        @Option(names = "--instance", required = true)
        String id;

        @Override
        public Integer call() throws Exception {
            var session = session();
            var setup = session.document().printSetup();
            instance(setup, id);
            session.setPrintSetup(setup.withInstances(
                    setup.instances().stream().filter(i -> !i.id().equals(id)).toList()));
            return save(session);
        }
    }

    @Command(
            name = "remove-support",
            mixinStandardHelpOptions = true,
            description = "Remove a support instruction or explicit pillar.")
    static final class RemoveSupport extends VoxelBrew.FileCommand {

        @javax.inject.Inject
        RemoveSupport(DocumentSource documents, DocumentOutput documentWriter) {
            super(documents, documentWriter);
        }

        @Option(names = "--id", required = true)
        String id;

        @Override
        public Integer call() throws Exception {
            var session = session();
            var setup = session.document().printSetup();
            if (setup.supports().stream().noneMatch(s -> s.id().equals(id)))
                throw new IllegalArgumentException("Unknown support " + id);
            session.setPrintSetup(setup.withSupports(
                    setup.supports().stream().filter(s -> !s.id().equals(id)).toList()));
            return save(session);
        }
    }

    @Command(
            name = "print-setup",
            mixinStandardHelpOptions = true,
            description = "List persisted build placements, profiles and support instructions.")
    static final class Setup extends VoxelBrew.FileCommand {

        @javax.inject.Inject
        Setup(DocumentSource documents, DocumentOutput documentWriter) {
            super(documents, documentWriter);
        }

        @Override
        public Integer call() throws Exception {
            var setup = session().document().printSetup();
            var out = spec.commandLine().getOut();
            out.println(setup.backendId()
                    + " printer="
                    + setup.printerId()
                    + " material="
                    + setup.materialId()
                    + " process="
                    + setup.process());
            setup.instances().forEach(out::println);
            setup.supports().forEach(out::println);
            return 0;
        }
    }

    @Command(
            name = "validate",
            mixinStandardHelpOptions = true,
            description = "Validate build-space meshes, bed polygon, overlap, height and support attachments.")
    static final class Validate extends VoxelBrew.FileCommand {

        @javax.inject.Inject
        Validate(DocumentSource documents, DocumentOutput documentWriter) {
            super(documents, documentWriter);
        }

        @Override
        public Integer call() throws Exception {
            var result = PrintValidation.validate(dev.voxelbrew.engine.printing.ManufacturingModels.evaluate(
                    session().document(), MeshOptions.DEFAULT, pluginDirectory));
            result.meshes().forEach((id, report) -> spec.commandLine().getOut().println(id + " " + report));
            result.diagnostics().forEach(spec.commandLine().getErr()::println);
            spec.commandLine().getOut().println(result.valid() ? "VALID" : "INVALID");
            return result.valid() ? 0 : 1;
        }
    }

    @Command(
            name = "printer-profiles",
            mixinStandardHelpOptions = true,
            description = "List bundled and custom printer hardware presets.")
    @RequiredArgsConstructor(access = AccessLevel.PACKAGE, onConstructor_ = @javax.inject.Inject)
    static final class Printers implements Callable<Integer> {
        private final PrinterProfileCatalog printers;

        @Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() {
            var result = printers.load();
            for (var entry : result.entries()) {
                var p = entry.definition();
                spec.commandLine()
                        .getOut()
                        .printf(
                                java.util.Locale.ROOT,
                                "%s\t%s\t%.1f mm height\t%.2f mm nozzle\t%d tools\t%s%n",
                                p.printer().id(),
                                entry,
                                p.printer().usableHeight(),
                                p.printer().nozzleMm(),
                                p.toolCount(),
                                p.genericGcode() ? "generic-gcode" : "layout-only");
            }
            result.diagnostics().forEach(spec.commandLine().getErr()::println);
            return 0;
        }
    }

    @Command(
            name = "slicers",
            mixinStandardHelpOptions = true,
            description = "List versioned backend capabilities; unsupported features are not silently substituted.")
    @RequiredArgsConstructor(access = AccessLevel.PACKAGE, onConstructor_ = @javax.inject.Inject)
    static final class Slicers implements Callable<Integer> {
        private final SlicerCatalog.Factory slicers;

        @Option(names = "--plugin-dir")
        Path plugins = dev.voxelbrew.util.config.ApplicationPaths.plugins();

        @Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() throws Exception {
            try (var registry = slicers.open(plugins)) {
                registry.descriptors().forEach(spec.commandLine().getOut()::println);
                registry.diagnostics().forEach(spec.commandLine().getErr()::println);
            }
            return 0;
        }
    }

    @Command(
            name = "slice",
            mixinStandardHelpOptions = true,
            description =
                    "Slice the complete persisted build setup with its explicit profiles; publish text G-code atomically.")
    static final class Slice extends VoxelBrew.FileCommand {
        private final SlicerCatalog.Factory slicers;
        private final dev.voxelbrew.exporter.api.ExporterCatalog.Factory exporters;

        @javax.inject.Inject
        Slice(
                DocumentSource documents,
                DocumentOutput documentWriter,
                SlicerCatalog.Factory slicers,
                dev.voxelbrew.exporter.api.ExporterCatalog.Factory exporters,
                Slicing slicing,
                @javax.inject.Named("slicerExecutable") Path defaultExecutable) {
            super(documents, documentWriter);
            this.slicers = slicers;
            this.exporters = exporters;
            this.slicing = slicing;
        }

        @Option(names = "--output", required = true)
        Path output;

        @Option(names = "--slicer", description = "Path to the pinned slicer executable")
        Path executable;

        @Option(names = "--slicer-option", description = "Backend configuration key=value (repeatable)")
        Map<String, String> slicerOptions = new java.util.LinkedHashMap<>();

        @Option(names = "--printer", description = "Saved printer ID; omit for profile-only G-code")
        String printerConnection;

        private final Slicing slicing;

        @Option(names = "--overwrite")
        boolean overwrite;

        @Override
        public Integer call() throws Exception {
            var cancellation = new Cancellation();
            var finished = new CountDownLatch(1);
            var shutdown = new Thread(
                    () -> {
                        cancellation.cancel();
                        try {
                            finished.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    },
                    "voxelbrew-slice-cancel");
            Runtime.getRuntime().addShutdownHook(shutdown);
            try (var registry = slicers.open(pluginDirectory);
                    var exportRegistry = exporters.open(pluginDirectory)) {
                var document = session().document();
                if (printerConnection != null) {
                    var connections = dev.voxelbrew.printer.api.PrinterRuntime.load()
                            .devices(dev.voxelbrew.util.config.ApplicationPaths.configuration()
                                    .resolveSibling("devices"));
                    var connection = connections.stream()
                            .filter(p -> p.id().equals(printerConnection))
                            .findFirst()
                            .orElseThrow(() ->
                                    new IllegalArgumentException("Unknown printer connection: " + printerConnection));
                    if (!connection.profileId().equals(document.printSetup().printerId()))
                        throw new IllegalArgumentException("Printer connection references a different profile");
                }
                var model = dev.voxelbrew.engine.printing.ManufacturingModels.evaluate(
                        document, MeshOptions.DEFAULT, pluginDirectory);
                registry.diagnostics().forEach(spec.commandLine().getErr()::println);
                var result = slicing.slice(
                        registry.require(model.setup().backendId()).configure(slicerOptions),
                        document,
                        model,
                        exportRegistry,
                        executable,
                        output,
                        overwrite,
                        cancellation,
                        spec.commandLine().getErr()::println,
                        Slicing.DIRECT);
                describe(result.job(), spec.commandLine().getOut());
                var jobs = new java.util.HashMap<>(document.gcode());
                jobs.put(document.printSetup().activePlateId(), result.job().lines());
                var artifacts = new java.util.HashMap<>(document.printArtifacts());
                artifacts.remove(document.printSetup().activePlateId());
                var targetSettings =
                        new java.util.HashMap<>(document.printSetup().workspaceSettings());
                if (printerConnection == null) targetSettings.remove(PrintTarget.PRINTER_ID);
                else targetSettings.put(PrintTarget.PRINTER_ID, printerConnection);
                var targetSetup = document.printSetup().withWorkspaceSettings(targetSettings);
                artifacts.put(
                        document.printSetup().activePlateId(),
                        PrintTarget.attach(result.artifact(), result.job().lines(), targetSetup));
                documentWriter.save(document.withGcode(jobs).withPrintArtifacts(artifacts), file, true);
                spec.commandLine().getOut().println("Job: " + result.output());
                return 0;
            } finally {
                finished.countDown();
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdown);
                } catch (IllegalStateException shuttingDown) {
                    /* cancellation hook is running */
                }
            }
        }
    }

    @Command(
            name = "inspect-job",
            mixinStandardHelpOptions = true,
            description = "Independently inspect actual G-code deposition layers, roles, estimates and job provenance.")
    @RequiredArgsConstructor(access = AccessLevel.PACKAGE, onConstructor_ = @javax.inject.Inject)
    static final class InspectJob implements Callable<Integer> {
        private final dev.voxelbrew.importer.api.GcodeSource gcode;

        @Parameters(index = "0")
        Path file;

        @Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() throws Exception {
            describe(gcode.read(file), spec.commandLine().getOut());
            return 0;
        }
    }

    private static BodyInstance instance(PrintSetup setup, String id) {
        return setup.instances().stream()
                .filter(i -> i.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown build instance " + id));
    }

    private static <T> T or(T supplied, T existing) {
        return supplied == null ? existing : supplied;
    }

    private static void describe(GcodeJob job, java.io.PrintWriter out) {
        out.println("model_layers="
                + job.modelLayers().size()
                + " deposition_moves="
                + job.segments().stream().filter(GcodeJob.Segment::deposits).count());
        out.println("estimated_time="
                + job.estimatedTime().orElse("unknown")
                + " filament_mm="
                + (job.filamentMm().isPresent() ? job.filamentMm().getAsDouble() : "unknown"));
        for (var role : GcodeJob.Role.values()) {
            long count = job.segments().stream().filter(s -> s.role() == role).count();
            if (count > 0) out.println(role + "=" + count);
        }
        job.report().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> out.println(e.getKey() + "=" + e.getValue()));
    }
}
