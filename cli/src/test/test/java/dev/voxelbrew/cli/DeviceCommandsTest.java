package dev.voxelbrew.cli;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.engine.printing.*;
import dev.voxelbrew.printer.api.*;
import dev.voxelbrew.slicer.api.PrinterProfileCatalog;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.DoubleConsumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** Tests command decisions against ports, without network, kernel, slicer or subprocesses. */
class DeviceCommandsTest {
    @TempDir
    Path directory;

    private final FakeRuntime runtime = new FakeRuntime();
    private final Printer printer = new Printer(
            "device-1",
            "Workshop",
            Printer.Kind.MOONRAKER,
            "profile-one",
            "http://printer.local",
            "",
            "",
            "TOP-SECRET");
    private CadDocument document = CadDocument.empty();
    private int writes;
    private String output;

    private static PrinterProfile profile() {
        var original = PrintProfile.resolve(PrintSetup.empty()).printer();
        return new PrinterProfile(
                new PrintProfile.Printer(
                        "profile-one",
                        "Test machine",
                        original.nozzleMm(),
                        original.bed(),
                        original.excludedZones(),
                        original.usableHeight(),
                        original.firmware(),
                        original.outputFormat()),
                "Test",
                1.75,
                1,
                true,
                false);
    }

    private PrinterProfileCatalog catalog() {
        return new PrinterProfileCatalog() {
            public Path directory() {
                return directory;
            }

            public Result load() {
                return new Result(List.of(new Entry(profile(), "", "test", true)), List.of());
            }

            public int exportDefaults(Set<String> ids) {
                throw new AssertionError("Unexpected export");
            }

            public String fallbackImage() {
                return "";
            }
        };
    }

