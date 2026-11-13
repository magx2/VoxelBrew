package dev.voxelbrew.cli;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.model.CadDocument;
import dev.voxelbrew.util.config.UserConfiguration;
import dev.voxelbrew.util.di.ApplicationModule;
import dev.voxelbrew.util.update.UpdateServiceFactory;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class DependencyInjectionTest {
    @TempDir
    Path directory;

    @Test
    void printerCommandsListAndEmbedHardwareWithoutNativeEvaluation() throws Exception {
        var document = new AtomicReference<>(CadDocument.empty());
        var files = new FileIoModule(path -> document.get(), (value, path, overwrite) -> document.set(value), path -> {
            throw new IOException("No G-code needed");
        });
        var graph = graph("en", files, () -> {
            throw new AssertionError("No update lookup");
        });
        var listing = new StringWriter();
        var command = quiet(graph.commandLine()).setOut(new PrintWriter(listing));
        assertEquals(0, command.execute("slicer", "profile", "list"));
        assertTrue(listing.toString().contains("bambu-lab-a1-mini"));
        assertEquals(
                0,
                quiet(graph.commandLine())
                        .execute("slicer", "process", "set", "virtual.vxb", "--printer-profile", "bambu-lab-a1-mini"));
        var saved = document.get().printSetup();
        assertEquals(4, saved.version());
        assertEquals(180, saved.printerSnapshot().printer().usableHeight());
        assertEquals(
                0, quiet(graph.commandLine()).execute("slicer", "process", "set", "virtual.vxb", "--material", "PETG"));
        assertEquals(saved.printerSnapshot(), document.get().printSetup().printerSnapshot());
        assertEquals(
                1,
                quiet(graph.commandLine())
                        .execute("slicer", "process", "set", "virtual.vxb", "--printer-profile", "missing-printer"));
        assertEquals(saved.printerSnapshot(), document.get().printSetup().printerSnapshot());
    }

    @Test
    void injectedPortsReachNestedCommandsAndParsedArgumentsNeverLeakToTheNextParser() throws Exception {
        var document = new AtomicReference<>(CadDocument.empty());
        var overwrites = new ArrayList<Boolean>();
        var reads = new AtomicInteger();
        var files = new FileIoModule(
                path -> {
                    reads.incrementAndGet();
                    return document.get();
                },
                (value, path, overwrite) -> {
                    document.set(value);
                    overwrites.add(overwrite);
                },
                path -> {
                    throw new IOException("Injected G-code reader");
                });
        var graph = graph("en", files, () -> {
            throw new AssertionError("Unexpected update lookup");
        });
        var first = quiet(graph.commandLine());
        Path destination = directory.resolve("virtual design.vxb");
        assertEquals(0, first.execute("document", "new", destination.toString(), "--overwrite"));
        var second = quiet(graph.commandLine());
        assertEquals(0, second.execute("document", "new", destination.toString()));
        assertEquals(List.of(true, false), overwrites);
        assertFalse(Files.exists(destination), "Injected sink must replace filesystem I/O");
        assertNotSame(
                first.getSubcommands()
                        .get("document")
                        .getSubcommands()
                        .get("new")
                        .getCommand(),
                second.getSubcommands()
                        .get("document")
                        .getSubcommands()
                        .get("new")
                        .getCommand());
        assertEquals(0, quiet(graph.commandLine()).execute("designer", "parameter", "list", destination.toString()));
        assertEquals(1, reads.get());
        assertEquals(1, quiet(graph.commandLine()).execute("slicer", "job", "inspect", "injected.gcode"));
        assertSame(graph.commands(), first.getFactory());
        assertSame(
                first.getFactory(),
                first.getSubcommands()
                        .get("designer")
                        .getSubcommands()
                        .get("sketch")
                        .getSubcommands()
                        .get("draw")
                        .getFactory());
    }

    @Test
    void configurationAndUpdateFactoriesAreScopedToIndependentApplicationGraphs() throws Exception {
        var updates = new AtomicInteger();
        var polish = graph("pl", new FileIoModule(), () -> {
            updates.incrementAndGet();
            throw new IOException("Offline test updater");
        });
        var english = graph("en", new FileIoModule(), () -> {
            throw new AssertionError("Other graph's updater was called");
        });
        assertEquals("pl", polish.translations().locale().getLanguage());
        assertEquals("en", english.translations().locale().getLanguage());
        assertSame(polish.translations(), polish.translations());
        assertNotSame(polish.translations(), english.translations());
        assertEquals(0, quiet(polish.commandLine()).execute("settings", "update", "--help"));
        assertEquals(0, updates.get(), "Help and graph construction must not create the updater");
        assertEquals(1, quiet(polish.commandLine()).execute("settings", "update", "check"));
        assertEquals(1, updates.get());
        assertEquals(0, quiet(english.commandLine()).execute("--help"));
    }

    private CliComponent graph(String language, FileIoModule files, UpdateServiceFactory updates) throws IOException {
        var config = new UserConfiguration(directory.resolve(language + ".properties"));
        config.set("language", language);
        return DaggerCliComponent.factory().create(new ApplicationModule(config, updates), files);
    }

    private static CommandLine quiet(CommandLine command) {
        return command.setOut(new PrintWriter(new StringWriter())).setErr(new PrintWriter(new StringWriter()));
    }
}
