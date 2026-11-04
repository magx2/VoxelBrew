package dev.voxelbrew.engine.printing;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class Cancellation {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private boolean published;
    private final java.util.List<Runnable> listeners = new java.util.ArrayList<>();

    public synchronized boolean cancel() {
        if (published) return false;
        cancelled.set(true);
        for (var listener : java.util.List.copyOf(listeners)) listener.run();
        return true;
    }

    public boolean cancelled() {
        return cancelled.get();
    }

    public void check() {
        if (cancelled()) throw new CancellationException("Slicing cancelled");
    }

    synchronized void publish(SliceService.Commit action) throws java.io.IOException {
        check();
        action.run();
        published = true;
    }

    synchronized Runnable onCancel(Runnable listener) {
        if (cancelled()) listener.run();
        else listeners.add(listener);
        return () -> {
            synchronized (this) {
                listeners.remove(listener);
            }
        };
    }
}
