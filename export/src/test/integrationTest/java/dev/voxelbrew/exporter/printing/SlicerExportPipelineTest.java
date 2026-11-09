package dev.voxelbrew.exporter.printing;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.mesh.MeshOptions;
import dev.voxelbrew.engine.printing.*;
import dev.voxelbrew.exporter.*;
import dev.voxelbrew.exporter.api.*;
import dev.voxelbrew.slicer.api.SlicerBackend;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SlicerExportPipelineTest {
    @TempDir
    Path directory;

    @Test
    void selectedExporterCreatesInputBeforeBackendRuns() throws Exception {
        var document = PrintingTest.reference().document();
        var model = ManufacturingModels.evaluate(document, MeshOptions.DEFAULT);
        Path output = directory.resolve("preserved.gcode");
        Files.writeString(output, "original");
        try (var builtins = new ExporterRegistry(directory)) {
            for (String format : List.of("stl", "3mf", "prusa-project")) {
                var exports = new AtomicInteger();
                var runs = new AtomicInteger();
                var original = builtins.require(format).provider();
                ExporterProvider tracked =
                        new GeometryExporterProvider(original.descriptor(), (worker, plugins) -> (doc, parent) -> {
                            assertSame(document, doc);
                            exports.incrementAndGet();
                            return original.create(worker, plugins)
                                    .export(doc, parent)
                                    .thenApply(value -> (Object) value);
                        });
                try (var registry = new ExporterRegistry(directory, List.of(tracked))) {
                    var backend = backend(format, request -> {
                        assertEquals(1, exports.get());
                        assertTrue(Files.size(request.input()) > 100);
                        assertEquals(request.workingDirectory(), request.input().getParent());
                        assertSame(model, request.model());
                        runs.incrementAndGet();
                        throw new IOException("Backend reached after export");
                    });
                    var failure = assertThrows(
                            IOException.class,
                            () -> SliceService.slice(
                                    backend,
                                    document,
                                    model,
                                    registry,
                                    Path.of("unused"),
                                    output,
                                    true,
                                    new Cancellation(),
                                    line -> {},
                                    dev.voxelbrew.slicer.api.Slicing.DIRECT));
                    assertEquals("Backend reached after export", failure.getMessage());
                    assertEquals(1, runs.get());
                    assertEquals("original", Files.readString(output));
                    try (var files = Files.list(directory)) {
                        assertEquals(List.of(output), files.toList());
                    }
                }
            }
        }
    }

    @Test
    void failedOrCancelledExportNeverStartsBackendOrPublishesOutput() throws Exception {
        var document = PrintingTest.reference().document();
        var model = ManufacturingModels.evaluate(document, MeshOptions.DEFAULT);
        Path output = directory.resolve("preserved.gcode");
        Files.writeString(output, "original");
        for (boolean cancel : List.of(false, true)) {
            ExporterProvider provider = new GeometryExporterProvider(
                    new ExporterDescriptor("input", "Failing input"),
                    (worker, plugins) -> (doc, parent) -> CompletableFuture.failedFuture(
                            cancel ? new CancellationException() : new IOException("Export failed")));
            try (var registry = new ExporterRegistry(directory, List.of(provider))) {
                var backend = backend("input", request -> {
                    fail("Backend started before a successful export");
                    return null;
                });
                var failure = assertThrows(
                        Exception.class,
                        () -> SliceService.slice(
                                backend,
                                document,
                                model,
                                registry,
                                Path.of("unused"),
                                output,
                                true,
                                new Cancellation(),
                                line -> {},
                                dev.voxelbrew.slicer.api.Slicing.DIRECT));
                if (cancel) assertInstanceOf(CancellationException.class, failure);
                else assertInstanceOf(IOException.class, failure);
                assertEquals("original", Files.readString(output));
                try (var files = Files.list(directory)) {
                    assertEquals(List.of(output), files.toList());
                }
            }
        }
    }

    @FunctionalInterface
    interface Run {
        SlicerBackend.RunResult run(SlicerBackend.Request request) throws IOException;
    }

    private static SlicerBackend backend(String format, Run run) {
        return new SlicerBackend() {
            public Descriptor descriptor() {
                return new PrusaSlicerBackend().descriptor();
            }

            public Probe probe(Path executable, Cancellation cancellation) {
                return new Probe("fixture", descriptor().capabilities());
            }

            public InputFormat inputFormat() {
                return new InputFormat(format, format.equals("stl") ? "stl" : "3mf");
            }

            public RunResult slice(Request request) throws IOException {
                return run.run(request);
            }
        };
    }
}
