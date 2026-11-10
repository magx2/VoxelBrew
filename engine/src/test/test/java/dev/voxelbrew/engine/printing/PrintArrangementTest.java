package dev.voxelbrew.engine.printing;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.model.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PrintArrangementTest {
    private ManufacturingModel model(double width, int count) {
        var mesh = MeshGeometry.box(
                new SupportVolume("box", "part", SupportVolume.Role.SOLID_SUPPORT, 0, 0, 0, width, 40, 5));
        var instances = new ArrayList<BodyInstance>();
        for (int i = 0; i < count; i++)
            instances.add(new BodyInstance(
                    "part" + i, "body", Transform.translation(90, 90, 3).compose(Transform.rotation(0, 0, 15))));
        var setup = PrintSetup.empty().withInstances(instances);
        return new ManufacturingModel("doc", "rev", setup, PrintProfile.resolve(setup), Map.of("body", mesh));
    }

    @Test
    void separatesOverlapsAndPreservesRotationHeightAndOrder() {
        var model = model(60, 4);
        var result = PrintArrangement.arrange(model, 5).orElseThrow();
        for (int i = 0; i < result.size(); i++) {
            assertEquals(model.instances().get(i).id(), result.get(i).id());
            var before = model.instances().get(i).transform().matrix();
            var after = result.get(i).transform().matrix();
            for (int j = 0; j < 12; j++) if (j != 3 && j != 7) assertEquals(before[j], after[j]);
            var bounds = MeshGeometry.bounds(model.placedMesh(result.get(i)));
            assertTrue(bounds.minX() >= 5 - 1e-8
                    && bounds.minY() >= 5 - 1e-8
                    && bounds.maxX() <= 195 + 1e-8
                    && bounds.maxY() <= 195 + 1e-8);
            for (int j = 0; j < i; j++) {
                var other = MeshGeometry.bounds(model.placedMesh(result.get(j)));
                assertTrue(bounds.minX() >= other.maxX() + 5 - 1e-8
                        || other.minX() >= bounds.maxX() + 5 - 1e-8
                        || bounds.minY() >= other.maxY() + 5 - 1e-8
                        || other.minY() >= bounds.maxY() + 5 - 1e-8);
            }
        }
    }

    @Test
    void oversizedObjectLeavesOriginalUntouched() {
        var model = model(300, 1);
        var initial = model.instances();
        assertTrue(PrintArrangement.arrange(model, 5).isEmpty());
        assertEquals(initial, model.instances());
    }
}
