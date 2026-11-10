package dev.voxelbrew.gui;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class NumericFieldBoundsTest {
    @Test
    void inclusiveAndExclusiveBoundsHandleBothEndpoints() {
        for (boolean minInclusive : new boolean[] {false, true}) {
            for (boolean maxInclusive : new boolean[] {false, true}) {
                var bounds = new NumericFieldBounds(5, minInclusive, 100, maxInclusive);
                assertEquals(minInclusive, bounds.accepts(5));
                assertEquals(maxInclusive, bounds.accepts(100));
                assertTrue(bounds.accepts(10));
                assertFalse(bounds.accepts(4));
                assertFalse(bounds.accepts(101));
                assertFalse(bounds.accepts(Double.NaN));
                assertFalse(bounds.accepts(Double.POSITIVE_INFINITY));
            }
        }
    }

    @Test
    void optionalBoundsShowOnlyTheirFiniteLimits() {
        var minimum = new NumericFieldBounds(0, false, Double.POSITIVE_INFINITY, false);
        assertEquals("0 <", minimum.minimumHint());
        assertEquals("", minimum.maximumHint());
        assertTrue(minimum.accepts(1e10));
        var maximum = new NumericFieldBounds(Double.NEGATIVE_INFINITY, false, 100, true);
        assertEquals("", maximum.minimumHint());
        assertEquals("≤ 100", maximum.maximumHint());
        assertTrue(maximum.accepts(-1e10));
        assertEquals("0.05 ≤", new NumericFieldBounds(0.05, true, 0.3, true).minimumHint());
        assertEquals("", NumericFieldBounds.UNBOUNDED.minimumHint());
        assertEquals("", NumericFieldBounds.UNBOUNDED.maximumHint());
    }

    @Test
    void rejectsEmptyAndInvalidIntervals() {
        assertThrows(IllegalArgumentException.class, () -> new NumericFieldBounds(10, true, 5, true));
        assertThrows(IllegalArgumentException.class, () -> new NumericFieldBounds(5, false, 5, true));
        assertThrows(IllegalArgumentException.class, () -> new NumericFieldBounds(Double.NaN, true, 5, true));
        assertTrue(new NumericFieldBounds(5, true, 5, true).accepts(5));
    }
}
