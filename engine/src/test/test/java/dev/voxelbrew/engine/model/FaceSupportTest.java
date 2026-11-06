package dev.voxelbrew.engine.model;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.geometry.PlanarFace;
import java.util.List;
import org.junit.jupiter.api.Test;

class FaceSupportTest {
    @Test
    void supportUsesOrientedPlaneGroupsInsteadOfPersistingRevisionLocalFaceIds() {
        var output = new Feature.BodyRef("body", "solid");
        var plane =
                FaceSupport.attach(
                        output,
                        List.of(
                                new PlanarFace(7, 0, 0, 1, 5),
                                new PlanarFace(8, 0, 0, 1, 5),
                                new PlanarFace(3, 0, 0, -1, 0)),
                        8);
        assertEquals(1, plane.support().count());
        var moved = plane.support().resolve(List.of(new PlanarFace(33, 0, 0, 1, 8)), plane.frame());
        assertArrayEquals(new double[] {2, 3, 8}, moved.apply(2, 3, 0), 1e-12);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        plane.support()
                                .resolve(
                                        List.of(
                                                new PlanarFace(7, 0, 0, 1, 5),
                                                new PlanarFace(8, 0, 0, 1, 8)),
                                        plane.frame()));
        assertThrows(
                IllegalArgumentException.class,
                () -> plane.support().resolve(List.of(), plane.frame()));
        assertThrows(
                IllegalArgumentException.class, () -> FaceSupport.attach(output, List.of(), 0));
    }
}
