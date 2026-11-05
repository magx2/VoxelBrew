package dev.voxelbrew.util.concurrent;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/**
 * Bounded CPU workers for independent operations. Every call joins all its work before returning.
 */
public final class CpuWork {
    private CpuWork() {}

    private static final ThreadLocal<Boolean> WORKER = ThreadLocal.withInitial(() -> false);

    private static final class Shared {
        static final WorkPool POOL = new WorkPool(configuredParallelism());
    }

    private static int configuredParallelism() {
        String configured = System.getProperty("voxelbrew.compute.threads");
        if (configured == null)
            return Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));
        try {
            return Integer.parseInt(configured);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(
                    "voxelbrew.compute.threads must be an integer from 1 to 32", failure);
        }
    }

    public static int parallelism() {
        return Shared.POOL.parallelism;
    }

    /** Each index is independent; callbacks must not access GUI state or mutate shared inputs. */
    public static void forEach(int size, IntConsumer operation) {
        Shared.POOL.forEach(size, operation);
    }

    /**
     * Preserves input order. Only detached results should escape a native resource's owning scope.
     */
    public static <T, R> List<R> map(List<T> input, Function<? super T, ? extends R> operation) {
        var snapshot = List.copyOf(input);
        var result = new ArrayList<R>(Collections.nCopies(snapshot.size(), null));
        forEach(snapshot.size(), index -> result.set(index, operation.apply(snapshot.get(index))));
        return List.copyOf(result);
    }

    // Separate instances let lifecycle/concurrency tests exercise real workers without global
    // overrides.
    static final class WorkPool implements AutoCloseable {
        private final int parallelism;
        private final ExecutorService executor;

        WorkPool(int parallelism) {
            if (parallelism < 1 || parallelism > 32)
                throw new IllegalArgumentException(
                        "voxelbrew.compute.threads must be from 1 to 32");
            this.parallelism = parallelism;
            executor =
                    parallelism == 1
                            ? null
                            : Executors.newFixedThreadPool(
                                    parallelism,
                                    Thread.ofPlatform()
                                            .daemon()
                                            .name("voxelbrew-compute-", 0)
                                            .factory());
        }

        void forEach(int size, IntConsumer operation) {
            if (size < 0) throw new IllegalArgumentException("Negative work size");
            Objects.requireNonNull(operation);
            checkInterrupted();
            if (size < 2 || executor == null || WORKER.get()) {
                for (int i = 0; i < size; i++) {
                    checkInterrupted();
                    operation.accept(i);
                }
                return;
            }
            int workers = Math.min(size, parallelism);
            var finished = new CountDownLatch(workers);
            var next = new AtomicInteger();
            var stop = new AtomicBoolean();
            var failures = new Throwable[workers];
            for (int worker = 0; worker < workers; worker++) {
                final int slot = worker;
                try {
                    executor.execute(
                            () -> {
                                WORKER.set(true);
                                try {
                                    while (!stop.get()) {
                                        int index = next.getAndIncrement();
                                        if (index >= size) break;
                                        operation.accept(index);
                                    }
                                } catch (RuntimeException | Error failure) {
                                    failures[slot] = failure;
                                    stop.set(true);
                                } finally {
                                    WORKER.remove();
                                    finished.countDown();
                                }
                            });
                } catch (RuntimeException | Error failure) {
                    failures[slot] = failure;
                    stop.set(true);
                    finished.countDown();
                }
            }
            boolean interrupted = false;
            for (; ; ) {
                try {
                    finished.await();
                    break;
                } catch (InterruptedException cancelled) {
                    interrupted = true;
                    stop.set(true);
                    // Future.cancel() would report completion before JNI actually exits. Drain
                    // instead.
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
            checkInterrupted();
            for (var failure : failures) {
                if (failure instanceof RuntimeException runtime) throw runtime;
                if (failure instanceof Error error) throw error;
            }
        }

        @Override
        public void close() {
            if (executor != null) executor.close();
        }
    }

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted())
            throw new CancellationException("Computation cancelled");
    }
}
