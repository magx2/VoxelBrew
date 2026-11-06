package dev.voxelbrew.util.logging;

import dev.voxelbrew.util.UuidGenerator;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.logging.ErrorManager;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

/** Cooperating JVMs reopen app.log under a short lock so Windows can also rotate live sessions. */
final class RollingAppLog extends Handler {
    private static final UuidGenerator logIds = new UuidGenerator();
    private static final Object[] LOCKS = new Object[32];
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss-SSSSSSSSS").withZone(ZoneOffset.UTC);

    static {
        Arrays.setAll(LOCKS, ignored -> new Object());
    }

    private final Path directory;
    private final Path active;
    private final Path lockFile;
    private final long limit;
    private final int archives;
    private final Object monitor;
    private boolean closed;

    RollingAppLog(Path directory, long limit, int archives) throws IOException {
        Files.createDirectories(directory);
        this.directory = directory.toRealPath();
        this.active = this.directory.resolve("app.log");
        this.lockFile = this.directory.resolve(".app.log.lock");
        this.limit = limit;
        this.archives = archives;
        this.monitor = LOCKS[Math.floorMod(active.hashCode(), LOCKS.length)];
        setEncoding(StandardCharsets.UTF_8.name());
        synchronized (monitor) {
            locked(() -> {
                rotate();
                // Create a fresh file even when this application has not emitted an event
                // yet.
                Files.write(active, new byte[0], StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            });
        }
    }

    @Override
    public void publish(LogRecord record) {
        if (!isLoggable(record)) return;
        synchronized (monitor) {
            if (closed) return;
            try {
                byte[] bytes = getFormatter().format(record).getBytes(StandardCharsets.UTF_8);
                locked(() -> {
                    long size = Files.exists(active) ? Files.size(active) : 0;
                    if (size > 0 && (size >= limit || bytes.length > limit - size)) rotate();
                    try (var output = FileChannel.open(
                            active, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                        var buffer = ByteBuffer.wrap(bytes);
                        while (buffer.hasRemaining()) output.write(buffer);
                    }
                });
            } catch (IOException | RuntimeException failure) {
                reportError("Cannot write VoxelBrew app.log", failure, ErrorManager.WRITE_FAILURE);
            }
        }
    }

    @SuppressWarnings("try")
    private void locked(IoOperation operation) throws IOException {
        // One channel at a time per canonical directory, including within this JVM.
        try (var channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                var lock = channel.lock()) {
            operation.run();
        }
    }

    private void rotate() throws IOException {
        if (Files.exists(active) && Files.size(active) > 0) {
            Path archive = directory.resolve("app-"
                    + STAMP.format(Instant.now())
                    + "-"
                    + ProcessHandle.current().pid()
                    + "-"
                    + logIds.next()
                    + ".log");
            try {
                Files.move(active, archive, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(active, archive);
            }
        }
        try (var files = Files.list(directory)) {
            var old = files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path ->
                            path.getFileName().toString().matches("app-\\d{8}-\\d{6}-\\d{9}-[0-9]+-[0-9]+\\.log"))
                    .sorted(Comparator.reverseOrder())
                    .skip(archives)
                    .toList();
            for (var path : old) Files.delete(path);
        }
    }

    @Override
    public void flush() {
        // Each record is written and its output channel closed before publish returns.
    }

    @Override
    public void close() {
        synchronized (monitor) {
            closed = true;
        }
    }

    @FunctionalInterface
    private interface IoOperation {
        void run() throws IOException;
    }
}
