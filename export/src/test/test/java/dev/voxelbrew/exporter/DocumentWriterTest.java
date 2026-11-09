package dev.voxelbrew.exporter;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.model.CadDocument;
import dev.voxelbrew.engine.persistence.DocumentCodec;
import dev.voxelbrew.engine.persistence.DocumentFormatException;
import dev.voxelbrew.engine.persistence.proto.DocumentProto;
import dev.voxelbrew.importer.DocumentReader;
import dev.voxelbrew.util.Version;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentWriterTest {
    @TempDir
    Path directory;

    @Test
    void saveCounterTracksPublicationAndSaveAsWithoutAffectingDesignIdentity() throws Exception {
        var document = CadDocument.empty();
        var fingerprint = DocumentCodec.fingerprint(document);
        Path first = directory.resolve("first.jcad");
        DocumentWriter.save(document, first, false);
        assertEquals("first", document.parameters().text("=$vb.project.name"));
        assertEquals("first.jcad", document.parameters().text("=$vb.project.filename"));
        assertEquals(first.toAbsolutePath().toString(), document.parameters().text("=$vb.project.path"));
        assertEquals(
                directory.toAbsolutePath().toString(), document.parameters().text("=$vb.project.folder"));
        assertEquals(1, document.parameters().builtins().fileVersion());
        assertThrows(IOException.class, () -> DocumentWriter.save(document, first, false));
        assertEquals(1, document.parameters().builtins().fileVersion());
        var reopened = DocumentReader.load(first);
        assertEquals(1, reopened.parameters().builtins().fileVersion());
        assertEquals("first", reopened.parameters().text("=$vb.project.name"));
        Path existing = directory.resolve("existing.jcad");
        DocumentWriter.save(CadDocument.empty(), existing, false);
        assertThrows(IOException.class, () -> DocumentWriter.save(reopened, existing, false));
        assertEquals("first", reopened.parameters().text("=$vb.project.name"));
        DocumentWriter.save(reopened, directory.resolve("copy.jcad"), false);
        assertEquals("copy", reopened.parameters().text("=$vb.project.name"));
        assertEquals(
                "copy",
                DocumentReader.load(directory.resolve("copy.jcad")).parameters().text("=$vb.project.name"));
        assertEquals(2, reopened.parameters().builtins().fileVersion());
        assertEquals(fingerprint, DocumentCodec.fingerprint(reopened));
    }

    @Test
    void currentSchemaUpdatesMetadataWithoutChangingDefinitions() throws Exception {
        byte[] baseline;
        try (var input = getClass().getResourceAsStream("/fixtures/reference-schema10.jcad")) {
            baseline = Objects.requireNonNull(input).readAllBytes();
        }
        assertEquals(
                "68f9575b0539d811619a4197e2235f3f796eea798c3146fa3b2015cb86166939",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(baseline)));
        var document = DocumentCodec.decode(baseline);
        var upgradedDefinitions = message(baseline).toBuilder()
                .setSchemaVersion(DocumentCodec.SCHEMA_VERSION)
                .clearApplicationVersion();
        for (var feature : upgradedDefinitions.getFeaturesBuilderList())
            if (feature.hasSketch())
                feature.getSketchBuilder()
                        .getPlaneBuilder()
                        .addAllFrame(Arrays.stream(dev.voxelbrew.engine.model.Transform.IDENTITY.matrix())
                                .boxed()
                                .toList());
        assertEquals(
                HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256")
                                .digest(upgradedDefinitions.build().toByteArray())),
                DocumentCodec.fingerprint(document));
        Path file = directory.resolve("saved with spaces.jcad");
        DocumentWriter.save(document, file, false);
        byte[] saved = Files.readAllBytes(file);
        assertEquals(
                upgradedDefinitions
                        .setApplicationVersion(Version.version())
                        .setFileVersion(1)
                        .build(),
                message(saved));
        assertEquals(document, DocumentReader.load(file));
        DocumentWriter.save(document, file, true);
        assertEquals(2, DocumentReader.load(file).parameters().builtins().fileVersion());
    }

    @Test
    void olderApplicationVersionUpgradesOnExplicitSaveAndPreservesTheDesign() throws Exception {
        byte[] baseline;
        try (var input = getClass().getResourceAsStream("/fixtures/reference-schema10.jcad")) {
            baseline = Objects.requireNonNull(input).readAllBytes();
        }
        byte[] older = envelope(
                message(baseline).toBuilder().setApplicationVersion("0.0.0").build());
        Path file = directory.resolve("older application.jcad");
        Files.write(file, older);
        var document = DocumentReader.load(file);
        assertArrayEquals(older, Files.readAllBytes(file), "Opening must not rewrite the original");
        assertEquals(DocumentCodec.decode(baseline), document);
        DocumentWriter.save(document, file, true);
        assertEquals(Version.version(), message(Files.readAllBytes(file)).getApplicationVersion());
        assertEquals(document, DocumentReader.load(file));
    }

    @Test
    void newerAndMalformedApplicationVersionsCannotBeOverwritten() throws Exception {
        var document = CadDocument.empty();
        var current = message(DocumentCodec.encode(document));
        for (String version : List.of("65534.65534.65534", "invalid")) {
            byte[] future =
                    envelope(current.toBuilder().setApplicationVersion(version).build());
            Path file = directory.resolve(version + ".jcad");
            Files.write(file, future);
            assertThrows(DocumentFormatException.class, () -> DocumentReader.load(file));
            assertThrows(DocumentFormatException.class, () -> DocumentWriter.save(document, file, true));
            assertArrayEquals(future, Files.readAllBytes(file));
        }
        try (var paths = Files.list(directory)) {
            assertEquals(2, paths.count(), "No temporary files should remain");
        }
    }

    @Test
    void failedOverwritePreservesExistingBytesAndLeavesNoTemporaryFiles() throws Exception {
        Path file = directory.resolve("existing.jcad");
        var document = CadDocument.empty();
        DocumentWriter.save(document, file, false);
        byte[] original = Files.readAllBytes(file);
        assertThrows(IOException.class, () -> DocumentWriter.save(CadDocument.empty(), file, false));
        assertArrayEquals(original, Files.readAllBytes(file));
        Files.writeString(file, "Unsupported or damaged original");
        assertThrows(IOException.class, () -> DocumentWriter.save(document, file, true));
        assertEquals("Unsupported or damaged original", Files.readString(file));
        try (var paths = Files.list(directory)) {
            assertEquals(List.of(file), paths.toList());
        }
    }

    @Test
    void concurrentCreateNeverReplacesTheWinningDocument() throws Exception {
        Path destination = directory.resolve("winner.jcad");
        var documents = List.of(CadDocument.empty(), CadDocument.empty());
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var results = new ArrayList<Future<Boolean>>();
        try (var workers = Executors.newFixedThreadPool(2)) {
            for (var document : documents)
                results.add(workers.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("Writer start timed out");
                    try {
                        DocumentWriter.save(document, destination, false);
                        return true;
                    } catch (IOException expectedRace) {
                        return false;
                    }
                }));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            int winner = -1;
            for (int index = 0; index < results.size(); index++) {
                if (results.get(index).get(10, TimeUnit.SECONDS)) {
                    assertEquals(-1, winner, "Both writers published");
                    winner = index;
                }
            }
            assertTrue(winner >= 0);
            assertEquals(documents.get(winner), DocumentReader.load(destination));
        } finally {
            start.countDown();
        }
    }

    private static DocumentProto.Document message(byte[] bytes) throws Exception {
        return DocumentProto.Document.parseFrom(Arrays.copyOfRange(bytes, 44, bytes.length));
    }

    private static byte[] envelope(DocumentProto.Document message) throws Exception {
        byte[] payload = message.toByteArray();
        return ByteBuffer.allocate(44 + payload.length)
                .put(new byte[] {'J', 'C', 'A', 'D', 13, 10, 26, 10})
                .putInt(payload.length)
                .put(MessageDigest.getInstance("SHA-256").digest(payload))
                .put(payload)
                .array();
    }
}
