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
    @TempDir Path directory;

    @Test
    void legacySchemaSixUpgradesMetadataWithoutChangingDefinitions() throws Exception {
        byte[] baseline;
        try (var input = getClass().getResourceAsStream("/fixtures/reference-schema6.jcad")) {
            baseline = Objects.requireNonNull(input).readAllBytes();
        }
        assertEquals(
                "0481466803177edae0ca10582497b7e60b698fe2d15f347f78c0ba679aa80570",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(baseline)));
        var document = DocumentCodec.decode(baseline);
        var upgradedDefinitions =
                message(baseline).toBuilder()
                        .setSchemaVersion(DocumentCodec.SCHEMA_VERSION)
                        .clearApplicationVersion()
                        .build();
        assertEquals(
                HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(upgradedDefinitions.toByteArray())),
                DocumentCodec.fingerprint(document));
        Path file = directory.resolve("saved with spaces.jcad");
        DocumentWriter.save(document, file, false);
        byte[] saved = Files.readAllBytes(file);
        var legacy = message(baseline);
        assertEquals(
                legacy.toBuilder()
                        .setSchemaVersion(DocumentCodec.SCHEMA_VERSION)
                        .setApplicationVersion(Version.version())
                        .build(),
                message(saved));
        assertEquals(document, DocumentReader.load(file));
        DocumentWriter.save(document, file, true);
        assertArrayEquals(saved, Files.readAllBytes(file));
    }

    @Test
    void olderApplicationVersionUpgradesOnExplicitSaveAndPreservesTheDesign() throws Exception {
        byte[] baseline;
        try (var input = getClass().getResourceAsStream("/fixtures/reference-schema6.jcad")) {
            baseline = Objects.requireNonNull(input).readAllBytes();
        }
        byte[] older =
                envelope(message(baseline).toBuilder().setApplicationVersion("0.0.0").build());
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
            byte[] future = envelope(current.toBuilder().setApplicationVersion(version).build());
            Path file = directory.resolve(version + ".jcad");
            Files.write(file, future);
            assertThrows(DocumentFormatException.class, () -> DocumentReader.load(file));
            assertThrows(
                    DocumentFormatException.class, () -> DocumentWriter.save(document, file, true));
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
        assertThrows(
                IOException.class, () -> DocumentWriter.save(CadDocument.empty(), file, false));
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
                results.add(
                        workers.submit(
                                () -> {
                                    ready.countDown();
                                    if (!start.await(10, TimeUnit.SECONDS))
                                        throw new AssertionError("Writer start timed out");
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
