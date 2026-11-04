package dev.voxelbrew.gui;

import static org.junit.jupiter.api.Assertions.*;

import javafx.geometry.Point3D;
import javafx.scene.transform.Rotate;
import org.junit.jupiter.api.Test;

class ViewOrientationTest {
    @Test
    void allFacesEdgesAndCornersPointTowardsCameraInTheCadBasis() {
        for (int x = -1; x <= 1; x++)
            for (int y = -1; y <= 1; y++)
                for (int z = -1; z <= 1; z++) {
                    if (x == 0 && y == 0 && z == 0) continue;
                    var view = ViewOrientation.lookingFrom(x, y, z);
                    var yaw = new Rotate(view.orbit(), Rotate.Y_AXIS);
                    var pitch = new Rotate(view.tilt(), Rotate.X_AXIS);
                    var direction = new Point3D(x, -z, y).normalize();
                    assertEquals(
                            0,
                            pitch.transform(yaw.transform(direction))
                                    .distance(new Point3D(0, 0, -1)),
                            1e-12);
                    // Independently compare the cube projection with the main scene's FX
                    // transforms.
                    var point = new Point3D(17, -23, 41);
                    assertEquals(
                            0,
                            view.project(point)
                                    .distance(
                                            pitch.transform(
                                                    yaw.transform(new Point3D(17, -41, -23)))),
                            1e-12);
                }
    }

    @Test
    void standardViewsKeepZUpAndCorrectLeftRightHandedness() {
        assertEquals(new ViewOrientation(90, 0), ViewOrientation.named("Top"));
        assertEquals(new ViewOrientation(-90, 0), ViewOrientation.named("Bottom"));
        assertEquals(new ViewOrientation(0, 0), ViewOrientation.named("Front"));
        assertEquals(new ViewOrientation(0, -180), ViewOrientation.named("Back"));
        assertEquals(new ViewOrientation(0, 90), ViewOrientation.named("Right"));
        assertEquals(new ViewOrientation(0, -90), ViewOrientation.named("Left"));
        assertEquals(
                new Point3D(1, 0, 0), ViewOrientation.named("Front").project(new Point3D(1, 0, 0)));
        for (String name :
                new String[] {"Isometric", "Top", "Bottom", "Front", "Back", "Right", "Left"})
            assertEquals(name, ViewOrientation.named(name).name());
        assertEquals("Custom", new ViewOrientation(12, 34).name());
    }

    @Test
    void orbitWrapsAndRejectsInvalidAngles() {
        assertEquals(new ViewOrientation(20, -35), new ViewOrientation(20, 685));
        assertEquals(new ViewOrientation(89, 10), new ViewOrientation(0, 0).dragged(20, -1000));
        assertThrows(IllegalArgumentException.class, () -> new ViewOrientation(Double.NaN, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ViewOrientation(0, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> new ViewOrientation(91, 0));
        assertThrows(IllegalArgumentException.class, () -> ViewOrientation.lookingFrom(0, 0, 0));
    }
}
