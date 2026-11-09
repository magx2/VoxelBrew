package dev.voxelbrew.importer;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.engine.persistence.DocumentCodec;
import dev.voxelbrew.importer.api.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImportTest {
    @TempDir
    Path directory;

    @Test
    void independentProviderUsesApiOnlyAndReturnsEditableCad() throws Exception {
        Path plugins = Files.createDirectories(directory.resolve("trusted plugins with spaces"));
        Path jar = Files.copy(
                Path.of(System.getProperty("voxelbrew.test.plugin")), plugins.resolve("external importer.jar"));
        try (var archive = new java.util.zip.ZipFile(jar.toFile())) {
            assertTrue(
                    archive.stream().noneMatch(e -> e.getName().startsWith("dev/voxelbrew/")),
                    "Host APIs are not bundled");
        }
        Path source = directory.resolve("source plate.vbplate");
        Files.writeString(source, "60,40,4");
        try (var registry = new ImporterRegistry(plugins)) {
            assertEquals(
                    List.of("failing", "gcode", "json", "plate", "vxb", "wrong-kind"),
                    registry.descriptors().stream().map(ImporterDescriptor::id).toList());
            String diagnostics = String.join("\n", registry.diagnostics());
            assertTrue(diagnostics.contains("Duplicate importer ID: plate"), diagnostics);
            assertTrue(diagnostics.contains("Incompatible importer future"), diagnostics);
            assertTrue(diagnostics.contains("Broken descriptor"), diagnostics);
            var document = ((ImportResult.Document) ImportService.read(registry, "plate", source, Map.of("scale", "2")))
                    .document();
            try (var evaluation = Evaluator.evaluate(document)) {
                assertTrue(evaluation.valid());
                assertEquals(76800, evaluation.bodies().getFirst().solid().volume(), .001);
            }
            assertEquals("imported-plate", document.id());
            assertEquals(2, document.features().size());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ImportService.read(registry, "plate", source, Map.of("scale", "NaN")));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ImportService.read(registry, "plate", source, Map.of("typo", "1")));
            assertTrue(assertThrows(IOException.class, () -> ImportService.read(registry, "failing", source, Map.of()))
                    .getMessage()
                    .contains("Deliberate importer failure"));
            assertTrue(assertThrows(
                            IllegalArgumentException.class,
                            () -> ImportService.read(registry, "wrong-kind", source, Map.of()))
                    .getMessage()
                    .contains("different result kind"));
        }
        assertEquals("60,40,4", Files.readString(source));
        // Windows also verifies the registry released its provider JAR.
        Files.delete(jar);
    }

    @Test
    void builtinsUseTheSameRegistryAndRejectOversizedInputs() throws Exception {
        Path source = directory.resolve("document.vxb");
        var document = CadDocument.empty();
        Files.write(source, DocumentCodec.encode(document));
        try (var registry = new ImporterRegistry(directory.resolve("no plugins"))) {
            assertEquals(
                    document,
                    ((ImportResult.Document) ImportService.read(registry, "vxb", source, Map.of())).document());
            Path gcode = directory.resolve("part.gcode");
            Files.writeString(gcode, "G21\nG90\nM82\nG1 Z0.2\nG1 X10 Y10 E1 F1200\n");
            assertEquals(
                    ImportResult.Kind.GCODE,
                    ImportService.read(registry, "gcode", gcode, Map.of()).kind());
            try (var oversized = new RandomAccessFile(source.toFile(), "rw")) {
                oversized.setLength(ImportService.MAX_INPUT_BYTES + 1);
            }
            assertTrue(assertThrows(IOException.class, () -> ImportService.read(registry, "vxb", source, Map.of()))
                    .getMessage()
                    .contains("256 MiB"));
        }
    }
}
