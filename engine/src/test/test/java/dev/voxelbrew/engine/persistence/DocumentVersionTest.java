package dev.voxelbrew.engine.persistence;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.model.CadDocument;
import dev.voxelbrew.engine.persistence.proto.DocumentProto;
import dev.voxelbrew.util.Version;
import dev.voxelbrew.util.update.ReleaseVersion;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class DocumentVersionTest {
    @Test
    void usesTheCurrentMagicAndRejectsThePreviousSignature() throws Exception {
        var document = CadDocument.empty();
        byte[] bytes = DocumentCodec.encode(document);
        assertArrayEquals(new byte[] {'J', 'C', 'A', 'D', 13, 37, 13, 37}, Arrays.copyOf(bytes, 8));
        assertEquals(document, DocumentCodec.decode(bytes));
        byte[] previous = {'J', 'C', 'A', 'D', 13, 10, 26, 10};
        System.arraycopy(previous, 0, bytes, 0, previous.length);
        assertTrue(assertThrows(DocumentFormatException.class, () -> DocumentCodec.decode(bytes))
                .getMessage()
                .contains("magic mismatch"));
    }

    @Test
    void savesTheRuntimeVersionWithoutAddingItToCadIdentity() throws Exception {
        var document = CadDocument.empty();
        byte[] bytes = DocumentCodec.encode(document);
        var message = message(bytes);
        assertEquals(Version.version(), message.getApplicationVersion());
        assertEquals(document, DocumentCodec.decode(bytes));
        assertEquals(
                java.util.HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256")
                                .digest(message.toBuilder()
                                        .clearApplicationVersion()
                                        .build()
                                        .toByteArray())),
                DocumentCodec.fingerprint(document));
        bytes[bytes.length - 1] ^= 1;
        assertTrue(assertThrows(DocumentFormatException.class, () -> DocumentCodec.decode(bytes))
                .getMessage()
                .contains("checksum"));
    }

    @Test
    void olderSchemasAreRejectedAndCurrentSchemaPreservesOlderWriterMetadata() throws Exception {
        var original = CadDocument.empty();
        var current = message(DocumentCodec.encode(original));
        for (int schema = 1; schema <= DocumentCodec.SCHEMA_VERSION; schema++) {
            for (String version : List.of("", "0.0.0", Version.version())) {
                byte[] old = envelope(current.toBuilder()
                        .setSchemaVersion(schema)
                        .setApplicationVersion(version)
                        .build());
                byte[] before = old.clone();
                if (schema != DocumentCodec.SCHEMA_VERSION) {
                    assertThrows(DocumentFormatException.class, () -> DocumentCodec.decode(old));
                    continue;
                }
                var document = DocumentCodec.decode(old);
                assertEquals(original, document);
                assertArrayEquals(before, old);
                var upgraded = message(DocumentCodec.encode(document));
                assertEquals(DocumentCodec.SCHEMA_VERSION, upgraded.getSchemaVersion());
                assertEquals(Version.version(), upgraded.getApplicationVersion());
                assertEquals(DocumentCodec.fingerprint(original), DocumentCodec.fingerprint(document));
            }
        }
    }

    @Test
    void newerMajorMinorPatchAndFinalReleaseAreRejected() throws Exception {
        var current = ReleaseVersion.parse(Version.version());
        var future = new java.util.ArrayList<String>();
        future.add((current.major() + 1) + ".0.0");
        future.add(current.major() + "." + (current.minor() + 1) + ".0");
        future.add(current.major() + "." + current.minor() + "." + (current.patch() + 1));
        if (current.snapshot()) future.add(Version.version().replace("-SNAPSHOT", ""));
        var message = message(DocumentCodec.encode(CadDocument.empty()));
        for (String version : future) {
            byte[] bytes =
                    envelope(message.toBuilder().setApplicationVersion(version).build());
            var error = assertThrows(DocumentFormatException.class, () -> DocumentCodec.decode(bytes));
            assertTrue(error.getMessage().contains("VoxelBrew " + version));
            assertTrue(error.getMessage().contains("supported version " + Version.version()));
            assertTrue(error.getMessage().contains("Update VoxelBrew"));
        }
    }

    @Test
    void malformedWriterVersionsAreNotTreatedAsLegacy() throws Exception {
        var message = message(DocumentCodec.encode(CadDocument.empty()));
        for (String version : List.of(" ", "1.2", "v1.2.3", "01.2.3", "-1.0.0", "1.2.3-rc1", "65535.0.0")) {
            byte[] bytes =
                    envelope(message.toBuilder().setApplicationVersion(version).build());
            var error = assertThrows(DocumentFormatException.class, () -> DocumentCodec.decode(bytes));
            assertTrue(
                    error.getMessage().contains("Invalid .vxb application version"),
                    () -> "Unexpected diagnostic for writer version '" + version + "': " + error.getMessage());
        }
    }

    private static DocumentProto.Document message(byte[] bytes) throws Exception {
        return DocumentProto.Document.parseFrom(Arrays.copyOfRange(bytes, 44, bytes.length));
    }

    private static byte[] envelope(DocumentProto.Document message) throws Exception {
        byte[] payload = message.toByteArray();
        return ByteBuffer.allocate(44 + payload.length)
                .put(new byte[] {'J', 'C', 'A', 'D', 13, 37, 13, 37})
                .putInt(payload.length)
                .put(MessageDigest.getInstance("SHA-256").digest(payload))
                .put(payload)
                .array();
    }
}
