package dev.voxelbrew.exporter;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.engine.persistence.DocumentCodec;
import dev.voxelbrew.exporter.api.*;
import dev.voxelbrew.importer.*;
import dev.voxelbrew.importer.api.ImportResult;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GcodePersistenceTest {
    @TempDir
    Path directory;

    private static final List<String> LINES = List.of(
            "; UTF-8: żółć",
            "",
            "G21",
            "G90",
            "M82",
            "G1 Z0.2",
            ";TYPE:Perimeter",
            "G1 X10 Y10 E1 F1200",
            "M104 S0",
            "");

    @Test
    void providersRoundTripEveryLineAndExportTheActivePlate() throws Exception {
        var base = CadDocument.empty();
        var document =
                base.withGcode(Map.of(base.printSetup().activePlateId(), LINES, "plate-2", List.of("; other plate")));
        assertEquals(DocumentCodec.fingerprint(base), DocumentCodec.fingerprint(document));
        try (var exports = new ExporterRegistry(directory);
                var imports = new ImporterRegistry(directory)) {
            for (String format : List.of("vxb", "json")) {
                Path file = directory.resolve("part." + format);
                exports.require(format)
                        .provider()
                        .create(Runnable::run)
                        .export(document, FileExportDialog.destination(file, false))
                        .get();
                var loaded = ((ImportResult.Document) ImportService.read(imports, format, file, Map.of())).document();
                assertEquals(document.gcode(), loaded.gcode());
                Path output = directory.resolve(format + ".gcode");
                exports.require("gcode")
                        .provider()
                        .create(Runnable::run)
                        .export(loaded, FileExportDialog.destination(output, false))
                        .get();
                assertEquals(LINES, Files.readAllLines(output));
                var imported = (ImportResult.Gcode) ImportService.read(imports, "gcode", output, Map.of());
                assertEquals(LINES, imported.job().lines());
                assertEquals(List.of(0.2), imported.job().modelLayers());
                assertEquals(imported.job(), new DefaultImportRuntime().gcode().readLines(LINES));
            }
        }
    }

    @Test
    void missingJobAndOverwriteProtectionPreserveDestination() throws Exception {
        var base = CadDocument.empty();
        var document = base.withGcode(Map.of(base.printSetup().activePlateId(), LINES));
        Path output = directory.resolve("existing.gcode");
        Files.writeString(output, "preserved");
        var exporter = new GcodeExporterProvider().create(Runnable::run);
        assertThrows(
                ExecutionException.class,
                () -> exporter.export(base, FileExportDialog.destination(output, true))
                        .get());
        assertThrows(
                ExecutionException.class,
                () -> exporter.export(document, FileExportDialog.destination(output, false))
                        .get());
        assertEquals("preserved", Files.readString(output));
        exporter.export(document, FileExportDialog.destination(output, true)).get();
        assertEquals(LINES, Files.readAllLines(output));
    }
}
