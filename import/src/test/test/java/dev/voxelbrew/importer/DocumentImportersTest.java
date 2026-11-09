package dev.voxelbrew.importer;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.model.CadDocument;
import dev.voxelbrew.engine.persistence.DocumentCodec;
import dev.voxelbrew.importer.api.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentImportersTest {
    @TempDir
    Path directory;

    @Test
    void vxbAndJsonPreserveCompleteEditableDefinitionsAndNeverWriteSource() throws Exception {
        CadDocument document;
        try (var input = getClass().getResourceAsStream("/fixtures/reference-schema10.vxb")) {
            document = DocumentCodec.decode(Objects.requireNonNull(input).readAllBytes());
        }
        document.parameters().builtins().fileVersion(17);
        for (var format : List.of("vxb", "json")) {
            Path source = directory.resolve("editable design." + format);
            if (format.equals("vxb")) Files.write(source, DocumentCodec.encode(document));
            else Files.writeString(source, DocumentCodec.encodeJson(document));
            byte[] original = Files.readAllBytes(source);
            try (var registry = new ImporterRegistry(directory)) {
                var actual =
                        ((ImportResult.Document) ImportService.read(registry, format, source, Map.of())).document();
                assertArrayEquals(DocumentCodec.encode(document), DocumentCodec.encode(actual));
                assertEquals(17, actual.parameters().builtins().fileVersion());
                assertEquals(4, actual.features().size());
            }
            assertArrayEquals(original, Files.readAllBytes(source));
        }
    }

    @Test
    void cancellationDoesNotScheduleReadsAndRejectedWorkersCompleteExceptionally() {
        for (var provider :
                List.of(new VoxelBrewImporterProvider(), new JsonImporterProvider(), new GcodeImporterProvider())) {
            var tasks = new AtomicInteger();
            var importer = provider.create(task -> tasks.incrementAndGet());
            var host = new ImportDialogs().register(FileImportDialog.class, request -> Optional.empty());
            assertThrows(
                    CancellationException.class, () -> importer.importFrom(host).join());
            assertEquals(0, tasks.get());
            var failure = new RejectedExecutionException("closed");
            var rejected = provider.create(task -> {
                        throw failure;
                    })
                    .importFrom(FileImportDialog.source(directory.resolve("missing")));
            assertSame(
                    failure,
                    assertThrows(CompletionException.class, rejected::join).getCause());
        }
    }

    @Test
    void jsonRejectsMalformedUnknownAndFutureDocumentsWithoutChangingSource() throws Exception {
        String valid = DocumentCodec.encodeJson(CadDocument.empty());
        for (var invalid : List.of(
                "{",
                "{}",
                valid.replace("\"schemaVersion\": 10", "\"schemaVersion\": 999"),
                valid.replaceFirst("\\{", "{\"unknownField\":true,"),
                valid.replace("MILLIMETERS", "UNRECOGNIZED"))) {
            Path source = directory.resolve("bad.json");
            Files.writeString(source, invalid);
            assertThrows(
                    CompletionException.class,
                    () -> new JsonImporter(Runnable::run)
                            .importFrom(FileImportDialog.source(source))
                            .join());
            assertEquals(invalid, Files.readString(source));
        }
    }

    @Test
    void interactionRunsBeforeWorkerAndMissingFileFailsOnWorker() {
        var queued = new ArrayList<Runnable>();
        var importer = new JsonImporter(queued::add);
        var result = importer.importFrom(FileImportDialog.source(directory.resolve("missing.json")));
        assertFalse(result.isDone());
        assertEquals(1, queued.size());
        queued.removeFirst().run();
        assertInstanceOf(
                java.io.IOException.class,
                assertThrows(CompletionException.class, result::join).getCause());
    }
}
