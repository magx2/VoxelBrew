package dev.voxelbrew.importer;

import dev.voxelbrew.importer.api.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Host-owned, bounded input and provider validation. Importing never writes the source file. */
@lombok.extern.slf4j.Slf4j
public final class ImportService {
    public static final long MAX_INPUT_BYTES = 256L * 1024 * 1024;

    private ImportService() {}

    public static ImportResult read(
            ImporterRegistry registry, String format, Path source, Map<String, String> supplied)
            throws IOException {
        var entry = registry.require(format);
        var descriptor = entry.descriptor();
        var options = new LinkedHashMap<String, String>();
        for (var option : descriptor.options()) options.put(option.key(), option.defaultValue());
        for (var value : supplied.entrySet()) {
            var spec =
                    descriptor.options().stream()
                            .filter(o -> o.key().equals(value.getKey()))
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "Unknown option '"
                                                            + value.getKey()
                                                            + "' for "
                                                            + format));
            spec.validate(value.getValue());
            options.put(value.getKey(), value.getValue());
        }
        if (Files.size(source) > MAX_INPUT_BYTES)
            throw new IOException("Import exceeds 256 MiB input limit");
        log.info("Importing {} using {}", source, format);
        try (var input = Files.newInputStream(source)) {
            var borrowed = new BoundedInput(input);
            var result =
                    Objects.requireNonNull(
                            entry.provider().read(borrowed, Map.copyOf(options)),
                            "Importer returned no result");
            borrowed.consumed(0);
            if (result.kind() != descriptor.resultKind())
                throw new IOException(
                        "Importer returned a different result kind than its descriptor");
            log.info("Imported {} as {}", source, result.kind());
            return result;
        } catch (IOException
                | RuntimeException
                | LinkageError
                | ServiceConfigurationError
                | AssertionError failure) {
            log.error("Import failed for {} using {}", source, format, failure);
            throw new IOException(
                    "Importer '" + format + "' failed: " + failure.getMessage(), failure);
        }
    }

    private static final class BoundedInput extends FilterInputStream {
        private long count;

        BoundedInput(InputStream input) {
            super(input);
        }

        private void consumed(long amount) throws IOException {
            count += amount;
            if (count > MAX_INPUT_BYTES)
                throw new IOException("Import exceeds 256 MiB input limit");
        }

        @Override
        public int read() throws IOException {
            consumed(0);
            int value = in.read();
            if (value >= 0) consumed(1);
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            consumed(0);
            int read = in.read(bytes, offset, (int) Math.min(length, MAX_INPUT_BYTES - count + 1));
            if (read > 0) consumed(read);
            return read;
        }

        @Override
        public long skip(long bytes) throws IOException {
            consumed(0);
            long skipped = in.skip(Math.max(0, Math.min(bytes, MAX_INPUT_BYTES - count + 1)));
            consumed(skipped);
            return skipped;
        }

        @Override
        public boolean markSupported() {
            return false;
        }

        @Override
        public void mark(int limit) {}

        @Override
        public void reset() throws IOException {
            throw new IOException("Import stream does not support reset");
        }

        @Override
        public void close() {
            /* The host owns and closes the underlying file. */
        }
    }
}
