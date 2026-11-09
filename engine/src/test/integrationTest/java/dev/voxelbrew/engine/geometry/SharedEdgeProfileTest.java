package dev.voxelbrew.engine.geometry;

import static dev.voxelbrew.engine.sketch.SketchGeometry.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.sketch.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SharedEdgeProfileTest {
    private static List<Entity> house() {
        return List.of(
                Sketches.entity("bottom", Kind.LINE, false, 0, 0, 20, 0),
                Sketches.entity("right", Kind.LINE, false, 20, 0, 20, 10),
                Sketches.entity("divider", Kind.LINE, false, 20, 10, 0, 10),
                Sketches.entity("left", Kind.LINE, false, 0, 10, 0, 0),
                Sketches.entity("roof-left", Kind.LINE, false, 0, 10, 10, 20),
                Sketches.entity("roof-right", Kind.LINE, false, 10, 20, 20, 10));
    }

    private static SketchGeometry geometry(List<Entity> entities) {
        return new SketchGeometry(
                entities, List.of(), entities.stream().map(Entity::id).toList());
    }

    @Test
    void individualFacesKeepSharedEdgeAndStableReferences() throws Exception {
        var original = SketchProfiles.selectableRegions(geometry(house()));
        assertEquals(2, original.size());
        for (int seed = 0; seed < 12; seed++) {
            var entities = new ArrayList<>(house());
            Collections.shuffle(entities, new Random(seed));
            if (seed % 2 == 0)
                entities.replaceAll(
                        e -> Sketches.entity(e.id(), Kind.LINE, false, e.value(2), e.value(3), e.value(0), e.value(1)));
            var sketch = new dev.voxelbrew.engine.model.Feature.Sketch(
                    "sketch",
                    "profile",
                    new dev.voxelbrew.engine.model.Feature.Plane("origin-xy", 0),
                    new dev.voxelbrew.engine.model.Feature.Constrained(geometry(entities)));
            for (int i = 0; i < original.size(); i++) {
                var reference = new dev.voxelbrew.engine.model.Feature.ProfileRef(
                        "sketch", SketchProfiles.regionProfileId("profile", original.get(i)));
                var session =
                        new dev.voxelbrew.engine.model.ModelSession(dev.voxelbrew.engine.model.CadDocument.empty());
                session.add(sketch);
                session.add(new dev.voxelbrew.engine.model.Feature.Extrude("extrude", reference, 5));
                var reopened = dev.voxelbrew.engine.persistence.DocumentCodec.decode(
                        dev.voxelbrew.engine.persistence.DocumentCodec.encode(session.document()));
                try (var evaluation = new dev.voxelbrew.engine.model.ModelSession(reopened).evaluate()) {
                    evaluation.requireCompleteDocument();
                    assertEquals(1, evaluation.bodies().size());
                    var solid = evaluation.bodies().getFirst().solid();
                    assertEquals(i == 0 ? 1000 : 500, solid.volume(), 1e-6);
                    assertEquals(i == 0, solid.contains(10, 5, 2));
                    assertEquals(i != 0, solid.contains(10, 15, 2));
                }
            }
        }
    }

    @Test
    void selectedFacePreservesHoleAndMissingRegionFails() {
        var entities = new ArrayList<>(house());
        entities.add(Sketches.entity("hole", Kind.CIRCLE, false, 10, 5, 2));
        var g = geometry(entities);
        var regions = SketchProfiles.selectableRegions(g);
        assertEquals(2, regions.size());
        try (var solid = Solid.profilePrism(regions.getFirst().profile(), 0, 5)) {
            assertEquals(1000 - 20 * Math.PI, solid.volume(), 1e-6);
            assertFalse(solid.contains(10, 5, 2));
            assertFalse(solid.contains(10, 15, 2));
        }
        var sketch = new dev.voxelbrew.engine.model.Feature.Sketch(
                "s",
                "p",
                new dev.voxelbrew.engine.model.Feature.Plane("origin-xy", 0),
                new dev.voxelbrew.engine.model.Feature.Constrained(g));
        assertThrows(
                IllegalArgumentException.class, () -> SketchProfiles.referencedRegions(sketch, "p/region/missing"));
    }

    @Test
    void sharedEdgeExtrudesAsOneSolidRegardlessOfOrderAndDirection() {
        for (int seed = 0; seed < 12; seed++) {
            var entities = new ArrayList<>(house());
            Collections.shuffle(entities, new Random(seed));
            if (seed % 2 == 0)
                entities.replaceAll(
                        e -> Sketches.entity(e.id(), Kind.LINE, false, e.value(2), e.value(3), e.value(0), e.value(1)));
            var g = geometry(entities);
            assertTrue(SketchProfiles.hasProfile(g));
            assertEquals(1, SketchProfiles.regions(g).size());
            try (var solid = Solid.profilePrism(SketchProfiles.profile(g), 0, 5)) {
                assertEquals(1500, solid.volume(), 1e-6);
                assertTrue(solid.contains(10, 5, 2));
                assertTrue(solid.contains(10, 15, 2));
                assertTrue(solid.contains(10, 10, 2));
            }
        }
    }

    @Test
    void sharedBoundaryPreservesHolesAndDisconnectedRegions() {
        var entities = new ArrayList<>(house());
        entities.add(Sketches.entity("hole", Kind.CIRCLE, false, 10, 5, 2));
        entities.add(Sketches.entity("separate", Kind.CIRCLE, false, 40, 5, 2));
        var regions = SketchProfiles.regions(geometry(entities));
        assertEquals(2, regions.size());
        assertEquals("bottom", regions.getFirst().id());
        double volume = 0;
        for (var region : regions) {
            try (var solid = Solid.profilePrism(region.profile(), 0, 5)) {
                volume += solid.volume();
                assertFalse(solid.contains(10, 5, 2));
            }
        }
        assertEquals(1500, volume, 1e-6);
    }

    @Test
    void danglingEdgeStillRejectsExtrusion() {
        var entities = new ArrayList<>(house());
        entities.add(Sketches.entity("open", Kind.LINE, false, 10, 20, 10, 25));
        assertFalse(SketchProfiles.hasProfile(geometry(entities)));
    }
}
