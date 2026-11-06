package dev.voxelbrew.util.testing;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.extension.AnnotatedElementContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDirFactory;

/** Test-only temporary directories: deletion problems must never replace a test's result. */
@Slf4j
public class TestDirectoryFactory implements TempDirFactory {
    private Path directory;
    private Path realDirectory;

    @Override
    public Path createTempDirectory(AnnotatedElementContext element, ExtensionContext context) throws IOException {
        directory = Files.createTempDirectory("voxelbrew-test-");
        realDirectory = directory.toRealPath();
        return directory;
    }

    @Override
    public void close() {
        if (directory == null) return;
        try {
            // No FOLLOW_LINKS: links and Windows junctions are removed, never traversed.
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) throws IOException {
                    // Also guard Windows junctions, which can appear as ordinary
                    // directories.
                    Path expected = realDirectory.resolve(directory.relativize(dir));
                    if (!dir.toRealPath().equals(expected)) {
                        remove(dir);
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    remove(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException failure) {
                    if (failure instanceof NoSuchFileException) return FileVisitResult.CONTINUE;
                    log.error("Could not access test temporary file for cleanup: {}", file, failure);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException failure) {
                    if (failure != null) log.error("Could not list test directory: {}", dir, failure);
                    remove(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | RuntimeException failure) {
            log.error("Could not clean test temporary directory: {}", directory, failure);
        }
    }

    private void remove(Path path) {
        try {
            delete(path);
        } catch (IOException | RuntimeException failure) {
            log.error("Could not delete test temporary path: {}", path, failure);
        }
    }

    // Override only in cleanup fault-injection tests; normal tests use the default factory.
    protected void delete(Path path) throws IOException {
        Files.deleteIfExists(path);
    }
}
