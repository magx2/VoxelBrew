package dev.voxelbrew.engine.geometry;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.mesh.*;
import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.engine.performance.ComputeBenchmark;
import dev.voxelbrew.engine.printing.*;
import dev.voxelbrew.util.concurrent.CpuWork;
import java.util.*;
import org.junit.jupiter.api.Test;

class ParallelGeometryTest {
    @Test
    void nativeBatchesPreserveMeshOrderFaceMappingMeasurementsAndOwnership() {
        int before = NativeKernel.liveHandles();
        var document = ComputeBenchmark.document(8, 4);
        ManufacturingModel detached;
        try (var evaluation = Evaluator.evaluate(document)) {
            var bodies = evaluation.requireCompleteDocument();
            var serial =
                    bodies.stream().map(b -> b.solid().tessellate(MeshOptions.DEFAULT)).toList();
            var volumes = bodies.stream().map(b -> b.solid().volume()).toList();
            detached = ManufacturingModel.from(document, evaluation, MeshOptions.DEFAULT);
            assertEquals(
                    bodies.stream().map(Evaluation.Body::id).toList(),
                    new ArrayList<>(detached.meshes().keySet()));
            for (int repeat = 0; repeat < 3; repeat++) {
                var meshes = CpuWork.map(bodies, b -> b.solid().tessellate(MeshOptions.DEFAULT));
                for (int i = 0; i < bodies.size(); i++) {
                    assertArrayEquals(serial.get(i).vertices(), meshes.get(i).vertices());
                    assertArrayEquals(serial.get(i).triangles(), meshes.get(i).triangles());
                    assertArrayEquals(serial.get(i).faceIndices(), meshes.get(i).faceIndices());
                    assertEquals(volumes.get(i), bodies.get(i).solid().volume());
                    assertTrue(MeshValidation.inspect(meshes.get(i)).closed());
                }
            }
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            CpuWork.map(
                                    bodies,
                                    body -> {
                                        var mesh = body.solid().tessellate(MeshOptions.DEFAULT);
                                        if (body.id().equals(bodies.getFirst().id()))
                                            throw new IllegalStateException(
                                                    "injected batch failure");
                                        return mesh;
                                    }));
        }
        assertEquals(before, NativeKernel.liveHandles());
        assertTrue(PrintValidation.validate(detached).valid());
    }
}
