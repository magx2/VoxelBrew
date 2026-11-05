package dev.voxelbrew.engine.update;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumSet;

final class UpdateFiles {
    private UpdateFiles() {}

    static void copyTree(Path source, Path target, UpdateCancellation cancellation)
            throws IOException {
        Path realSource = source.toRealPath();
        Files.walkFileTree(
                source,
                EnumSet.of(FileVisitOption.FOLLOW_LINKS),
                100,
                new SimpleFileVisitor<>() {
                    private Path destination(Path path) throws IOException {
                        cancellation.check();
                        if (!path.toRealPath().startsWith(realSource))
                            throw new IOException("Runtime contains an external link: " + path);
                        return target.resolve(source.relativize(path));
                    }

                    @Override
                    public FileVisitResult preVisitDirectory(
                            Path directory, BasicFileAttributes attrs) throws IOException {
                        Files.createDirectories(destination(directory));
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                            throws IOException {
                        Files.copy(file, destination(file), StandardCopyOption.COPY_ATTRIBUTES);
                        return FileVisitResult.CONTINUE;
                    }
                });
    }

    // Called only for a freshly allocated staging directory, never an installation or backup.
    static void deleteStage(Path stage) throws IOException {
        Path root = stage.toAbsolutePath().normalize();
        if (!root.getFileName().toString().startsWith(".VoxelBrew-update-")
                || Files.isSymbolicLink(root))
            throw new IOException("Refusing to remove an unknown staging directory");
        if (!Files.exists(root)) return;
        Files.walkFileTree(
                root,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                            throws IOException {
                        // jpackage/runtime files may retain the Windows read-only attribute.
                        // Clear it only on our staged regular file, without following links.
                        if (attrs.isRegularFile()) {
                            var dos =
                                    Files.getFileAttributeView(
                                            file,
                                            java.nio.file.attribute.DosFileAttributeView.class,
                                            LinkOption.NOFOLLOW_LINKS);
                            if (dos != null && dos.readAttributes().isReadOnly())
                                dos.setReadOnly(false);
                        }
                        Files.delete(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path directory, IOException problem)
                            throws IOException {
                        if (problem != null) throw problem;
                        Files.delete(directory);
                        return FileVisitResult.CONTINUE;
                    }
                });
    }
}
