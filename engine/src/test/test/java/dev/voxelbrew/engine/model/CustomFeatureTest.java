package dev.voxelbrew.engine.model;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.feature.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class CustomFeatureTest {
    @Test
    void opaqueBytesAreImmutableAndHaveValueIdentityInSessionSnapshots() {
        byte[] bytes = {0, 1, -1, 10};
        var custom = new Feature.Custom("custom", "unknown.provider", "operation", 999, bytes);
        var equal = new Feature.Custom("custom", "unknown.provider", "operation", 999, bytes);
        bytes[0] = 42;
        custom.payload()[1] = 100;
        assertArrayEquals(new byte[] {0, 1, -1, 10}, custom.payload());
        assertEquals(equal, custom);
        assertEquals(equal.hashCode(), custom.hashCode());
        var session = new ModelSession(CadDocument.empty());
        session.add(custom);
        var snapshot = session.snapshot();
        session.replace(equal);
        assertEquals(snapshot, session.snapshot(), "Equal opaque payload is not a new edit");
        assertTrue(session.undo());
        assertTrue(session.document().features().isEmpty());
        assertTrue(session.redo());
        assertEquals(custom, session.document().features().getFirst());
    }

    @Test
    void metadataAndParametersRejectInvalidValuesWithoutNativeInitialization() {
        assertThrows(
                IllegalArgumentException.class, () -> new Feature.Custom("id", "provider", "type", 0, new byte[0]));
        assertThrows(
                IllegalArgumentException.class, () -> new Feature.Custom("id", "bad/provider", "type", 1, new byte[0]));
        var p = new FeatureParameter("height", "Height", "mm", 8, 0.001, 1000);
        for (double value : new double[] {Double.NaN, Double.POSITIVE_INFINITY, 0, 1001})
            assertThrows(IllegalArgumentException.class, () -> p.validate(value));
        assertThrows(
                IllegalArgumentException.class,
                () -> new FeatureDescriptor("provider", "type", "Name", 1, 1, List.of(p, p)));
        assertThrows(IllegalArgumentException.class, () -> new FeatureParameter("height", "Height", "mm", 8, 10, 20));
    }
}
