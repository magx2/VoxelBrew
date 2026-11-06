package dev.voxelbrew.engine.mesh;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MeshValidationTest {
    @Test
    void invalidMeshesAndTolerancesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new MeshOptions(Double.NaN, 5));
        assertThrows(IllegalArgumentException.class, () -> new MeshOptions(0.02, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> MeshValidation.requireClosed(
                        new MeshData(new double[] {0, 0, 0, 1, 0, 0, 0, 1, 0}, new int[] {0, 1, 2}, new int[] {0})));
    }
}
