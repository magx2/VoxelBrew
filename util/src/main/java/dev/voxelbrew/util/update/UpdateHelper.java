package dev.voxelbrew.util.update;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.Properties;

/** JDK-only helper, launched with an independent runtime so Windows can move the old JVM. */
public final class UpdateHelper {
    private UpdateHelper() {}

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Expected an update job file");
            System.exit(1);
        }
        Path job = Path.of(args[0]).toAbsolutePath().normalize();
        try {
            run(job);
        } catch (Exception failure) {
            failure.printStackTrace(System.err);
            try {
                status(job.getParent(), "FAILED", failure.toString());
            } catch (IOException ignored) {
                /* stderr remains the recovery log. */
            }
            System.exit(1);
        }
    }

    @SuppressWarnings("try") // Leases protect replacement for their entire lexical lifetime.
    static void run(Path job) throws IOException, InterruptedException {
        Path stage = job.getParent();
        if (!job.getFileName().toString().equals("job.properties")
                || !stage.getFileName().toString().startsWith(".VoxelBrew-update-")
                || Files.isSymbolicLink(stage)) throw new IOException("Invalid update staging directory");
        Properties p = ApplicationImage.read(job);
        Path target = Path.of(p.getProperty("target")).toAbsolutePath().normalize();
        if (!target.getParent().equals(stage.getParent()) || Files.isSymbolicLink(target))
            throw new IOException("Update must remain beside its original installation");
        var component = ApplicationImage.Component.valueOf(p.getProperty("component"));
        String platform = p.getProperty("platform");
        var original = ApplicationImage.open(target, component, platform);
        if (!original.version().toString().equals(p.getProperty("oldVersion")))
            throw new IOException("Installation changed while preparing update");
        Path source = stage.resolve("payload").resolve(component.imageName(platform));
        var replacement = ApplicationImage.open(source, component, platform);
        if (!replacement.version().toString().equals(p.getProperty("newVersion")))
            throw new IOException("Replacement version changed");
        Path backup = target.resolveSibling("."
                + target.getFileName()
                + "-backup-"
                + stage.getFileName().toString().substring(".VoxelBrew-update-".length()));
        long pid = Long.parseLong(p.getProperty("pid"));
        Instant start = Instant.parse(p.getProperty("processStart"));
        long deadline = System.nanoTime() + java.time.Duration.ofMinutes(2).toNanos();
        try (var updateLock = waitLock(original.updateLock(), false, deadline)) {
            status(stage, "READY", "Waiting for application exit");
            while (ProcessHandle.of(pid)
                    .filter(h -> h.isAlive()
                            && h.info().startInstant().map(start::equals).orElse(true))
                    .isPresent()) {
                if (System.nanoTime() > deadline)
                    throw new IOException(
                            "Application did not exit; no files replaced. Close all instances and retry.");
                Thread.sleep(100);
            }
            try (var runningLock = waitLock(original.runningLock(), false, deadline)) {
                var current = ApplicationImage.open(target, component, platform);
                if (!current.version().equals(original.version()))
                    throw new IOException("Installation changed; no files replaced");
                status(stage, "INSTALLING", "Installing " + replacement.version());
                replace(target, source, backup, UpdateHelper::move);
                status(stage, "SUCCESS", "Installed " + replacement.version() + "; backup: " + backup);
            }
        }
    }

    private static ApplicationImage.Lease waitLock(Path file, boolean shared, long deadline)
            throws IOException, InterruptedException {
        while (true) {
            try {
                return ApplicationImage.lock(file, shared);
            } catch (IOException failure) {
                if (System.nanoTime() > deadline)
                    throw new IOException(
                            "Installation is busy. Close all instances and retry; no update was installed.", failure);
                Thread.sleep(100);
            }
        }
    }

    @FunctionalInterface
    interface Move {
        void move(Path from, Path to) throws IOException;
    }

    static void replace(Path target, Path source, Path backup, Move move) throws IOException {
        if (Files.exists(backup)) throw new IOException("Backup already exists; refusing to overwrite it");
        move.move(target, backup);
        try {
            move.move(source, target);
        } catch (IOException installFailure) {
            try {
                move.move(backup, target);
            } catch (IOException restoreFailure) {
                installFailure.addSuppressed(restoreFailure);
                throw new IOException(
                        "Could not restore installation. Recover the untouched backup at " + backup, installFailure);
            }
            throw new IOException("Update failed; original installation restored", installFailure);
        }
    }

    private static void move(Path from, Path to) throws IOException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (true) {
            try {
                Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
                return;
            } catch (java.nio.file.AccessDeniedException locked) {
                if (System.nanoTime() > deadline) throw locked;
                try {
                    Thread.sleep(100);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Replacement interrupted", interrupted);
                }
            }
        }
    }

    static void status(Path stage, String state, String message) throws IOException {
        var p = new Properties();
        p.setProperty("state", state);
        p.setProperty("message", message);
        Path temporary = Files.createTempFile(stage, "status-", ".tmp");
        try {
            try (var output = Files.newOutputStream(temporary)) {
                p.store(output, "VoxelBrew update status");
            }
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
            while (true) {
                try {
                    Files.move(
                            temporary,
                            stage.resolve("status.properties"),
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                    break;
                } catch (AccessDeniedException reader) {
                    // Windows may briefly deny replacement while the parent is reading status.
                    if (System.nanoTime() > deadline) throw reader;
                    try {
                        Thread.sleep(25);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Status publication interrupted", interrupted);
                    }
                }
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
