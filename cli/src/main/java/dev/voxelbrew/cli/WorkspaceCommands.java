package dev.voxelbrew.cli;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

/** Workspace composition. Existing handlers remain ordinary injected commands. */
final class WorkspaceCommands {
    private WorkspaceCommands() {}

    enum OutputFormat {
        text,
        json
    }

    static void install(CommandLine root) {
        var original = new LinkedHashMap<>(root.getSubcommands());
        original.keySet().forEach(root.getCommandSpec()::removeSubcommand);
        group(root, "document", "Create, inspect, import and export editable documents");
        group(root, "designer", "Sketches, solids, construction and editable history");
        group(root, "slicer", "Plates, placement, process, supports and slicing jobs");
        group(root, "printer", "Physical printer connections and job dispatch");
        group(root, "settings", "Application preferences and updates");
        Map<String, String> routes = Map.ofEntries(
                Map.entry("new", "document new"), Map.entry("inspect", "designer inspect"),
                Map.entry("import", "document import"), Map.entry("export", "document export"),
                Map.entry("importers", "document importers"), Map.entry("exporters", "document exporters"),
                Map.entry("rectangle", "designer sketch rectangle"), Map.entry("circle", "designer sketch circle"),
                Map.entry("sketch", "designer sketch"), Map.entry("extrude", "designer solid extrude"),
                Map.entry("cut", "designer solid cut"), Map.entry("split", "designer solid split"),
                Map.entry("edit", "designer feature set"), Map.entry("repair", "designer feature repair"),
                Map.entry("custom", "designer feature add"), Map.entry("feature-types", "designer feature types"),
                Map.entry("history", "designer history list"), Map.entry("cursor", "designer history cursor"),
                Map.entry("body-style", "designer body style set"), Map.entry("parameters", "designer parameter"),
                Map.entry("configurations", "designer configuration"), Map.entry("place", "slicer placement add"),
                Map.entry("duplicate", "slicer placement duplicate"),
                        Map.entry("lay-on-face", "slicer placement lay-on-face"),
                Map.entry("remove-instance", "slicer placement remove"),
                        Map.entry("print-settings", "slicer process set"),
                Map.entry("support", "slicer supports set"), Map.entry("remove-support", "slicer supports remove"),
                Map.entry("print-setup", "slicer inspect"), Map.entry("validate", "slicer validate"),
                Map.entry("slicers", "slicer backend list"), Map.entry("printer-profiles", "slicer profile list"),
                Map.entry("slice", "slicer job slice"), Map.entry("inspect-job", "slicer job inspect"),
                Map.entry("update", "settings update"), Map.entry("native-demo", "designer native-demo"));
        // Move containers first so their children can be extended below.
        original.entrySet().stream()
                .filter(e -> !e.getValue().getSubcommands().isEmpty())
                .forEach(e -> mount(root, routes.get(e.getKey()), e.getValue()));
        original.entrySet().stream()
                .filter(e -> e.getValue().getSubcommands().isEmpty())
                .forEach(e -> mount(root, routes.get(e.getKey()), e.getValue()));
        WorkspaceEdits.register(root);
        path(root, "slicer job").addSubcommand("export", VoxelBrew.Export.class);
        try {
            root.getFactory().create(DeviceCommands.class).install(root);
            root.getFactory().create(PreferenceCommands.class).install(root);
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot compose printer commands", failure);
        }
        root.addSubcommand("session", SessionCommand.class);
        root.getCommandSpec()
                .addOption(OptionSpec.builder("--document")
                        .type(Path.class)
                        .scopeType(CommandLine.ScopeType.INHERIT)
                        .description("Editable document for this invocation")
                        .build());
        root.getCommandSpec()
                .addOption(OptionSpec.builder("--output-format")
                        .type(OutputFormat.class)
                        .defaultValue("text")
                        .scopeType(CommandLine.ScopeType.INHERIT)
                        .description("text or json (command result envelope)")
                        .build());
        configure(root, root);
        var execution = root.getExecutionStrategy();
        root.setExecutionStrategy(parsed -> {
            var selected = parsed;
            while (selected.hasSubcommand()) selected = selected.subcommand();
            var document = (Path) root.getCommandSpec().findOption("--document").getValue();
            var command = selected.commandSpec();
            if (document != null
                    && !command.positionalParameters().isEmpty()
                    && command.positionalParameters().getFirst().type() == Path.class
                    && !(command.userObject() instanceof ImportCommands.ImportFile)
                    && !(command.userObject() instanceof PrintingCommands.InspectJob)) {
                var parameter = command.positionalParameters().getFirst();
                if (parameter.getValue() != null && !document.equals(parameter.getValue()))
                    throw new CommandLine.ParameterException(command.commandLine(), "Specify the document once");
                parameter.setValue(document);
            }
            if (!parsed.isUsageHelpRequested() && !selected.isUsageHelpRequested()) {
                for (var parameter : command.positionalParameters()) {
                    if (parameter.type() == Path.class
                            && parameter.getValue() == null
                            && !(document != null && command.userObject() instanceof PrintingCommands.InspectJob))
                        throw new CommandLine.ParameterException(
                                command.commandLine(), "A document/file path is required");
                }
            }
            if (root.getCommandSpec().findOption("--output-format").getValue() != OutputFormat.json
                    || parsed.isUsageHelpRequested()
                    || selected.isUsageHelpRequested()) return execution.execute(parsed);
            var output = root.getOut();
            var buffer = new java.io.StringWriter();
            root.setOut(new java.io.PrintWriter(buffer));
            int result;
            try {
                result = execution.execute(parsed);
            } catch (Exception failure) {
                root.getErr().println("ERROR: " + failure.getMessage());
                result = 1;
            } finally {
                root.setOut(output);
            }
            output.println(new com.google.gson.Gson()
                    .toJson(Map.of(
                            "command",
                            command.qualifiedName(),
                            "exitCode",
                            result,
                            "output",
                            buffer.toString().lines().toList())));
            return result;
        });
    }

