package dev.voxelbrew.engine.sketch;

import static dev.voxelbrew.engine.sketch.SketchGeometry.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class ParallelSketchTest {
    @Test
    void largeJacobianKeepsSerialGeometryRankAndConflictDiagnostics() {
        var entities = new ArrayList<Entity>();
        for (int i = 0; i < 48; i++)
            entities.add(Sketches.entity("line" + i, Kind.LINE, false, 0, i * 3, 9, i * 3 + 1));
        var geometry = new SketchGeometry(entities, List.of(), List.of());
        for (var entity : entities) {
            geometry =
                    geometry.add(
                            SketchConstraints.create(
                                    geometry,
                                    entity.id() + "fixed",
                                    Type.FIX,
                                    List.of(entity.ref(Part.START)),
                                    0));
            geometry =
                    geometry.add(
                            SketchConstraints.create(
                                    geometry,
                                    entity.id() + "horizontal",
                                    Type.HORIZONTAL,
                                    List.of(entity.ref()),
                                    0));
            geometry =
                    geometry.add(
                            SketchConstraints.create(
                                    geometry,
                                    entity.id() + "length",
                                    Type.LENGTH,
                                    List.of(entity.ref()),
                                    10));
        }
        var serial = SketchSolver.solve(geometry, false);
        var parallel = SketchSolver.solve(geometry);
        assertTrue(serial.solved());
        assertEquals(serial, parallel);
        assertEquals(0, parallel.degreesOfFreedom());
        var conflict =
                geometry.add(
                        SketchConstraints.create(
                                geometry,
                                "conflict",
                                Type.LENGTH,
                                List.of(entities.getFirst().ref()),
                                20));
        assertEquals(SketchSolver.solve(conflict, false), SketchSolver.solve(conflict));
    }
}
