package dev.voxelbrew.engine.model;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.mesh.MeshOptions;
import dev.voxelbrew.engine.parameters.BuiltinParameters;
import dev.voxelbrew.engine.printing.ManufacturingModel;
import dev.voxelbrew.engine.printing.PrintProfile;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModelApiTest {
    @Test
    void createsDocumentAndDetachedManufacturingValuesWithoutApplicationModules() {
        var document = CadDocument.empty();
        assertEquals(0, document.cursor());
        assertNotEquals(
                document.uuidGenerator().next(), document.uuidGenerator().next());
        var model = new ManufacturingModel(
                document.id(),
                "revision",
                document.printSetup(),
                PrintProfile.resolve(document.printSetup()),
                Map.of());
        assertEquals(document.id(), model.documentId());
        assertTrue(model.instances().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> model.mesh("missing"));
        assertNotNull(MeshOptions.DEFAULT);
        assertFalse(((String) new BuiltinParameters().value("vb.version")).contains("${"));
    }

    @Test
    void documentIdentifiersRemainMonotonicAndRejectOverflow() {
        var ids = new UuidGenerator(7);
        assertEquals(7, ids.next());
        assertEquals(8, ids.nextValue());
        assertThrows(IllegalArgumentException.class, () -> new UuidGenerator(0));
        assertThrows(IllegalStateException.class, () -> new UuidGenerator(Long.MAX_VALUE).next());
    }
}