    private static void configure(CommandLine node, CommandLine root) {
        node.setCaseInsensitiveEnumValuesAllowed(true);
        for (var option : node.getCommandSpec().options()) {
            Class<?> type = option.type().isArray() ? option.type().getComponentType() : option.type();
            if (type.isEnum()) registerEnum(node, type);
        }
        node.getSubcommands().values().forEach(child -> configure(child, root));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void registerEnum(CommandLine command, Class<?> type) {
        command.registerConverter((Class) type, text -> {
            for (Object constant : type.getEnumConstants())
                if (((Enum<?>) constant).name().equalsIgnoreCase(text.replace('-', '_'))) return constant;
            throw new CommandLine.TypeConversionException("Unknown " + type.getSimpleName() + ": " + text);
        });
    }

    static CommandLine group(CommandLine parent, String name, String description) {
        var existing = parent.getSubcommands().get(name);
        if (existing != null) return existing;
        var spec = CommandSpec.create().name(name).mixinStandardHelpOptions(true);
        spec.usageMessage().description(description);
        var command = new CommandLine(spec, parent.getFactory());
        parent.addSubcommand(name, command);
        return command;
    }

    static CommandLine path(CommandLine root, String path) {
        var parent = root;
        for (var part : path.split(" ")) parent = group(parent, part, part + " operations");
        return parent;
    }

    static void mount(CommandLine root, String route, CommandLine command) {
        if (route == null) throw new IllegalStateException("Unmapped CLI command " + command.getCommandName());
        int split = route.lastIndexOf(' ');
        var name = route.substring(split + 1);
        command.getCommandSpec().name(name);
        path(root, route.substring(0, split)).addSubcommand(name, command);
    }

    static dev.voxelbrew.engine.model.CadDocument selectPlate(
            dev.voxelbrew.engine.model.CadDocument document, String plate) {
        return new dev.voxelbrew.engine.model.CadDocument(
                document.id(),
                document.features(),
                document.cursor(),
                document.printSetup().selectPlate(plate),
                document.parameters(),
                document.bodyStyles(),
                document.bodyNames(),
                document.featureNames(),
                document.gcode(),
                document.printArtifacts());
    }
}
