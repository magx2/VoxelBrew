package dev.voxelbrew.cli;

import static dev.voxelbrew.cli.WorkspaceEdits.*;

import dev.voxelbrew.slicer.api.*;
import dev.voxelbrew.util.config.*;
import java.nio.file.Path;
import java.util.*;
import javax.inject.Inject;
import lombok.RequiredArgsConstructor;
import picocli.CommandLine;

@RequiredArgsConstructor(onConstructor_ = @Inject)
final class PreferenceCommands {
    private final UserConfiguration configuration;
    private final PrinterProfileCatalog printers;
    private final SlicerCatalog.Factory slicers;

    void install(CommandLine root) {
        for (String action : List.of("show", "set", "reset")) {
            var spec = command(
                    root,
                    "settings " + action,
                    s -> {
                        if (action.equals("show"))
                            s.commandLine().getOut().println(new TreeMap<>(configuration.read()));
                        else if (action.equals("set")) configuration.set(value(s, "--key"), value(s, "--value"));
                        else configuration.remove(value(s, "--key"));
                        return 0;
                    },
                    false);
            if (!action.equals("show"))
                option(
                        spec,
                        "--key",
                        String.class,
                        true,
                        null,
                        "Preference key (language, save.folder, appearance, rendering.style)");
            if (action.equals("set")) option(spec, "--value", String.class, true, null, "Preference value");
        }
        command(
                root,
                "slicer profile path",
                s -> {
                    s.commandLine().getOut().println(printers.directory());
                    return 0;
                },
                false);
        var export = command(
                root,
                "slicer profile export",
                s -> {
                    s.commandLine()
                            .getOut()
                            .println("Exported " + printers.exportDefaults(Set.of((String[]) value(s, "--id")))
                                    + " profiles");
                    return 0;
                },
                false);
        option(export, "--id", String[].class, true, null, "Repeat for selected bundled profile IDs");
        for (String action : List.of("show", "reload")) {
            var spec = command(
                    root,
                    "slicer profile " + action,
                    s -> {
                        var catalog = printers.load();
                        catalog.diagnostics().forEach(s.commandLine().getErr()::println);
                        if (action.equals("show")) s.commandLine().getOut().println(catalog.require(value(s, "--id")));
                        else catalog.entries().forEach(s.commandLine().getOut()::println);
                        return 0;
                    },
                    false);
            if (action.equals("show")) option(spec, "--id", String.class, true, null, "Profile ID");
        }
        var visibility = command(
                root,
                "slicer profile visibility",
                s -> {
                    String operation = value(s, "--operation");
                    var hidden = new TreeSet<>(Arrays.asList(
                            configuration.get("printers.hiddenDefaults", "").split(",")));
                    hidden.remove("");
                    var defaults = printers.load().entries().stream()
                            .filter(PrinterProfileCatalog.Entry::bundled)
                            .map(e -> e.definition().printer().id())
                            .collect(java.util.stream.Collectors.toSet());
                    switch (operation) {
                        case "show" -> {
                            s.commandLine().getOut().println("hidden=" + hidden);
                            return 0;
                        }
                        case "select-all" -> hidden.removeAll(defaults);
                        case "deselect-all" -> hidden.addAll(defaults);
                        case "invert" ->
                            defaults.forEach(id -> {
                                if (!hidden.remove(id)) hidden.add(id);
                            });
                        case "hide", "unhide" -> {
                            String id = value(s, "--id");
                            if (!defaults.contains(id))
                                throw new IllegalArgumentException("Expected a bundled profile ID");
                            if (operation.equals("hide")) hidden.add(id);
                            else hidden.remove(id);
                        }
                        default -> throw new IllegalArgumentException("Unknown visibility operation");
                    }
                    configuration.set("printers.hiddenDefaults", String.join(",", hidden));
                    return 0;
                },
                false);
        option(
                visibility,
                "--operation",
                String.class,
                false,
                "show",
                "show, hide, unhide, select-all, deselect-all, invert");
        option(visibility, "--id", String.class, false, null, "Bundled profile ID for hide/unhide");
        for (String action : List.of("describe", "configure")) {
            var spec = command(
                    root,
                    "slicer backend " + action,
                    s -> {
                        try (var catalog = slicers.open(value(s, "--plugin-dir"))) {
                            String id = value(s, "--id");
                            var backend = catalog.require(id);
                            var options = new TreeMap<>(backend.configurationOptions());
                            var saved = configuration.section("slicer", id);
                            var stored = saved.read();
                            options.replaceAll((key, fallback) -> stored.getOrDefault(key, fallback));
                            if (action.equals("configure")) {
                                for (String setting : (String[]) value(s, "--option")) {
                                    int equals = setting.indexOf('=');
                                    if (equals < 1 || !options.containsKey(setting.substring(0, equals)))
                                        throw new IllegalArgumentException("Unknown provider option: " + setting);
                                    options.put(setting.substring(0, equals), setting.substring(equals + 1));
                                }
                                backend.configure(options);
                                saved.update(options, Set.of());
                            }
                            s.commandLine().getOut().println(backend.descriptor());
                            options.forEach(
                                    (key, val) -> s.commandLine().getOut().println(key + "=" + val));
                        }
                        return 0;
                    },
                    false);
            option(spec, "--id", String.class, true, null, "Backend ID");
            option(
                    spec,
                    "--plugin-dir",
                    Path.class,
                    false,
                    ApplicationPaths.plugins().toString(),
                    "Trusted plugins directory");
            if (action.equals("configure"))
                option(spec, "--option", String[].class, true, null, "Repeat provider key=value");
        }
    }
}
