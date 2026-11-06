package dev.voxelbrew.engine.persistence;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.engine.parameters.ParameterUnit;
import org.junit.jupiter.api.Test;

class UuidPersistenceTest {
    @Test
    void reloadResumesSharedDocumentSequence() throws Exception {
        var session = new ModelSession(CadDocument.empty());
        session.document().uuidGenerator().next();
        session.setParameters(session.document().parameters().put("width", ParameterUnit.MM, "10 mm", ""));
        long expected = session.document().uuidGenerator().nextValue();
        var reopened = DocumentCodec.decode(DocumentCodec.encode(session.document()));
        assertEquals(expected, reopened.uuidGenerator().next());
        assertEquals(expected + 1, reopened.parameters().uuidGenerator().next());
    }
}
