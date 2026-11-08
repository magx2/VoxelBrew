package dev.voxelbrew.engine.construction;

import static dev.voxelbrew.engine.construction.ConstructionGeometry.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.model.ConstructionFeature;
import dev.voxelbrew.engine.model.Feature;
import dev.voxelbrew.engine.model.Transform;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConstructionFrameTest {
    @Test
    void principalPlanesPreserveCoordinateSystemOrientationAndOriginPlaneConventions() {
        var frame = Transform.translation(3, 4, 5).compose(Transform.rotation(17, 23, 37));
        var names = List.of("YZ", "XZ", "XY");
        for (int i = 0; i < names.size(); i++) {
            var input = new ConstructionFeature.Input(
                    ConstructionFeature.Selection.DATUM, "frame", "", 4 + i, new Vector(0, 0, 0));
            var feature =
                    new ConstructionFeature("plane", ConstructionTools.Tool.OFFSET_PLANE, List.of(input), 0, false);
            var plane = (Plane)
                    ConstructionEvaluator.evaluate(feature, Map.of("frame", new CoordinateSystem(frame)), ref -> null);
            assertArrayEquals(
                    frame.compose(Feature.Plane.origin(names.get(i)).frame()).matrix(),
                    plane.frame().matrix(),
                    1e-9);
            var origin = new ConstructionFeature(
                    "origin",
                    ConstructionTools.Tool.OFFSET_PLANE,
                    List.of(ConstructionFeature.Input.originPlane(i)),
                    0,
                    false);
            var originPlane = (Plane) ConstructionEvaluator.evaluate(origin, Map.of(), ref -> null);
            assertArrayEquals(
                    Feature.Plane.origin(names.get(i)).frame().matrix(),
                    originPlane.frame().matrix(),
                    1e-9);
        }
    }
}
