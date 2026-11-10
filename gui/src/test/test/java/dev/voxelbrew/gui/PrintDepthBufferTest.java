package dev.voxelbrew.gui;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PrintDepthBufferTest {
    private static PrintDepthBuffer.Point point(double x, double y, double depth) {
        return new PrintDepthBuffer.Point(x, y, depth);
    }

    @Test
    void nearestSurfaceAndPickingDoNotDependOnInsertionOrder() {
        var first = new PrintDepthBuffer(20, 20);
        var second = new PrintDepthBuffer(20, 20);
        first.triangle(point(0, 0, 5), point(20, 0, 5), point(0, 20, 5), 0xff112233, 1);
        first.triangle(point(0, 0, 10), point(20, 0, 10), point(0, 20, 10), 0xff445566, 2);
        second.triangle(point(0, 0, 10), point(20, 0, 10), point(0, 20, 10), 0xff445566, 2);
        second.triangle(point(0, 0, 5), point(20, 0, 5), point(0, 20, 5), 0xff112233, 1);
        assertArrayEquals(first.pixels(), second.pixels());
        assertEquals(1, first.owner(4, 4));
        assertEquals(1, second.owner(4, 4));
        assertEquals(-1, first.owner(19, 19));
        assertEquals(-1, first.owner(-0.1, 4));
    }

    @Test
    void crossingFacesUseDepthAtEachPixelRatherThanTheirCenters() {
        var raster = new PrintDepthBuffer(20, 20);
        raster.triangle(point(0, 0, 0), point(20, 0, 20), point(0, 20, 0), 0xff112233, 1);
        raster.triangle(point(0, 0, 8), point(20, 0, 8), point(0, 20, 8), 0xff445566, 2);
        assertEquals(1, raster.owner(2, 2));
        assertEquals(2, raster.owner(12, 2));
    }

    @Test
    void hiddenEdgesDoNotShowThroughAndVisibleEdgesKeepSurfacePicking() {
        var raster = new PrintDepthBuffer(20, 20);
        raster.triangle(point(0, 0, 5), point(20, 0, 5), point(0, 20, 5), 0xff112233, 1);
        raster.edge(point(2, 4.5, 10), point(12, 4.5, 10), 0xff000000);
        assertEquals(0xff112233, raster.pixels()[4 * 20 + 4]);
        raster.edge(point(2, 4.5, 5), point(12, 4.5, 5), 0xff000000);
        assertEquals(0xff000000, raster.pixels()[4 * 20 + 4]);
        assertEquals(1, raster.owner(4, 4));
    }

    @Test
    void overlappingEdgesUseDepthAndStableTieBreaking() {
        var first = new PrintDepthBuffer(20, 20);
        var second = new PrintDepthBuffer(20, 20);
        first.edge(point(2, 4.5, 5), point(12, 4.5, 5), 0xff000000);
        first.edge(point(2, 4.5, 10), point(12, 4.5, 10), 0xff075b91);
        second.edge(point(2, 4.5, 10), point(12, 4.5, 10), 0xff075b91);
        second.edge(point(2, 4.5, 5), point(12, 4.5, 5), 0xff000000);
        assertArrayEquals(first.pixels(), second.pixels());
        first.edge(point(2, 4.5, 5), point(12, 4.5, 5), 0xff075b91);
        assertArrayEquals(first.pixels(), second.pixels());
    }

    @Test
    void cameraDepthFollowsOrbitAndTopViewLooksDown() {
        var top = new PrintCamera(false);
        assertTrue(top.depth(0, 0, 10) < top.depth(0, 0, 0));
        var camera = new PrintCamera(true);
        double before = camera.depth(100, 0, 0);
        camera.orbit(360, 0);
        assertEquals(-before, camera.depth(100, 0, 0), 1e-9);
    }
}
