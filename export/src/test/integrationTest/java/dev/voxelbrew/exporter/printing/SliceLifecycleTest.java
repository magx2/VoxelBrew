package dev.voxelbrew.exporter.printing;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.mesh.MeshOptions;
import dev.voxelbrew.engine.printing.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SliceLifecycleTest {
    @TempDir Path directory;

    @Test
    void cancellationKillsRealSlicerAndPreservesDestination() throws Exception {
        var model =
                ManufacturingModel.evaluate(
                        PrintingTest.reference().document(), MeshOptions.DEFAULT);
        Path output = directory.resolve("preserved job.gcode");
        Files.writeString(output, "original");
        var cancellation = new Cancellation();
        var log = new ArrayList<String>();
        assertThrows(
                CancellationException.class,
                () ->
                        SliceService.slice(
                                new PrusaSlicerBackend(),
                                model,
                                PrusaSlicerBackend.defaultExecutable(),
                                output,
                                true,
                                cancellation,
                                line -> {
                                    synchronized (log) {
                                        log.add(line);
                                    }
                                    if (line.contains("Processing triangulated mesh"))
                                        cancellation.cancel();
                                },
                                SliceService.DIRECT));
        assertEquals("original", Files.readString(output));
        assertTrue(cancellation.cancelled());
        try (var paths = Files.list(directory)) {
            assertEquals(List.of(output), paths.toList());
        }
    }

    @Test
    void backendFailureWrongFormatAndMissingProfilesNeverPublish() throws Exception {
        var session = PrintingTest.reference();
        var model = ManufacturingModel.evaluate(session.document(), MeshOptions.DEFAULT);
        Path output = directory.resolve("failed.gcode");
        Files.writeString(output, "original");
        var backend =
                new SlicerBackend() {
                    public Descriptor descriptor() {
                        return new PrusaSlicerBackend().descriptor();
                    }

                    public Probe probe(Path exe, Cancellation cancellation) {
                        return new Probe("failure-fixture", Set.of());
                    }

                    public RunResult slice(Request request) throws IOException {
                        Files.writeString(request.output(), "partial");
                        throw new IOException("forced backend failure");
                    }
                };
        var failure =
                assertThrows(
                        IOException.class,
                        () ->
                                SliceService.slice(
                                        backend,
                                        model,
                                        Path.of("unused"),
                                        output,
                                        true,
                                        new Cancellation(),
                                        line -> {},
                                        SliceService.DIRECT));
        assertTrue(failure.getMessage().contains("forced backend failure"));
        assertEquals("original", Files.readString(output));
        var p = model.profile().printer();
        var wrong =
                new PrintProfile(
                        new PrintProfile.Printer(
                                p.id(),
                                p.name(),
                                p.nozzleMm(),
                                p.bed(),
                                p.excludedZones(),
                                p.usableHeight(),
                                p.firmware(),
                                "bgcode"),
                        model.profile().material(),
                        model.profile().process());
        assertThrows(
                IOException.class,
                () ->
                        SliceService.slice(
                                backend,
                                new ManufacturingModel(
                                        model.documentId(),
                                        model.revision(),
                                        model.setup(),
                                        wrong,
                                        model.meshes()),
                                Path.of("unused"),
                                output,
                                true,
                                new Cancellation(),
                                line -> {},
                                SliceService.DIRECT));
        session.setPrintSetup(
                session.document()
                        .printSetup()
                        .withProfile("prusa-slicer", "missing", "PLA", ProcessSettings.DEFAULT));
        assertTrue(
                assertThrows(
                                IllegalArgumentException.class,
                                () ->
                                        ManufacturingModel.evaluate(
                                                session.document(), MeshOptions.DEFAULT))
                        .getMessage()
                        .contains("Missing printer profile"));
        assertEquals("original", Files.readString(output));
    }
}
