package dev.voxelbrew.util;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class UuidGeneratorTest {
    @Test
    void startsAtOneAndResumesWithoutReusingIds() {
        var ids = new UuidGenerator();
        assertEquals(1, ids.next());
        assertEquals(2, ids.next());
        assertEquals(3, new UuidGenerator(ids.nextValue()).next());
    }

    @Test
    void concurrentCallsAreUnique() {
        var ids = new UuidGenerator();
        assertEquals(
                10000,
                java.util.stream.IntStream.range(0, 10000)
                        .parallel()
                        .mapToLong(i -> ids.next())
                        .distinct()
                        .count());
    }

    @Test
    void invalidAndExhaustedSequencesFail() {
        assertThrows(IllegalArgumentException.class, () -> new UuidGenerator(0));
        assertThrows(IllegalStateException.class, () -> new UuidGenerator(Long.MAX_VALUE).next());
    }
}
