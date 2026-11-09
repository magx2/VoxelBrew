package dev.voxelbrew.exporter;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.util.JsonFormat;
import dev.voxelbrew.engine.model.CadDocument;
import dev.voxelbrew.engine.persistence.DocumentCodec;
import dev.voxelbrew.engine.persistence.proto.DocumentProto;
import dev.voxelbrew.exporter.api.FileExportDialog;
import dev.voxelbrew.importer.DocumentReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentExportersTest {
    @TempDir
    Path directory;

    @Test
    void cancellationDoesNotScheduleWritesForFileFormats() {
        for (var provider : java.util.List.of(
                new VoxelBrewExporterProvider(), new JsonExporterProvider(), new StepExporterProvider())) {
            var tasks = new AtomicInteger();
            var format = provider.descriptor().id();
            var exporter = provider.create(task -> tasks.incrementAndGet());
            var window = new dev.voxelbrew.exporter.api.ExportDialogs().register(FileExportDialog.class, request -> {
                assertEquals(format, request.extension());
                return Optional.empty();
            });
            var result = exporter.export(CadDocument.empty(), window);
            assertThrows(CancellationException.class, result::join);
            assertEquals(0, tasks.get());
        }
    }

    @Test
    void nativeLoadingFailuresCompleteTheExportFuture() {
        var failure = new UnsatisfiedLinkError("unavailable native dependency");
        var exporter = new FileExporter("step", "STEP", Runnable::run, (document, target, overwrite) -> {
            throw failure;
        }) {};
        var result = exporter.export(
                CadDocument.empty(), FileExportDialog.destination(directory.resolve("part.step"), false));
        assertTrue(result.isCompletedExceptionally());
        assertSame(failure, assertThrows(ExecutionException.class, result::get).getCause());
    }

    @Test
    void vxbUsesTheSameHeadlessParentAndPreservesExistingFiles() throws Exception {
        var document = CadDocument.empty();
        Path target = directory.resolve("editable.vxb");
        var exporter = new VoxelBrewExporter(Runnable::run);
        assertEquals(
                target,
                exporter.export(document, FileExportDialog.destination(target, false))
                        .get());
        assertEquals(document.id(), DocumentReader.load(target).id());
        byte[] original = Files.readAllBytes(target);
        assertThrows(
                ExecutionException.class,
                () -> exporter.export(document, FileExportDialog.destination(target, false))
                        .get());
        assertArrayEquals(original, Files.readAllBytes(target));
        assertEquals(1, document.parameters().builtins().fileVersion());
    }

    @Test
    void jsonPreservesCompleteSchemaWithoutChangingSaveMetadata() throws Exception {
        CadDocument document;
        try (var input = getClass().getResourceAsStream("/fixtures/reference-schema10.jcad")) {
            document =
                    DocumentCodec.decode(java.util.Objects.requireNonNull(input).readAllBytes());
        }
        long previousVersion = document.parameters().builtins().fileVersion();
        Path project = directory.resolve("original.vxb");
        DocumentWriter.save(document, project, false);
        byte[] encoded = DocumentCodec.encode(document);
        var expected = DocumentProto.Document.parseFrom(Arrays.copyOfRange(encoded, 44, encoded.length));
        Path target = directory.resolve("document.json");
        var exporter = new JsonExporter(Runnable::run);
        exporter.export(document, FileExportDialog.destination(target, false)).get();
        var actual = DocumentProto.Document.newBuilder();
        JsonFormat.parser().merge(Files.readString(target), actual);
        assertEquals(expected, actual.build());
        assertEquals(previousVersion + 1, document.parameters().builtins().fileVersion());
        assertEquals(project.toString(), document.parameters().text("=$vb.project.path"));
        byte[] original = Files.readAllBytes(target);
        assertThrows(
                ExecutionException.class,
                () -> exporter.export(document, FileExportDialog.destination(target, false))
                        .get());
        assertArrayEquals(original, Files.readAllBytes(target));
        exporter.export(document, FileExportDialog.destination(target, true)).get();
        assertArrayEquals(original, Files.readAllBytes(target));
        try (var files = Files.list(directory)) {
            assertEquals(2, files.count());
        }
    }
}
