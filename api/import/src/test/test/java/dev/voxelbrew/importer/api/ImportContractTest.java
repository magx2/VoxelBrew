package dev.voxelbrew.importer.api;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class ImportContractTest {
    @Test
    void malformedDescriptorsAndOptionsFailBeforeReading() {
        var scale = new ImportOption("scale", "Scale", ImportOption.Type.DECIMAL, "1", List.of());
        assertThrows(IllegalArgumentException.class, () -> scale.validate("NaN"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ImporterDescriptor(
                        "a", "Reader", 1, List.of("txt"), ImportResult.Kind.DOCUMENT, List.of(scale, scale)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ImporterDescriptor(
                        "a", "Reader", 1, List.of("../txt"), ImportResult.Kind.DOCUMENT, List.of()));
        var options = new ArrayList<>(List.of(scale));
        var descriptor = new ImporterDescriptor("a", "Reader", 1, List.of("txt"), ImportResult.Kind.DOCUMENT, options);
        options.clear();
        assertEquals(1, descriptor.options().size());
        assertThrows(NullPointerException.class, () -> new ImportResult.Document(null));
    }
}
