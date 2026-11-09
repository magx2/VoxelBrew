package dev.voxelbrew.slicer;

import dev.voxelbrew.engine.printing.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@lombok.extern.slf4j.Slf4j
final class ProcessRunner {
    private ProcessRunner() {}

    static String run(List<String> arguments, Path directory, Cancellation cancellation, Consumer<String> sink)
            throws IOException {
        cancellation.check();
        var builder = new ProcessBuilder(arguments).redirectErrorStream(true);
        if (directory != null) builder.directory(directory.toFile());
        var process = builder.start();
        log.debug("Started slicer process {} from {}", process.pid(), arguments.getFirst());
        var transcript = new StringBuilder();
        Runnable stop = () -> {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        };
        var unregister = cancellation.onCancel(stop);
        var reader = Thread.ofVirtual().start(() -> {
            try (var input =
                    new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = input.readLine()) != null; ) {
                    synchronized (transcript) {
                        if (transcript.length() < 65536) transcript.append(line).append('\n');
                    }
                    try {
                        sink.accept(line);
                    } catch (RuntimeException failure) {
                        log.debug("Slicer diagnostic listener failed", failure);
                    }
                }
            } catch (IOException failure) {
                synchronized (transcript) {
                    transcript.append(failure.getMessage());
                }
                log.debug("Slicer output stream closed", failure);
            }
        });
        try {
            sink.accept("Starting slicer");
            long deadline = System.nanoTime() + Duration.ofMinutes(10).toNanos();
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                cancellation.check();
                if (System.nanoTime() > deadline) throw new IOException("Slicer exceeded the 10 minute limit");
            }
            reader.join(5000);
            cancellation.check();
            String output;
            synchronized (transcript) {
                output = transcript.toString();
            }
            log.debug("Slicer process {} exited with {}", process.pid(), process.exitValue());
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
