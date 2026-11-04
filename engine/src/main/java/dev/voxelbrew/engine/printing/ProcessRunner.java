package dev.voxelbrew.engine.printing;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

final class ProcessRunner {
    private ProcessRunner() {}

    static String run(
            List<String> arguments,
            Path directory,
            Cancellation cancellation,
            Consumer<String> sink)
            throws IOException {
        cancellation.check();
        var builder = new ProcessBuilder(arguments).redirectErrorStream(true);
        if (directory != null) builder.directory(directory.toFile());
        var process = builder.start();
        var log = new StringBuilder();
        Runnable stop =
                () -> {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly();
                };
        var unregister = cancellation.onCancel(stop);
        var reader =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    try (var input =
                                            new BufferedReader(
                                                    new InputStreamReader(
                                                            process.getInputStream(),
                                                            StandardCharsets.UTF_8))) {
                                        for (String line; (line = input.readLine()) != null; ) {
                                            synchronized (log) {
                                                if (log.length() < 65536)
                                                    log.append(line).append('\n');
                                            }
                                            try {
                                                sink.accept(line);
                                            } catch (RuntimeException ignored) {
                                                /* A diagnostic listener cannot stop draining the process pipe. */
                                            }
                                        }
                                    } catch (IOException failure) {
                                        synchronized (log) {
                                            log.append(failure.getMessage());
                                        }
                                    }
                                });
        try {
            sink.accept("Starting slicer");
            long deadline = System.nanoTime() + Duration.ofMinutes(10).toNanos();
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                cancellation.check();
                if (System.nanoTime() > deadline)
                    throw new IOException("Slicer exceeded the 10 minute limit");
            }
            reader.join(5000);
            cancellation.check();
            String output;
            synchronized (log) {
                output = log.toString();
            }
            if (process.exitValue() != 0)
                throw new IOException("Slicer exited with " + process.exitValue() + ":\n" + output);
            return output;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Slicer interrupted", interrupted);
        } finally {
            if (process.isAlive()) stop.run();
            unregister.run();
        }
    }
}
