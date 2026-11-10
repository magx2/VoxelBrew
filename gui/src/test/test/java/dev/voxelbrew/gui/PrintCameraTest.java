package dev.voxelbrew.gui;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PrintCameraTest {
    @Test
    void threeDimensionalOrbitUsesDesignerOrientationAndBedMovementInvertsProjection() {
        var camera = new PrintCamera(true);
        camera.orbit(34, 18);
        assertEquals(ViewOrientation.HOME.dragged(34, 18), camera.orientation());
        var delta = camera.bedDelta(camera.x(12, -7), camera.y(12, -7, 0));
        assertNotNull(delta);
        assertEquals(12, delta.getX(), 1e-9);
        assertEquals(-7, delta.getY(), 1e-9);
        camera.orbit(0, -camera.orientation().tilt() * 2);
        assertNull(camera.bedDelta(1, 1), "Edge-on movement must not produce infinite placements");
    }

    @Test
    void topViewPansInsteadOfTiltingAndFitRestoresPanAndZoom() {
        var camera = new PrintCamera(false);
        camera.orbit(40, -30);
        camera.pan(10, 20);
        assertEquals(50, camera.panX());
        assertEquals(-10, camera.panY());
        assertEquals(ViewOrientation.named("Top"), camera.orientation());
        var delta = camera.bedDelta(4, 7);
        assertEquals(4, delta.getX(), 1e-9);
        assertEquals(7, delta.getY(), 1e-9);
        camera.scroll(120);
        assertEquals(Math.exp(120 * .0015), camera.zoom(), 1e-9);
        camera.scroll(Double.MAX_VALUE);
        assertEquals(100, camera.zoom());
        camera.scroll(-Double.MAX_VALUE);
        assertEquals(.05, camera.zoom());
        camera.fit();
        assertEquals(0, camera.panX());
        assertEquals(0, camera.panY());
        assertEquals(1, camera.zoom());
    }
}
