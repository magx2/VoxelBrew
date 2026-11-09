package dev.voxelbrew.exporter.api;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class ExportOptionTest {
    @Test
    void invalidProviderValuesFailBeforeWriting() {
        var count = new ExportOption("count", "Count", ExportOption.Type.INTEGER, "2", List.of());
        assertThrows(IllegalArgumentException.class, () -> count.validate("2.5"));
        var scale = new ExportOption("scale", "Scale", ExportOption.Type.DECIMAL, "1", List.of());
        assertThrows(IllegalArgumentException.class, () -> scale.validate("NaN"));
        assertThrows(IllegalArgumentException.class, () -> scale.validate("Infinity"));
        var choice = new ExportOption("mode", "Mode", ExportOption.Type.CHOICE, "a", List.of("a", "b"));
        assertThrows(IllegalArgumentException.class, () -> choice.validate("c"));
        assertDoesNotThrow(() -> choice.validate("b"));
    }
}
