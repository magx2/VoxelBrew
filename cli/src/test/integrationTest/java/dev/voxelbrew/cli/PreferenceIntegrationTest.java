package dev.voxelbrew.cli;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.slicer.api.*;
import dev.voxelbrew.util.config.UserConfiguration;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** Real configuration files and runtime catalogs; no executable invocation or network. */
class PreferenceIntegrationTest {
    @TempDir
    Path directory;

    private String output;

    private int run(String... args) {
        var runtime = SlicerRuntime.load();
        var root = new CommandLine(CommandLine.Model.CommandSpec.create().name("test"));
        new PreferenceCommands(
                        new UserConfiguration(directory.resolve("config.properties")),
                        runtime.printers(directory.resolve("printers")),
                        runtime.slicers())
                .install(root);
        var text = new StringWriter();
        root.setOut(new PrintWriter(text));
        root.setErr(new PrintWriter(text));
        root.setExecutionExceptionHandler((failure, command, parsed) -> {
            command.getErr().println(failure.getMessage());
            return 1;
        });
        int result = root.execute(args);
        output = text.toString();
        return result;
    }

    @Test
    void preferencesSurviveFreshCommandInstancesAndResetPreservesOtherKeys() throws Exception {
        assertEquals(0, run("settings", "set", "--key", "language", "--value", "pl"));
        assertEquals(0, run("settings", "set", "--key", "appearance", "--value", "dark"));
        assertEquals(0, run("settings", "reset", "--key", "language"));
        var saved = new UserConfiguration(directory.resolve("config.properties")).read();
        assertFalse(saved.containsKey("language"));
        assertEquals("dark", saved.get("appearance"));
        assertEquals(0, run("settings", "show"));
        assertTrue(output.contains("appearance=dark"));
    }

    @Test
    void visibilityOperationsApplyToAllDefaultsAndRejectCustomOrUnknownIdsWithoutWriting() throws Exception {
        var config = new UserConfiguration(directory.resolve("config.properties"));
        var catalog =
                SlicerRuntime.load().printers(directory.resolve("printers")).load();
        var ids = catalog.entries().stream()
                .filter(PrinterProfileCatalog.Entry::bundled)
                .map(e -> e.definition().printer().id())
                .collect(java.util.stream.Collectors.toSet());
        assertFalse(ids.isEmpty());
        assertEquals(0, run("slicer", "profile", "visibility", "--operation", "deselect-all"));
        assertEquals(ids, Set.of(config.get("printers.hiddenDefaults", "").split(",")));
        assertEquals(0, run("slicer", "profile", "visibility", "--operation", "invert"));
        assertEquals("", config.get("printers.hiddenDefaults", ""));
        assertEquals(
                0,
                run(
                        "slicer",
                        "profile",
                        "visibility",
                        "--operation",
                        "hide",
                        "--id",
                        ids.iterator().next()));
        byte[] before = Files.readAllBytes(config.path());
        assertEquals(1, run("slicer", "profile", "visibility", "--operation", "hide", "--id", "missing-profile"));
        assertArrayEquals(before, Files.readAllBytes(config.path()));
        assertEquals(0, run("slicer", "profile", "visibility", "--operation", "select-all"));
        assertEquals("", config.get("printers.hiddenDefaults", ""));
    }

    @Test
    void providerOptionsPersistInSharedSectionAndUnknownOptionsCannotOverwriteIt() throws Exception {
        String executable = directory.resolve("slicer executable").toString();
        assertEquals(
                0,
                run("slicer", "backend", "configure", "--id", "prusa-slicer", "--option", "executable=" + executable),
                output);
        var section = new UserConfiguration(directory.resolve("config.properties")).section("slicer", "prusa-slicer");
        assertEquals(executable, section.get("executable", ""));
        byte[] before = Files.readAllBytes(section.path());
        assertEquals(
                1, run("slicer", "backend", "configure", "--id", "prusa-slicer", "--option", "not-an-option=value"));
        assertArrayEquals(before, Files.readAllBytes(section.path()));
        assertEquals(0, run("slicer", "backend", "describe", "--id", "prusa-slicer"));
        assertTrue(output.contains("executable=" + executable));
    }

    @Test
    void exportingDefaultProfilesPublishesConsumerReadableCopiesAndNeverOverwrites() throws Exception {
        var catalog = SlicerRuntime.load().printers(directory.resolve("printers"));
        String id = catalog.load().entries().stream()
                .filter(PrinterProfileCatalog.Entry::bundled)
                .findFirst()
                .orElseThrow()
                .definition()
                .printer()
                .id();
        assertEquals(0, run("slicer", "profile", "export", "--id", id));
        assertTrue(output.contains("Exported 1 profiles"));
        var copy = catalog.load().entries().stream()
                .filter(e -> !e.bundled())
                .findFirst()
                .orElseThrow();
        assertEquals("custom-" + id, copy.definition().printer().id());
        Path json;
        try (var files = Files.list(catalog.directory())) {
            json = files.filter(p -> p.toString().endsWith(".json")).findFirst().orElseThrow();
        }
        byte[] before = Files.readAllBytes(json);
        assertEquals(0, run("slicer", "profile", "export", "--id", id));
        assertTrue(output.contains("Exported 0 profiles"));
        assertArrayEquals(before, Files.readAllBytes(json));
    }
}