    private int run(String... args) {
        var root = new CommandLine(CommandLine.Model.CommandSpec.create().name("test"));
        new DeviceCommands(
                        runtime,
                        path -> document,
                        (value, path, overwrite) -> {
                            document = value;
                            writes++;
                        },
                        catalog())
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

    private void storedJob() {
        runtime.devices.add(printer);
        var setup = PrintSetup.empty().withPrinter(profile()).withPrintTarget(printer.id());
        var session = new ModelSession(document);
        session.setPrintSetup(setup);
        var lines = List.of("G1 X1");
        var artifact = PrintTarget.attach(
                PrintArtifact.of("gcode", "G1 X1\n".getBytes(java.nio.charset.StandardCharsets.UTF_8), Map.of()),
                lines,
                setup);
        document =
                session.document().withGcode(Map.of("plate-1", lines)).withPrintArtifacts(Map.of("plate-1", artifact));
    }

    @Test
    void deviceListingAndDetailsNeverExposeSecretOrInternalIdentity() {
        runtime.devices.add(printer);
        assertEquals(0, run("printer", "device", "list"));
        assertTrue(output.contains("Workshop"));
        assertFalse(output.contains(printer.secret()));
        assertFalse(output.contains(printer.id()));
        assertEquals(0, run("printer", "device", "show", "--device", " workshop "));
        assertTrue(output.contains(printer.address()));
        assertFalse(output.contains(printer.secret()));
        assertFalse(output.contains(printer.id()));
        assertTrue(runtime.calls.isEmpty());
    }

    @Test
    void renamingPreservesIdentityProfileAndCredentials() {
        runtime.devices.add(printer);
        assertEquals(0, run("printer", "device", "set", "--device", "WORKSHOP", "--name", "Renamed"));
        var saved = runtime.devices.getFirst();
        assertEquals(printer.id(), saved.id());
        assertEquals("Renamed", saved.name());
        assertEquals(printer.secret(), saved.secret());
        assertEquals(printer.profileId(), saved.profileId());
    }

    @Test
    void secretFileIsReadButInvalidDeviceIsNeverSaved() throws Exception {
        Path secret = directory.resolve("secret.txt");
        Files.writeString(secret, "replacement\n");
        assertEquals(
                0,
                run(
                        "printer",
                        "device",
                        "add",
                        "--device",
                        "New",
                        "--kind",
                        "MOONRAKER",
                        "--profile",
                        "profile-one",
                        "--address",
                        "http://machine.local",
                        "--secret-file",
                        secret.toString()));
        assertEquals("replacement", runtime.devices.getFirst().secret());
        assertFalse(output.contains("replacement"));
        assertEquals(1, run("printer", "device", "set", "--device", "New", "--address", "ftp://invalid"));
        assertEquals("http://machine.local", runtime.devices.getFirst().address());
    }

    @Test
    void uploadDoesNotStartAndAlwaysRemovesItsTemporaryArtifact() {
        storedJob();
        assertEquals(0, run("printer", "upload", "virtual.vxb", "--device", "Workshop"));
        assertEquals(List.of("connect", "upload", "close"), runtime.calls);
        assertArrayEquals(document.printArtifacts().get("plate-1").bytes(), runtime.uploaded);
        assertFalse(Files.exists(runtime.temporary));
        assertEquals(0, writes);
    }

    @Test
    void printChecksReadinessBeforeUploadAndNeverRetriesAnUncertainStart() {
        storedJob();
        runtime.state = PrinterStatus.State.PRINTING;
        assertEquals(1, run("printer", "print", "virtual.vxb", "--device", "Workshop"));
        assertEquals(List.of("connect", "status", "close"), runtime.calls);
        runtime.calls.clear();
        runtime.state = PrinterStatus.State.IDLE;
        runtime.failStart = true;
        assertEquals(1, run("printer", "print", "virtual.vxb", "--device", "Workshop"));
        assertEquals(List.of("connect", "status", "upload", "start", "close"), runtime.calls);
        assertFalse(Files.exists(runtime.temporary));
    }

    @Test
    void incompatibleOrMissingJobIsRejectedBeforeConnecting() {
        storedJob();
        document = document.withGcode(Map.of());
        assertEquals(1, run("printer", "upload", "virtual.vxb", "--device", "Workshop"));
        assertTrue(runtime.calls.isEmpty());
        storedJob();
        var artifact = document.printArtifacts().get("plate-1");
        var metadata = new HashMap<>(artifact.metadata());
        metadata.put("target_id", "other-device");
        document =
                document.withPrintArtifacts(Map.of("plate-1", PrintArtifact.of("gcode", artifact.bytes(), metadata)));
        assertEquals(1, run("printer", "print", "virtual.vxb", "--device", "Workshop"));
        assertTrue(runtime.calls.isEmpty());
    }

    @Test
    void failedUploadClosesConnectionDeletesTemporaryAndDoesNotStart() {
        storedJob();
        runtime.failUpload = true;
        assertEquals(1, run("printer", "print", "virtual.vxb", "--device", "Workshop"));
        assertEquals(List.of("connect", "status", "upload", "close"), runtime.calls);
        assertFalse(Files.exists(runtime.temporary));
    }

    @Test
    void controlsSendExactlyOneCommandAndCloseTheConnection() {
        runtime.devices.add(printer);
        for (String action : List.of("pause", "resume", "cancel")) {
            runtime.calls.clear();
            assertEquals(0, run("printer", action, "--device", "Workshop"));
            assertEquals(List.of("connect", action.toUpperCase(Locale.ROOT), "close"), runtime.calls);
        }
    }

    @Test
    void targetSelectionEmbedsHardwareAndClearRetainsItWithoutDeviceIdentity() {
        runtime.devices.add(printer);
        assertEquals(0, run("slicer", "target", "set", "virtual.vxb", "--device", "Workshop"));
        assertEquals(printer.id(), document.printSetup().workspaceSettings().get(PrintTarget.PRINTER_ID));
        var hardware = document.printSetup().printerSnapshot();
        assertEquals(profile(), hardware);
        assertEquals(0, run("slicer", "target", "clear", "virtual.vxb"));
        assertEquals(hardware, document.printSetup().printerSnapshot());
        assertFalse(document.printSetup().workspaceSettings().containsKey(PrintTarget.PRINTER_ID));
        int before = writes;
        assertEquals(
                1, run("slicer", "target", "set", "virtual.vxb", "--device", "Workshop", "--profile", "profile-one"));
        assertEquals(before, writes);
    }

    private static final class FakeRuntime implements PrinterRuntime {
        final List<Printer> devices = new ArrayList<>();
        final List<String> calls = new ArrayList<>();
        PrinterStatus.State state = PrinterStatus.State.IDLE;
        boolean failUpload, failStart;
        Path temporary;
        byte[] uploaded;

        public List<Printer> devices(Path path) {
            return List.copyOf(devices);
        }

        public void save(Path path, Printer printer) {
            devices.removeIf(p -> p.id().equals(printer.id()));
            devices.add(printer);
        }

        public void remove(Path path, String id) {
            devices.removeIf(p -> p.id().equals(id));
        }

        public PrinterSession connect(Printer printer) {
            calls.add("connect");
            return new PrinterSession() {
                public PrinterStatus status() {
                    calls.add("status");
                    return new PrinterStatus(
                            state,
                            "",
                            OptionalDouble.empty(),
                            OptionalDouble.empty(),
                            OptionalDouble.empty(),
                            OptionalDouble.empty(),
                            "");
                }

                public Upload upload(Path file, DoubleConsumer progress) throws IOException {
                    calls.add("upload");
                    temporary = file;
                    uploaded = Files.readAllBytes(file);
                    if (failUpload) throw new IOException("Upload failed");
                    return new Upload("job.gcode", "checksum");
                }

                public void start(Upload upload) throws IOException {
                    calls.add("start");
                    if (failStart) throw new IOException("Start outcome unknown");
                }

                public void command(Command command) {
                    calls.add(command.name());
                }

                public void close() {
                    calls.add("close");
                }
            };
        }
    }
}
