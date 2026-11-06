package dev.voxelbrew.util;

import java.util.concurrent.atomic.AtomicLong;

/** Monotonic identifiers owned by one document or service. */
public final class UuidGenerator {
    private final AtomicLong uuid;

    public UuidGenerator() {
        this(1);
    }

    public UuidGenerator(long next) {
        if (next < 1) throw new IllegalArgumentException("Next UUID must be positive");
        uuid = new AtomicLong(next);
    }

    public long next() {
        return uuid.getAndUpdate(value -> {
            if (value == Long.MAX_VALUE) throw new IllegalStateException("UUID sequence exhausted");
            return value + 1;
        });
    }

    public long nextValue() {
        return uuid.get();
    }
}
