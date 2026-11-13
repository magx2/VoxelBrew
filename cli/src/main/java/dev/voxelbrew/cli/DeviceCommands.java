package dev.voxelbrew.cli;

import static dev.voxelbrew.cli.WorkspaceEdits.*;

import dev.voxelbrew.importer.api.DocumentSource;
import dev.voxelbrew.printer.api.*;
import dev.voxelbrew.util.config.ApplicationPaths;
import java.nio.file.*;
import java.util.*;
import javax.inject.Inject;
import lombok.RequiredArgsConstructor;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;

@RequiredArgsConstructor(onConstructor_ = @Inject)
final class DeviceCommands {
    private final PrinterRuntime runtime;
    private final DocumentSource documents;
    private final dev.voxelbrew.exporter.DocumentOutput writer;
    private final dev.voxelbrew.slicer.api.PrinterProfileCatalog printers;

    private Path directory() {
        return ApplicationPaths.configuration().resolveSibling("devices");
    }

    void install(CommandLine root) {
        for (String action : List.of("show", "set", "clear")) {
            var target = edit(root, "slicer target " + action, s -> {
                Path file = s.positionalParameters().getFirst().getValue();
                var document = documents.load(file);
                var setup = document.printSetup();
                if (action.equals("show")) {
                    String id = setup.workspaceSettings()
                            .getOrDefault(dev.voxelbrew.engine.printing.PrintTarget.PRINTER_ID, "");
                    String name = runtime.devices(directory()).stream()
                            .filter(p -> p.id().equals(id))
                            .map(Printer::name)
                            .findFirst()
                            .orElse(id.isBlank() ? "No device" : "Unavailable device");
                    s.commandLine().getOut().println(name + "\tprofile=" + setup.printerId());
                    return 0;
                }
                if (action.equals("clear")) setup = setup.withPrintTarget(null);
                else {
                    String name = value(s, "--device"), profile = value(s, "--profile");
                    if ((name == null) == (profile == null))
                        throw new IllegalArgumentException("Choose --device or --profile");
                    if (name != null) {
                        var device = find(name);
                        setup = setup.withPrinter(printers.load().require(device.profileId()))
                                .withPrintTarget(device.id());
                    } else
                        setup = setup.withPrintTarget(null)
                                .withPrinter(printers.load().require(profile));
                }
                var session = new dev.voxelbrew.engine.model.ModelSession(document);
                session.setPrintSetup(setup);
                writer.save(session.document(), file, true);
                return 0;
            });
            if (action.equals("set")) {
                option(target, "--device", String.class, false, null, "Saved printer name");
                option(target, "--profile", String.class, false, null, "Standalone printer profile ID");
            }
        }
        for (String action : List.of("list", "show", "add", "set", "remove", "test")) {
            var spec = command(root, "printer device " + action, s -> device(s, action), false);
            if (!action.equals("list")) option(spec, "--device", String.class, true, null, "Saved printer name");
            if (action.equals("add") || action.equals("set")) {
                option(spec, "--name", String.class, false, null, "New display name");
                option(spec, "--kind", Printer.Kind.class, action.equals("add"), null, "moonraker or bambu");
                option(spec, "--profile", String.class, action.equals("add"), null, "Printer profile ID");
                option(spec, "--address", String.class, action.equals("add"), null, "Moonraker URL or Bambu host");
                option(spec, "--serial", String.class, false, null, "Bambu serial number");
                option(spec, "--model", String.class, false, null, "Bambu model");
                option(spec, "--secret-file", Path.class, false, null, "UTF-8 API key/access code file");
            }
        }
        for (String action : List.of("status", "pause", "resume", "cancel", "upload", "print")) {
            boolean send = action.equals("upload") || action.equals("print");
            var spec = command(root, "printer " + action, s -> control(s, action), send);
            option(spec, "--device", String.class, true, null, "Saved printer name");
            if (send) option(spec, "--plate", String.class, false, null, "Plate ID; defaults to active plate");
            if (action.equals("status"))
                option(spec, "--watch", boolean.class, false, "false", "Poll status until interrupted");
        }
    }

    private Printer find(String name) throws Exception {
        return runtime.devices(directory()).stream()
                .filter(p -> p.name().equalsIgnoreCase(name.strip()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown printer: " + name));
    }

    private int device(CommandSpec s, String action) throws Exception {
        if (action.equals("list")) {
            runtime.devices(directory())
                    .forEach(p -> s.commandLine().getOut().println(p.name() + "\t" + p.kind() + "\t" + p.profileId()));
            return 0;
        }
        Printer old = action.equals("add") ? null : find(value(s, "--device"));
        switch (action) {
            case "show" ->
                s.commandLine()
                        .getOut()
                        .println(old.name() + "\t" + old.kind() + "\t" + old.profileId() + "\t" + old.address() + "\t"
                                + old.serial() + "\t" + old.model());
            case "remove" -> runtime.remove(directory(), old.id());
            case "test" -> {
                try (var connection = runtime.connect(old)) {
                    s.commandLine().getOut().println(connection.status());
                }
            }
            case "add", "set" -> {
                Path secretFile = value(s, "--secret-file");
                String secret = secretFile == null
                        ? (old == null ? "" : old.secret())
                        : Files.readString(secretFile).stripTrailing();
                var next = new Printer(
                        old == null ? UUID.randomUUID().toString() : old.id(),
                        fallback(s, "--name", old == null ? value(s, "--device") : old.name()),
                        fallback(s, "--kind", old == null ? null : old.kind()),
                        fallback(s, "--profile", old == null ? null : old.profileId()),
                        fallback(s, "--address", old == null ? null : old.address()),
                        fallback(s, "--serial", old == null ? "" : old.serial()),
                        fallback(s, "--model", old == null ? "" : old.model()),
                        secret);
                runtime.save(directory(), next);
            }
            default -> throw new IllegalArgumentException(action);
        }
        return 0;
    }

    private int control(CommandSpec s, String action) throws Exception {
        var device = find(value(s, "--device"));
        if (action.equals("upload") || action.equals("print")) {
            Path file = s.positionalParameters().getFirst().getValue();
            var document = documents.load(file);
            String plate = fallback(s, "--plate", document.printSetup().activePlateId());
            var incompatible = PrinterJobs.incompatibility(document, plate, device);
            if (incompatible.isPresent()) throw new IllegalArgumentException(incompatible.get());
            var artifact = document.printArtifacts().get(plate);
            Path temporary = Files.createTempFile("voxelbrew-job-", "." + artifact.format());
            try {
                Files.write(temporary, artifact.bytes());
                try (var connection = runtime.connect(device)) {
                    if (action.equals("print") && !connection.status().canStart())
                        throw new IllegalStateException("Printer is not ready to start");
                    var receipt = connection.upload(temporary, progress -> {});
                    if (action.equals("print")) connection.start(receipt);
                    s.commandLine().getOut().println(action.equals("print") ? "Print start submitted" : "Uploaded");
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } else
            try (var connection = runtime.connect(device)) {
                if (action.equals("status")) {
                    do {
                        s.commandLine().getOut().println(connection.status());
                        s.commandLine().getOut().flush();
                        if (!(Boolean) value(s, "--watch")) break;
                        try {
                            Thread.sleep(2000);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            return 130;
                        }
                    } while (!Thread.currentThread().isInterrupted());
                } else connection.command(PrinterSession.Command.valueOf(action.toUpperCase(Locale.ROOT)));
            }
        return 0;
    }

    private static <T> T fallback(CommandSpec s, String name, T fallback) {
        T value = value(s, name);
        return value == null ? fallback : value;
    }
}
