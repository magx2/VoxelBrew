package dev.voxelbrew.cli;

import dev.voxelbrew.engine.construction.ConstructionGeometry.Vector;
import dev.voxelbrew.engine.construction.ConstructionTools;
import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.exporter.DocumentOutput;
import dev.voxelbrew.importer.api.DocumentSource;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;
import javax.inject.Inject;
import lombok.RequiredArgsConstructor;
import picocli.CommandLine;
import picocli.CommandLine.Model.*;

/** Headless edits missing from the original flat command surface. */
@RequiredArgsConstructor(onConstructor_ = @Inject)
final class WorkspaceEdits {
    private final DocumentSource documents;
    private final DocumentOutput writer;

    static void register(CommandLine root) {
        try {
            root.getFactory().create(WorkspaceEdits.class).install(root);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot compose workspace edits", e);
        }
    }

    private void install(CommandLine root) {
        var rotate = edit(root, "slicer placement rotate", s -> {
            var session = session(s);
            String instanceId = value(s, "--instance"),
                    axis = ((String) value(s, "--axis")).toLowerCase(Locale.ROOT),
                    space = ((String) value(s, "--space")).toLowerCase(Locale.ROOT);
            if (!List.of("x", "y", "z").contains(axis)
                    || !Set.of("world", "local").contains(space))
                throw new IllegalArgumentException("Choose axis x/y/z and space world/local");
            var setup = session.document().printSetup();
            var instance = setup.instances().stream()
                    .filter(i -> i.id().equals(instanceId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown instance " + instanceId));
            var model = dev.voxelbrew.engine.printing.ManufacturingModels.evaluate(
                    session.document(), dev.voxelbrew.engine.mesh.MeshOptions.DEFAULT, value(s, "--plugin-dir"));
            double angle = session.document()
                    .parameters()
                    .value(value(s, "--angle"), dev.voxelbrew.engine.parameters.ParameterUnit.DEG);
            var changed = dev.voxelbrew.engine.printing.PrintOrientation.rotate(
                    instance,
                    model.meshes().get(instance.bodyId()),
                    List.of("x", "y", "z").indexOf(axis),
                    angle,
                    space.equals("local"));
            session.setPrintSetup(setup.withInstances(setup.instances().stream()
                    .map(i -> i.id().equals(instanceId) ? changed : i)
                    .toList()));
            return save(s, session);
        });
        option(rotate, "--instance", String.class, true, null, "Instance ID");
        option(rotate, "--axis", String.class, true, null, "x, y or z");
        option(rotate, "--space", String.class, false, "world", "world or local");
        option(rotate, "--angle", String.class, true, null, "Signed rotation expression in degrees");
        for (String action : List.of("status", "wait", "cancel"))
            command(
                    root,
                    "slicer job " + action,
                    s -> {
                        throw new IllegalArgumentException("Background job controls require an editing session");
                    },
                    false);
        var sketchPlane = edit(root, "designer sketch plane set", s -> {
            var session = session(s);
            String id = value(s, "--sketch");
            var sketch = (Feature.Sketch) session.document().features().stream()
                    .filter(f -> f.id().equals(id))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown sketch " + id));
            String origin = ((String) value(s, "--plane")).toUpperCase(Locale.ROOT);
            double offset = number(s, "--offset");
            var plane = new Feature.Plane(
                    offset == 0 ? "origin-" + origin.toLowerCase(Locale.ROOT) : id + ".plane",
                    offset,
                    Feature.Plane.origin(origin).frame(),
                    null);
            String construction = value(s, "--construction-plane"), bodyId = value(s, "--body");
            Integer face = value(s, "--face");
            if (construction != null && (bodyId != null || face != null))
                throw new IllegalArgumentException("Choose a construction plane or a face");
            if (construction != null)
                plane = new Feature.Plane(id + ".plane", offset, Transform.IDENTITY, null, construction);
            if (bodyId != null || face != null) {
                if (bodyId == null || face == null || value(s, "--revision") == null)
                    throw new IllegalArgumentException("Face repair requires --body, --face and --revision");
                try (var evaluation = session.evaluate(value(s, "--plugin-dir"))) {
                    var body = evaluation.requireCompleteDocument().stream()
                            .filter(b -> b.id().equals(bodyId))
                            .findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("Unknown body " + bodyId));
                    int sketchIndex = session.document().features().indexOf(sketch);
                    int sourceIndex = java.util.stream.IntStream.range(
                                    0, session.document().features().size())
                            .filter(i -> session.document()
                                    .features()
                                    .get(i)
                                    .id()
                                    .equals(body.output().featureId()))
                            .findFirst()
                            .orElseThrow();
                    if (sourceIndex >= sketchIndex) throw new IllegalArgumentException("Choose an earlier body output");
                    plane = FaceSupport.attach(
                                    body.output(),
                                    body.solid().planarFaces(),
                                    face,
                                    session.document().uuidGenerator())
                            .withOffset(offset);
                }
            }
            session.replace(new Feature.Sketch(sketch.id(), sketch.profileId(), plane, sketch.profile()));
            return save(s, session);
        });
        option(sketchPlane, "--sketch", String.class, true, null, "Sketch feature ID");
        option(sketchPlane, "--plane", String.class, false, "xy", "Origin plane xy/xz/yz");
        option(sketchPlane, "--offset", Double.class, false, "0", "Offset along local plane normal in mm");
        option(sketchPlane, "--construction-plane", String.class, false, null, "Earlier construction plane feature");
        option(sketchPlane, "--body", String.class, false, null, "Earlier supporting body ID");
        option(sketchPlane, "--face", Integer.class, false, null, "Zero-based planar face index");
        edit(root, "slicer job list", s -> {
            var document = session(s).document();
            document.printSetup().plates().forEach(plate -> {
                var lines = document.gcode().getOrDefault(plate.id(), List.of());
                var artifact = document.printArtifacts().get(plate.id());
                s.commandLine()
                        .getOut()
                        .println(plate.id() + "\t" + (lines.isEmpty() ? "unsliced" : "stored") + "\tlines="
                                + lines.size() + "\tformat=" + (artifact == null ? "gcode" : artifact.format()));
            });
            return 0;
        });
        var topology = edit(root, "designer body topology", s -> {
            var session = session(s);
            try (var evaluation = session.evaluate(value(s, "--plugin-dir"))) {
                s.commandLine()
                        .getOut()
                        .println("revision="
                                + dev.voxelbrew.engine.persistence.DocumentCodec.fingerprint(session.document()));
                String id = value(s, "--body");
                for (var body : evaluation.requireCompleteDocument()) {
                    if (id != null && !body.id().equals(id)) continue;
                    s.commandLine().getOut().println("body=" + body.id() + " output=" + body.output());
                    body.solid().planarFaces().forEach(s.commandLine().getOut()::println);
                    s.commandLine()
                            .getOut()
                            .println(
                                    "face_edges=" + Arrays.toString(body.solid().faceEdges()));
                    s.commandLine()
                            .getOut()
                            .println("edge_samples="
                                    + Arrays.toString(body.solid().edgeSamples()));
                }
            }
            return 0;
        });
        option(topology, "--body", String.class, false, null, "Body ID (default: all)");
        var regions = edit(root, "designer sketch regions", s -> {
            var doc = session(s).document();
            String id = value(s, "--sketch");
            var sketch = (Feature.Sketch) doc.features().stream()
                    .filter(f -> f.id().equals(id))
                    .findFirst()
                    .orElseThrow();
            s.commandLine().getOut().println(sketch.profileId());
            if (sketch.profile() instanceof Feature.Constrained profile)
                dev.voxelbrew.engine.sketch.SketchProfiles.selectableRegions(profile.geometry())
                        .forEach(region -> s.commandLine()
                                .getOut()
                                .println(dev.voxelbrew.engine.sketch.SketchProfiles.regionProfileId(
                                        sketch.profileId(), region)));
            return 0;
        });
        option(regions, "--sketch", String.class, true, null, "Sketch feature ID");
        for (String operation : List.of("arrange", "orient")) {
            var spec = edit(root, "slicer placement " + operation, s -> {
                var session = session(s);
                var model = dev.voxelbrew.engine.printing.ManufacturingModels.evaluate(
                        session.document(), dev.voxelbrew.engine.mesh.MeshOptions.DEFAULT, value(s, "--plugin-dir"));
                var setup = session.document().printSetup();
                if (operation.equals("arrange"))
                    setup = setup.withInstances(dev.voxelbrew.engine.printing.PrintArrangement.arrange(
                                    model, number(s, "--gap"))
                            .orElseThrow(() -> new IllegalArgumentException("Objects cannot fit on the plate")));
                else {
                    String selected = value(s, "--instance");
                    boolean all = value(s, "--all");
                    if (all == (selected != null)) throw new IllegalArgumentException("Select --instance or --all");
                    if (!all && setup.instances().stream().noneMatch(i -> i.id().equals(selected)))
                        throw new IllegalArgumentException("Unknown instance " + selected);
                    setup = setup.withInstances(setup.instances().stream()
                            .map(instance -> {
                                if (!all && !instance.id().equals(selected)) return instance;
                                return new BodyInstance(
                                        instance.id(),
                                        instance.bodyId(),
                                        dev.voxelbrew.engine.printing.PrintOrientation.autoOrient(
                                                model.meshes().get(instance.bodyId()), instance.transform()));
                            })
                            .toList());
                }
                session.setPrintSetup(setup);
                return save(s, session);
            });
            if (operation.equals("arrange")) option(spec, "--gap", Double.class, false, "5", "Minimum spacing in mm");
            else {
                option(spec, "--instance", String.class, false, null, "Instance ID");
                option(spec, "--all", boolean.class, false, "false", "Orient every instance on the active plate");
            }
        }
        for (String action : List.of("show", "set")) {
            var spec = edit(root, "slicer bed " + action, s -> {
                var session = session(s);
                var setup = session.document().printSetup();
                var printer = setup.printerSnapshot();
                if (printer == null)
                    printer = new dev.voxelbrew.engine.printing.PrinterProfile(
                            dev.voxelbrew.engine.printing.PrintProfile.resolve(setup)
                                    .printer(),
                            "Custom",
                            1.75,
                            1,
                            true,
                            false);
                if (action.equals("show")) {
                    s.commandLine().getOut().println(printer);
                    return 0;
                }
                if (setup.workspaceSettings().containsKey(dev.voxelbrew.engine.printing.PrintTarget.PRINTER_ID))
                    throw new IllegalArgumentException("Clear the physical device target before editing the bed");
                session.setPrintSetup(setup.withPrinter(
                        printer.rectangular(number(s, "--width"), number(s, "--depth"), number(s, "--height"))));
                return save(s, session);
            });
            if (action.equals("set"))
                for (String dimension : List.of("width", "depth", "height"))
                    option(spec, "--" + dimension, Double.class, true, null, "Bed " + dimension + " in mm");
        }
        for (String action : List.of("list", "add", "rename", "select", "remove")) {
            var spec = edit(root, "slicer plate " + action, s -> plate(s, action));
            if (!action.equals("list")) option(spec, "--plate", String.class, true, null, "Plate ID");
            if (action.equals("rename")) option(spec, "--name", String.class, true, null, "Plate name");
            if (action.equals("remove"))
                option(spec, "--force", boolean.class, false, "false", "Remove a populated plate");
        }
        var move = edit(root, "slicer placement move-to-plate", s -> {
            var session = session(s);
            session.setPrintSetup(
                    session.document().printSetup().moveInstance(value(s, "--instance"), value(s, "--plate")));
            return save(s, session);
        });
        option(move, "--instance", String.class, true, null, "Instance ID");
        option(move, "--plate", String.class, true, null, "Destination plate ID");
        for (boolean enabled : List.of(true, false))
            edit(root, "slicer supports " + (enabled ? "enable" : "disable"), s -> {
                var session = session(s);
                session.setPrintSetup(session.document().printSetup().withSupportsDisabled(!enabled));
                return save(s, session);
            });
        var nozzle = edit(root, "slicer process nozzle", s -> {
            var session = session(s);
            session.setPrintSetup(session.document().printSetup().withNozzle(number(s, "--diameter")));
            return save(s, session);
        });
        option(nozzle, "--diameter", Double.class, true, null, "Installed nozzle diameter in mm");
        for (String action : List.of("show", "set", "reset")) {
            var spec = edit(root, "slicer workspace " + action, s -> {
                var session = session(s);
                var setup = session.document().printSetup();
                if (action.equals("show")) s.commandLine().getOut().println(new TreeMap<>(setup.workspaceSettings()));
                else {
                    var settings = new TreeMap<>(setup.workspaceSettings());
                    if (action.equals("reset")) settings.clear();
                    else settings.put(value(s, "--key"), value(s, "--value"));
                    session.setPrintSetup(setup.withWorkspaceSettings(settings));
                    return save(s, session);
                }
                return 0;
            });
            if (action.equals("set")) {
                option(spec, "--key", String.class, true, null, "Saved GUI workspace field");
                option(spec, "--value", String.class, true, null, "Draft value; does not apply manufacturing settings");
            }
        }
        edit(root, "slicer job clear", s -> {
            var session = session(s);
            session.invalidateGcode();
            return save(s, session);
        });
        for (String type : List.of("body", "feature")) {
            var spec = edit(root, "designer " + type + " rename", s -> {
                var session = session(s);
                if (type.equals("body")) session.renameBody(value(s, "--id"), value(s, "--name"));
                else session.renameFeature(value(s, "--id"), value(s, "--name"));
                return save(s, session);
            });
            option(spec, "--id", String.class, true, null, "Stable object ID");
            option(spec, "--name", String.class, true, null, "Display name");
        }
        var remove = edit(root, "designer feature remove", s -> {
            var session = session(s);
            session.deleteFeatures(value(s, "--from"), value(s, "--to"));
            return save(s, session);
        });
        option(remove, "--from", Integer.class, true, null, "First history index (inclusive)");
        option(remove, "--to", Integer.class, true, null, "Last history index (exclusive)");
        for (String kind : List.of("fillet", "chamfer", "shell")) {
            var spec = edit(root, "designer solid " + kind, s -> {
                var session = session(s);
                String id = value(s, "--id");
                String expression = value(s, "--size");
                double amount = session.document()
                        .parameters()
                        .value(expression, dev.voxelbrew.engine.parameters.ParameterUnit.MM);
                var body = new Feature.BodyRef(value(s, "--body-feature"), value(s, "--body-output"));
                var selectedEdges = new TreeSet<Integer>();
                if (!kind.equals("shell")) {
                    String edges = value(s, "--edges"), faces = value(s, "--faces");
                    if (edges != null)
                        Arrays.stream(edges.split(",")).map(Integer::valueOf).forEach(selectedEdges::add);
                    if (faces != null) {
                        var selectedFaces = Arrays.stream(faces.split(","))
                                .map(Integer::valueOf)
                                .collect(java.util.stream.Collectors.toSet());
                        try (var evaluation = session.evaluate(value(s, "--plugin-dir"))) {
                            var solid = evaluation.requireCompleteDocument().stream()
                                    .filter(b -> b.output().equals(body))
                                    .findFirst()
                                    .orElseThrow(() -> new IllegalArgumentException("Unknown current body output"))
                                    .solid();
                            var pairs = solid.faceEdges();
                            for (int i = 0; i < pairs.length; i += 2)
                                if (selectedFaces.contains(pairs[i])) selectedEdges.add(pairs[i + 1]);
                        }
                    }
                    if (selectedEdges.isEmpty()) throw new IllegalArgumentException("Select --edges or --faces");
                }
                Feature feature = kind.equals("shell")
                        ? new Feature.Shell(id, body, amount, (Integer) value(s, "--face"))
                        : new Feature.EdgeFinish(
                                id,
                                body,
                                Feature.EdgeFinish.Kind.valueOf(kind.toUpperCase(Locale.ROOT)),
                                amount,
                                List.copyOf(selectedEdges));
                session.add(feature);
                session.bindExpression(id, kind.equals("shell") ? "thickness" : "size", expression);
                return save(s, session);
            });
            option(spec, "--id", String.class, true, null, "Feature ID");
            option(spec, "--body-feature", String.class, true, null, "Source feature ID");
            option(spec, "--body-output", String.class, false, "body", "Source output");
            option(
                    spec,
                    "--size",
                    String.class,
                    true,
                    null,
                    "Radius, chamfer size or shell thickness expression in mm");
            if (kind.equals("shell")) option(spec, "--face", Integer.class, true, null, "One-based face to remove");
            else {
                option(spec, "--edges", String.class, false, null, "Comma-separated one-based edge indices");
                option(
                        spec,
                        "--faces",
                        String.class,
                        false,
                        null,
                        "Comma-separated zero-based faces; use their boundary edges");
            }
        }
        for (String action : List.of("list", "add", "set", "preview")) {
            var spec = edit(root, "designer construction " + action, s -> construction(s, action));
            if (!action.equals("list")) {
                option(spec, "--id", String.class, true, null, "Feature ID");
                option(spec, "--tool", ConstructionTools.Tool.class, true, null, "Construction method");
                option(
                        spec,
                        "--input",
                        String[].class,
                        true,
                        null,
                        "Repeat in tool input order: selection:source:output:index or position:x:y:z");
                option(spec, "--value", Double.class, false, "0", "Distance mm, angle degrees or path fraction");
                option(spec, "--alternate", boolean.class, false, "false", "Alternate midplane");
            }
        }
    }

    private int construction(CommandSpec s, String action) throws Exception {
        var session = session(s);
        if (action.equals("list")) {
            session.document().features().stream()
                    .filter(ConstructionFeature.class::isInstance)
                    .forEach(s.commandLine().getOut()::println);
            s.commandLine().getOut().println("tools=" + Arrays.toString(ConstructionTools.Tool.values()));
            return 0;
        }
        var inputs = Arrays.stream((String[]) value(s, "--input"))
                .map(text -> {
                    var parts = text.split(":", -1);
                    if (parts.length != 4) throw new IllegalArgumentException("Invalid construction input: " + text);
                    if (parts[0].equalsIgnoreCase("position"))
                        return new ConstructionFeature.Input(
                                ConstructionFeature.Selection.POSITION,
                                "",
                                "",
                                0,
                                new Vector(
                                        Double.parseDouble(parts[1]),
                                        Double.parseDouble(parts[2]),
                                        Double.parseDouble(parts[3])));
                    return new ConstructionFeature.Input(
                            ConstructionFeature.Selection.valueOf(parts[0].toUpperCase(Locale.ROOT)),
                            parts[1],
                            parts[2],
                            Integer.parseInt(parts[3]),
                            new Vector(0, 0, 0));
                })
                .toList();
        var feature = new ConstructionFeature(
                value(s, "--id"), value(s, "--tool"), inputs, number(s, "--value"), value(s, "--alternate"));
        if (action.equals("set")) session.replace(feature);
        else session.add(feature);
        if (action.equals("preview")) {
            try (var evaluation = session.evaluate(value(s, "--plugin-dir"))) {
                evaluation.diagnostics().forEach(s.commandLine().getOut()::println);
                return evaluation.valid() ? 0 : 1;
            }
        }
        return save(s, session);
    }

    private int plate(CommandSpec s, String action) throws Exception {
        var session = session(s);
        var setup = session.document().printSetup();
        String id = action.equals("list") ? null : value(s, "--plate");
        if (action.equals("list")) {
            setup.plates().forEach(s.commandLine().getOut()::println);
            return 0;
        }
        setup = switch (action) {
            case "add" -> setup.addPlate(id);
            case "select" -> setup.selectPlate(id);
            case "rename" -> setup.renamePlate(id, value(s, "--name"));
            case "remove" -> {
                var selected = setup.selectPlate(id);
                if ((!selected.instances().isEmpty() || !selected.supports().isEmpty())
                        && !(Boolean) value(s, "--force"))
                    throw new IllegalArgumentException("Populated plate requires --force");
                yield selected.removeActivePlate();
            }
            default -> throw new IllegalArgumentException(action);
        };
        session.setPrintSetup(setup);
        return save(s, session);
    }

    private ModelSession session(CommandSpec s) throws Exception {
        var document = documents.load(file(s));
        String expected = value(s, "--revision");
        if (expected != null && !expected.equals(dev.voxelbrew.engine.persistence.DocumentCodec.fingerprint(document)))
            throw new IllegalArgumentException("Document changed; inspect and select topology again");
        return new ModelSession(document);
    }

    private Path file(CommandSpec s) {
        return s.positionalParameters().getFirst().getValue();
    }

    private int save(CommandSpec s, ModelSession session) throws Exception {
        writer.save(session.document(), file(s), true);
        try (var evaluation = session.evaluate(value(s, "--plugin-dir"))) {
            if (!evaluation.valid()) {
                evaluation.diagnostics().forEach(s.commandLine().getErr()::println);
                return 1;
            }
        }
        return 0;
    }

    @FunctionalInterface
    interface Action {
        int run(CommandSpec spec) throws Exception;
    }

    static CommandSpec edit(CommandLine root, String route, Action action) {
        return command(root, route, action, true);
    }

    static CommandSpec command(CommandLine root, String route, Action action, boolean document) {
        var holder = new CommandSpec[1];
        var command = new CommandLine(
                CommandSpec.wrapWithoutInspection((Callable<Integer>) () -> action.run(holder[0])), root.getFactory());
        var spec = command.getCommandSpec().mixinStandardHelpOptions(true);
        spec.usageMessage().description(route);
        if (document)
            spec.addPositional(PositionalParamSpec.builder()
                    .index("0")
                    .arity("0..1")
                    .type(Path.class)
                    .paramLabel("FILE")
                    .description("Editable document (or --document)")
                    .build());
        if (document) {
            option(
                    spec,
                    "--plugin-dir",
                    Path.class,
                    false,
                    dev.voxelbrew.util.config.ApplicationPaths.plugins().toString(),
                    "Trusted plugins directory");
            option(
                    spec,
                    "--revision",
                    String.class,
                    false,
                    null,
                    "Expected document fingerprint from topology inspection");
        }
        holder[0] = spec;
        WorkspaceCommands.mount(root, route, command);
        return spec;
    }

    static OptionSpec option(
            CommandSpec spec, String name, Class<?> type, boolean required, String fallback, String help) {
        var option = OptionSpec.builder(name)
                .type(type)
                .required(required)
                .defaultValue(fallback)
                .description(help)
                .build();
        spec.addOption(option);
        return option;
    }

    @SuppressWarnings("unchecked")
    static <T> T value(CommandSpec spec, String name) {
        return (T) spec.findOption(name).getValue();
    }

    private static double number(CommandSpec spec, String name) {
        return ((Number) value(spec, name)).doubleValue();
    }
}
