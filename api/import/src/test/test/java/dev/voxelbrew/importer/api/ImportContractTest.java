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
                () -> new FileImportDialog("Reader", List.of("txt"), List.of(scale, scale)));
        assertThrows(
                IllegalArgumentException.class, () -> new FileImportDialog("Reader", List.of("../txt"), List.of()));
        var options = new ArrayList<>(List.of(scale));
        var descriptor = new FileImportDialog("Reader", List.of("txt"), options);
        options.clear();
        assertEquals(1, descriptor.options().size());
        assertThrows(NullPointerException.class, () -> new ImportResult.Document(null));
    }
}
