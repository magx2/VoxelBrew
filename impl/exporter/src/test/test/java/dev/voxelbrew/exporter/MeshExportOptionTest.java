package dev.voxelbrew.exporter;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class MeshExportOptionTest {
    @Test
    void invalidProviderValuesFailBeforeWriting() {
        var count = new MeshExportOption("count", "Count", MeshExportOption.Type.INTEGER, "2", List.of());
        assertThrows(IllegalArgumentException.class, () -> count.validate("2.5"));
        var scale = new MeshExportOption("scale", "Scale", MeshExportOption.Type.DECIMAL, "1", List.of());
        assertThrows(IllegalArgumentException.class, () -> scale.validate("NaN"));
        assertThrows(IllegalArgumentException.class, () -> scale.validate("Infinity"));
        var choice = new MeshExportOption("mode", "Mode", MeshExportOption.Type.CHOICE, "a", List.of("a", "b"));
        assertThrows(IllegalArgumentException.class, () -> choice.validate("c"));
        assertDoesNotThrow(() -> choice.validate("b"));
    }
}
