package dev.voxelbrew.printer.api;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.model.CadDocument;
import dev.voxelbrew.engine.printing.PrintArtifact;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrinterApiTest {
    @TempDir
    Path directory;

    @Test
    void genericGcodeCannotMasqueradeAsBambuPackage() {
        var doc = new CadDocument("test", List.of(), 0).withGcode(Map.of("plate", List.of("G1 X1")));
        var device =
                new PrinterDevice("a1", "A1", PrinterDevice.Kind.BAMBU, "localhost", "serial", "Bambu Lab A1", "code");
        assertTrue(PrinterJobs.incompatibility(doc, "plate", device).isPresent());
        var artifact = PrintArtifact.of(
                "gcode.3mf",
                new byte[] {1, 2, 3},
                Map.of("backend", "orca", "model", "Bambu Lab A1", "tool", "0", "nozzle_mm", "0.4"));
        doc = doc.withPrintArtifacts(Map.of("plate", artifact));
        assertTrue(PrinterJobs.incompatibility(doc, "plate", device).isEmpty());
        assertTrue(PrinterJobs.incompatibility(
                        doc,
                        "plate",
                        new PrinterDevice(
                                "other", "Other", PrinterDevice.Kind.BAMBU, "localhost", "serial", "P1S", "code"))
                .isPresent());
        assertTrue(PrinterJobs.incompatibility(doc, "missing", device).isPresent());
    }

    @Test
    void independentJavaConsumerCompilesWithoutApplicationModules() throws Exception {
        Path source = directory.resolve("Consumer.java");
        Files.writeString(
                source,
                "import dev.voxelbrew.printer.api.*; public class Consumer { public PrinterStatus.State state(PrinterSession session) throws java.io.IOException { return session.status().state(); } }");
        assertEquals(
                0,
                javax.tools.ToolProvider.getSystemJavaCompiler()
                        .run(
                                null,
                                null,
                                null,
                                "-source",
                                "21",
                                "-target",
                                "21",
                                "-classpath",
                                System.getProperty("java.class.path"),
                                source.toString()));
    }
}
