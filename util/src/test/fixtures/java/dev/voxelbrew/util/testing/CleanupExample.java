package dev.voxelbrew.util.testing;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deliberately failing fixture, executed only by the cleanup contract's nested test engine. */
public class CleanupExample {
    public static final List<Path> paths = new ArrayList<>();

    @TempDir
    Path directory;

    private void write() throws IOException {
        paths.add(directory);
        Files.writeString(Files.createDirectories(directory.resolve("nested")).resolve("locked.txt"), "test");
    }

    @Test
    void successful() throws IOException {
        write();
    }

    @Test
    void failing() throws IOException {
        write();
        fail("Original test failure");
    }
}
