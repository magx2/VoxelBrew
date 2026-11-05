package dev.voxelbrew.engine.concurrent;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class CpuWorkTest {
    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "worker rendezvous timed out");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    @Test
    void workersOverlapStayBoundedVisitEachIndexAndAllowNestedWork() throws Exception {
        try (var pool = new CpuWork.WorkPool(3);
                var caller = Executors.newSingleThreadExecutor()) {
            var started = new CountDownLatch(3);
            var release = new CountDownLatch(1);
            var active = new AtomicInteger();
            var peak = new AtomicInteger();
            var visits = new AtomicIntegerArray(48);
            var result =
                    caller.submit(
                            () ->
                                    pool.forEach(
                                            48,
                                            index -> {
                                                int count = active.incrementAndGet();
                                                peak.accumulateAndGet(count, Math::max);
                                                started.countDown();
                                                await(release);
                                                pool.forEach(
                                                        2,
                                                        ignored -> visits.incrementAndGet(index));
                                                active.decrementAndGet();
                                            }));
            try {
                await(started);
                assertEquals(3, active.get());
            } finally {
                release.countDown();
            }
            result.get(10, TimeUnit.SECONDS);
            assertEquals(3, peak.get());
            for (int i = 0; i < 48; i++) assertEquals(2, visits.get(i));
        }
        assertEquals(List.of(9, 4, 1), CpuWork.map(List.of(3, 2, 1), n -> n * n));
    }

    @Test
    void failureDoesNotReturnWhileAnotherWorkerStillOwnsResources() throws Exception {
        try (var pool = new CpuWork.WorkPool(2);
                var caller = Executors.newSingleThreadExecutor()) {
            var started = new CountDownLatch(2);
            var failed = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var exception = new IllegalStateException("meshing failed");
            var result =
                    caller.submit(
                            () ->
                                    pool.forEach(
                                            2,
                                            index -> {
                                                started.countDown();
                                                await(started);
                                                if (index == 0) {
                                                    failed.countDown();
                                                    throw exception;
                                                }
                                                await(release);
                                            }));
            try {
                await(failed);
                assertThrows(TimeoutException.class, () -> result.get(100, TimeUnit.MILLISECONDS));
            } finally {
                release.countDown();
            }
            assertSame(
                    exception,
                    assertThrows(ExecutionException.class, () -> result.get(10, TimeUnit.SECONDS))
                            .getCause());
        }
    }

    @Test
    void interruptionDrainsInFlightWorkAndPreservesInterruptStatus() throws Exception {
        try (var pool = new CpuWork.WorkPool(2)) {
            var started = new CountDownLatch(2);
            var release = new CountDownLatch(1);
            var returned = new CountDownLatch(1);
            var failure = new AtomicReference<Throwable>();
            var interrupted = new AtomicBoolean();
            var caller =
                    Thread.ofPlatform()
                            .start(
                                    () -> {
                                        try {
                                            pool.forEach(
                                                    20,
                                                    index -> {
                                                        started.countDown();
                                                        await(release);
                                                    });
                                        } catch (Throwable problem) {
                                            failure.set(problem);
                                            interrupted.set(Thread.currentThread().isInterrupted());
                                        } finally {
                                            returned.countDown();
                                        }
                                    });
            try {
                await(started);
                caller.interrupt();
                assertFalse(returned.await(100, TimeUnit.MILLISECONDS));
            } finally {
                release.countDown();
            }
            assertTrue(caller.join(java.time.Duration.ofSeconds(10)));
            assertInstanceOf(CancellationException.class, failure.get());
            assertTrue(interrupted.get());
        }
    }

    @Test
    void serialModeHasNoWorkersAndRejectsInvalidBudgets() {
        try (var pool = new CpuWork.WorkPool(1)) {
            var caller = Thread.currentThread();
            pool.forEach(10, index -> assertSame(caller, Thread.currentThread()));
        }
        assertThrows(IllegalArgumentException.class, () -> new CpuWork.WorkPool(0));
        assertThrows(IllegalArgumentException.class, () -> new CpuWork.WorkPool(33));
    }
}
