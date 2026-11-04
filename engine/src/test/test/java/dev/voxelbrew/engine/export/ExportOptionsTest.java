package dev.voxelbrew.engine.export;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.mesh.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExportOptionsTest {
    @Test
    void invalidMeshesAndProviderOptionsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new MeshOptions(Double.NaN, 5));
        assertThrows(IllegalArgumentException.class, () -> new MeshOptions(0.02, 0));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        MeshValidation.requireClosed(
                                new MeshData(
                                        new double[] {0, 0, 0, 1, 0, 0, 0, 1, 0},
                                        new int[] {0, 1, 2},
                                        new int[] {0})));
        var option = new ExportOption("count", "Count", ExportOption.Type.INTEGER, "2", List.of());
        assertThrows(IllegalArgumentException.class, () -> option.validate("2.5"));
    }
}
