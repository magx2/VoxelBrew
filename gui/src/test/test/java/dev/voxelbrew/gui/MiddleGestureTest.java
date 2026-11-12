package dev.voxelbrew.gui;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MiddleGestureTest {
    @Test
    void clickWithSmallJitterDoesNotPan() {
        var gesture = new MiddleGesture();
        gesture.press(100, 100);
        assertNull(gesture.drag(103, 104));
        assertTrue(gesture.release(103, 104));
        assertFalse(gesture.release(103, 104));
    }

    @Test
    void crossingThresholdPansFromPressAndCannotBecomeClickAgain() {
        var gesture = new MiddleGesture();
        gesture.press(100, 100);
        assertNull(gesture.drag(102, 102));
        assertArrayEquals(new double[] {6, 0}, gesture.drag(106, 100));
        assertArrayEquals(new double[] {-6, 0}, gesture.drag(100, 100));
        assertFalse(gesture.release(100, 100));
        gesture.press(100, 100);
        assertTrue(gesture.release(100, 100));
    }

    @Test
    void releaseOutsideThresholdAndCancelledGesturesNeverOpen() {
        var gesture = new MiddleGesture();
        gesture.press(100, 100);
        assertFalse(gesture.release(120, 100));
        gesture.press(100, 100);
        gesture.cancel();
        assertNull(gesture.drag(120, 100));
        assertFalse(gesture.release(100, 100));
    }
}
