package dev.voxelbrew.engine;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.engine.persistence.DocumentCodec;
import dev.voxelbrew.engine.printing.PrintArtifact;
import java.util.*;
import org.junit.jupiter.api.Test;

class PrintArtifactTest {
    @Test
    void artifactIsImmutableAndInvalidatesWithDerivedJobs() throws Exception {
        byte[] bytes = {1, 2, 3};
        var artifact = PrintArtifact.of("gcode.3mf", bytes, Map.of("model", "Bambu Lab A1"));
        bytes[0] = 9;
        assertArrayEquals(new byte[] {1, 2, 3}, artifact.bytes());
        var document = new CadDocument("test", List.of(), 0)
                .withGcode(Map.of("plate", List.of("G1 X1")))
                .withPrintArtifacts(Map.of("plate", artifact));
        var session = new ModelSession(document);
        assertEquals(document, DocumentCodec.decode(DocumentCodec.encode(document)));
        assertEquals(document, DocumentCodec.decodeJson(DocumentCodec.encodeJson(document)));
        assertEquals(document.printArtifacts(), session.document().printArtifacts());
        assertEquals(DocumentCodec.fingerprint(document), DocumentCodec.fingerprint(document.withGcode(Map.of())));
        session.invalidateGcode();
        assertTrue(session.document().printArtifacts().isEmpty());
        assertTrue(session.document().gcode().isEmpty());
    }

    @Test
    void checksumMismatchIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PrintArtifact("gcode.3mf", "AQID", "wrong", Map.of()));
    }
}
