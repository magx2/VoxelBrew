package dev.voxelbrew.importer;

import java.io.*;
import java.nio.file.*;

/** Bounded local reads owned by built-in importers. */
public final class ImportInput {
    public static final long MAX_INPUT_BYTES = 256L * 1024 * 1024;

    private ImportInput() {}

    public static InputStream open(Path source) throws IOException {
        if (Files.size(source) > MAX_INPUT_BYTES) throw new IOException("Import exceeds 256 MiB input limit");
        return new BoundedInput(Files.newInputStream(source));
    }

    private static final class BoundedInput extends FilterInputStream {
        private long count;

        BoundedInput(InputStream input) {
            super(input);
        }

        private void consumed(long amount) throws IOException {
            count += amount;
            if (count > MAX_INPUT_BYTES) throw new IOException("Import exceeds 256 MiB input limit");
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
        public void close() throws IOException {
            in.close();
        }
    }
}
