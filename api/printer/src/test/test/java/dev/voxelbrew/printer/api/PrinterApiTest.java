package dev.voxelbrew.printer.api;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.model.CadDocument;
import dev.voxelbrew.engine.printing.PrintArtifact;
import dev.voxelbrew.engine.printing.PrintTarget;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrinterApiTest {
    @TempDir
    Path directory;

    @Test
    void deviceAddressesAreValidatedBeforeSaving() {
        for (var address : List.of(
                "moonraker.local",
                "ftp://moonraker.local",
                "http://user@moonraker.local",
                "http://moonraker.local:0",
                "http://moonraker.local:65536",
                "http://moonraker.local?key=secret",
                "http://moonraker.local:abc",
                "http://moonraker.local:7125/ bad")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new Printer("device", "Device", Printer.Kind.MOONRAKER, "profile", address, "", "", ""),
                    address);
        }
        assertEquals(
                "https://moonraker.local:7125",
                new Printer(
                                "device",
                                "Device",
                                Printer.Kind.MOONRAKER,
                                "profile",
                                " https://moonraker.local:7125 ",
                                "",
                                "",
                                "")
                        .address());
        for (var address : List.of(
                "256.1.1.1",
                "192.168.1",
                "printer..local",
                "-printer.local",
                "printer.local:8883",
                "http://printer.local",
                "printer local")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new Printer(
                            "device", "Device", Printer.Kind.BAMBU, "profile", address, "serial", "model", "code"),
                    address);
        }
        for (var address : List.of("192.168.1.25", "printer.local", "localhost")) {
            assertEquals(
                    address,
                    new Printer("device", "Device", Printer.Kind.BAMBU, "profile", address, "serial", "model", "code")
                            .address());
        }
    }

    @Test
    void genericGcodeCannotMasqueradeAsBambuPackage() {
        var doc = new CadDocument("test", List.of(), 0).withGcode(Map.of("plate", List.of("G1 X1")));
        var device = new Printer(
                "a1",
                "A1",
                Printer.Kind.BAMBU,
                "generic-marlin-200-0.4",
                "localhost",
                "serial",
                "Bambu Lab A1",
                "code");
        assertTrue(PrinterJobs.incompatibility(doc, "plate", device).isPresent());
        var artifact = PrintArtifact.of(
                "gcode.3mf",
                new byte[] {1, 2, 3},
                Map.of(
                        "backend",
                        "orca",
                        "model",
                        "Bambu Lab A1",
                        "tool",
                        "0",
                        "nozzle_mm",
                        "0.4",
                        "target_kind",
                        "printer",
                        "target_id",
                        "a1",
                        "target_profile",
                        "generic-marlin-200-0.4"));
        doc = doc.withPrintArtifacts(Map.of("plate", artifact));
        assertTrue(PrinterJobs.incompatibility(doc, "plate", device).isEmpty());
        var widerNozzle = new CadDocument("test", List.of(), 0, doc.printSetup().withNozzle(0.6))
                .withGcode(Map.of("plate", List.of("G1 X1")))
                .withPrintArtifacts(Map.of(
                        "plate",
                        PrintArtifact.of(
                                "gcode.3mf",
                                new byte[] {1, 2, 3},
                                Map.of(
                                        "backend",
                                        "orca",
                                        "model",
                                        "Bambu Lab A1",
                                        "tool",
                                        "0",
                                        "nozzle_mm",
                                        "0.6",
                                        "target_kind",
                                        "printer",
                                        "target_id",
                                        "a1",
                                        "target_profile",
                                        "generic-marlin-200-0.4"))));
        assertTrue(PrinterJobs.incompatibility(widerNozzle, "plate", device).isEmpty());
        assertTrue(
                PrinterJobs.incompatibility(widerNozzle.withPrintArtifacts(Map.of("plate", artifact)), "plate", device)
                        .isPresent());
        assertTrue(PrinterJobs.incompatibility(
                        doc,
                        "plate",
                        new Printer(
                                "other",
                                "Other",
                                Printer.Kind.BAMBU,
                                "generic-marlin-200-0.4",
                                "localhost",
                                "serial",
                                "P1S",
                                "code"))
                .isPresent());
        assertTrue(PrinterJobs.incompatibility(doc, "missing", device).isPresent());
    }

    @Test
    void onlyTheExactSlicedPrinterCanReceiveAJob() {
        var document = CadDocument.empty();
        var plate = document.printSetup().activePlateId();
        var lines = List.of("G21", "G1 X1 E1");
        var printer = new Printer(
                "workshop",
                "Workshop",
                Printer.Kind.MOONRAKER,
                document.printSetup().printerId(),
                "http://localhost:7125",
                "",
                "",
                "");
        var profileJob = PrintTarget.attach(null, lines, document.printSetup());
        var saved = document.withGcode(Map.of(plate, lines)).withPrintArtifacts(Map.of(plate, profileJob));
        assertTrue(PrinterJobs.incompatibility(saved, plate, printer).isPresent());

        var connected = document.printSetup().withPrintTarget(printer.id());
        var printerJob = PrintTarget.attach(null, lines, connected);
        saved = saved.withPrintArtifacts(Map.of(plate, printerJob));
        assertTrue(PrinterJobs.incompatibility(saved, plate, printer).isEmpty());
        var other = new Printer(
                "other", "Other", Printer.Kind.MOONRAKER, printer.profileId(), "http://localhost:7126", "", "", "");
        assertTrue(PrinterJobs.incompatibility(saved, plate, other).isPresent());
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
