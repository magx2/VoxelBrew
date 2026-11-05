package dev.voxelbrew.exporter;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.model.CadDocument;
import dev.voxelbrew.engine.persistence.DocumentCodec;
import dev.voxelbrew.importer.DocumentReader;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentWriterTest {
    @TempDir Path directory;

    @Test
    void schemaSixBytesAndFingerprintMatchThePreviousInstalledBuild() throws Exception {
        byte[] baseline;
        try (var input = getClass().getResourceAsStream("/fixtures/reference-schema6.jcad")) {
            baseline = Objects.requireNonNull(input).readAllBytes();
        }
        assertEquals(
                "0481466803177edae0ca10582497b7e60b698fe2d15f347f78c0ba679aa80570",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(baseline)));
        var document = DocumentCodec.decode(baseline);
        assertEquals(
                "df3bc6b5f8389380bf29db8af9e3618043d57dda4176b669883c560d643f3476",
                DocumentCodec.fingerprint(document));
        Path file = directory.resolve("saved with spaces.jcad");
        DocumentWriter.save(document, file, false);
        assertArrayEquals(baseline, Files.readAllBytes(file));
        assertEquals(document, DocumentReader.load(file));
        DocumentWriter.save(document, file, true);
        assertArrayEquals(baseline, Files.readAllBytes(file));
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
}
