package dev.voxelbrew.engine.update;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class UpdateCancellation {
    private final AtomicBoolean cancelled = new AtomicBoolean();

    public void cancel() {
        cancelled.set(true);
    }

    public void check() {
        if (cancelled.get() || Thread.currentThread().isInterrupted())
            throw new CancellationException("Update cancelled");
    }
}
