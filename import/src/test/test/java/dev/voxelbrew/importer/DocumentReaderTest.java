package dev.voxelbrew.importer;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.model.Feature;
import dev.voxelbrew.engine.persistence.DocumentCodec;
import dev.voxelbrew.engine.persistence.DocumentFormatException;
import java.io.RandomAccessFile;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentReaderTest {
    @TempDir
    Path directory;

    @Test
    void opensCurrentReferenceThroughTheImportModule() throws Exception {
        Path file = directory.resolve("legacy design with spaces.jcad");
        try (var input = getClass().getResourceAsStream("/fixtures/reference-schema9.jcad")) {
            Files.copy(java.util.Objects.requireNonNull(input), file);
        }
        var document = DocumentReader.load(file);
        assertEquals(4, document.features().size());
        assertEquals(4, document.cursor());
        var extrusion = document.features().stream()
                .filter(Feature.Extrude.class::isInstance)
                .map(Feature.Extrude.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals(8, extrusion.distance());
        assertTrue(document.features().getLast() instanceof Feature.ThroughCut);
    }

    @Test
    void rejectsOversizedTruncatedAndCorruptFilesWithoutChangingThem() throws Exception {
        Path file = directory.resolve("bad.jcad");
        try (var sparse = new RandomAccessFile(file.toFile(), "rw")) {
            sparse.setLength(DocumentCodec.MAX_ENCODED_BYTES + 4096L);
        }
        long size = Files.size(file);
        assertThrows(DocumentFormatException.class, () -> DocumentReader.load(file));
        assertEquals(size, Files.size(file));
        Files.write(file, new byte[] {'J', 'C', 'A', 'D'});
        assertThrows(DocumentFormatException.class, () -> DocumentReader.load(file));
        byte[] data;
        try (var input = getClass().getResourceAsStream("/fixtures/reference-schema9.jcad")) {
            data = java.util.Objects.requireNonNull(input).readAllBytes();
        }
        data[data.length - 1] ^= 1;
        Files.write(file, data);
        assertTrue(assertThrows(DocumentFormatException.class, () -> DocumentReader.load(file))
                .getMessage()
                .contains("checksum"));
        assertArrayEquals(data, Files.readAllBytes(file));
    }
}
