package dev.voxelbrew.engine.geometry;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NativeKernelTest {
    private static final double VOLUME_TOLERANCE_MM3 = 0.01;
    private static final double BOUNDS_TOLERANCE_MM = 0.001;

    @Test
    void edgeFinishesAndShellProduceValidIndependentSolids() {
        int before = NativeKernel.liveHandles();
        try (Solid box = Solid.rectanglePrism(0, 0, 0, 20, 20, 10);
                Solid fillet = box.edgeFinish(true, 1, new int[] {1});
                Solid chamfer = box.edgeFinish(false, 1, new int[] {1});
                Solid shell = box.shell(1, 1)) {
            assertTrue(fillet.volume() < box.volume());
            assertTrue(chamfer.volume() < box.volume());
            assertTrue(shell.volume() < box.volume());
            assertTrue(shell.volume() > 0);
            assertEquals(4000, box.volume(), VOLUME_TOLERANCE_MM3);
            assertTrue(box.faceEdges().length >= 2);
            assertEquals(0, box.faceEdges().length % 2);
            assertEquals(0, box.edgeSamples().length % 4);
        }
        assertEquals(before, NativeKernel.liveHandles());
    }

    @ParameterizedTest
    @ValueSource(doubles = {5, 8})
    void referencePlateUsesRealKernel(double thickness) {
        assertEquals("7.9.0", Solid.kernelVersion());
        int before = NativeKernel.liveHandles();
        try (Solid plate = Solid.rectanglePrism(0, 0, 0, 60, 40, thickness);
                Solid drilled = plate.cutThroughCircle(10, 10, 4)) {
            double expected = (60 * 40 - 4 * Math.PI) * thickness;
            assertEquals(expected, drilled.volume(), VOLUME_TOLERANCE_MM3);
            assertBounds(new Bounds(0, 0, 0, 60, 40, thickness), drilled.bounds());
            for (double fraction : new double[] {0.01, 0.5, 0.99}) {
                assertFalse(drilled.contains(10, 10, thickness * fraction), "hole must be open throughout");
                assertTrue(drilled.contains(13, 10, thickness * fraction), "surrounding material must remain");
            }
            assertEquals(2400 * thickness, plate.volume(), VOLUME_TOLERANCE_MM3);
            System.out.printf(
                    java.util.Locale.ROOT,
                    "OCCT %s thickness=%.1f mm volume=%.12f mm^3 expected=%.12f bounds=%s%n",
                    Solid.kernelVersion(),
                    thickness,
                    drilled.volume(),
                    expected,
                    drilled.bounds());
        }
        assertEquals(before, NativeKernel.liveHandles(), "all native owners released");
    }

    @Test
    void offsetAndSignedExtrusion() {
        try (Solid positive = Solid.rectanglePrism(0, 0, 12, 60, 40, 5);
                Solid negative = Solid.rectanglePrism(0, 0, 12, 60, 40, -3)) {
            assertBounds(new Bounds(0, 0, 12, 60, 40, 17), positive.bounds());
            assertBounds(new Bounds(0, 0, 9, 60, 40, 12), negative.bounds());
            assertEquals(7200, negative.volume(), VOLUME_TOLERANCE_MM3);
        }
    }

    @Test
    void resultSurvivesSourceCloseAndCloseIsIdempotent() {
        int before = NativeKernel.liveHandles();
        Solid result;
        try (Solid source = Solid.rectanglePrism(0, 0, 0, 60, 40, 8)) {
            result = source.cutThroughCircle(10, 10, 4);
        }
        try (result) {
            assertEquals((2400 - 4 * Math.PI) * 8, result.volume(), VOLUME_TOLERANCE_MM3);
        }
        result.close();
        assertThrows(IllegalStateException.class, result::volume);
        assertEquals(before, NativeKernel.liveHandles());
    }

    @Test
    void invalidDimensionsAreRejectedBeforeAllocating() {
        int before = NativeKernel.liveHandles();
        for (double bad : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> Solid.rectanglePrism(0, 0, 0, bad, 40, 5));
            try (Solid plate = Solid.rectanglePrism(0, 0, 0, 60, 40, 5)) {
                assertThrows(IllegalArgumentException.class, () -> plate.cutThroughCircle(10, 10, bad));
            }
        }
        assertEquals(before, NativeKernel.liveHandles());
    }

    @Test
    void nativeFailuresBecomeJavaDiagnostics() {
        GeometryException error = assertThrows(GeometryException.class, () -> NativeKernel.volume(Long.MAX_VALUE));
        assertTrue(error.getMessage().contains("volume: unknown or closed solid handle"));
        error = assertThrows(GeometryException.class, () -> NativeKernel.rectanglePrism(0, 0, 0, -1, 40, 5));
        assertTrue(error.getMessage().contains("rectangle extrusion"));
        int before = NativeKernel.liveHandles();
        try (Solid plate = Solid.rectanglePrism(0, 0, 0, 60, 40, 5)) {
            error = assertThrows(GeometryException.class, () -> plate.cutThroughCircle(30, 20, 1000));
            assertTrue(error.getMessage().contains("exactly one solid"));
            assertEquals(12000, plate.volume(), VOLUME_TOLERANCE_MM3);
        }
        assertEquals(before, NativeKernel.liveHandles());
    }

    @Test
    void concurrentDocumentsReleaseIndependentNativeOwners() throws Exception {
        int before = NativeKernel.liveHandles();
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var jobs = new java.util.ArrayList<java.util.concurrent.Callable<Void>>();
            for (int worker = 0; worker < 4; worker++) {
                jobs.add(() -> {
                    for (int revision = 0; revision < 10; revision++) {
                        double thickness = 5 + revision;
                        try (Solid plate = Solid.rectanglePrism(0, 0, 0, 60, 40, thickness);
                                Solid drilled = plate.cutThroughCircle(10, 10, 4)) {
                            assertEquals((2400 - 4 * Math.PI) * thickness, drilled.volume(), VOLUME_TOLERANCE_MM3);
                        }
                    }
                    return null;
                });
            }
            for (var future : executor.invokeAll(jobs)) {
                future.get();
            }
        }
        assertEquals(before, NativeKernel.liveHandles());
    }

    private static void assertBounds(Bounds expected, Bounds actual) {
        assertArrayEquals(
                new double[] {
                    expected.minX(), expected.minY(), expected.minZ(), expected.maxX(), expected.maxY(), expected.maxZ()
                },
                new double[] {actual.minX(), actual.minY(), actual.minZ(), actual.maxX(), actual.maxY(), actual.maxZ()},
                BOUNDS_TOLERANCE_MM);
    }
}
