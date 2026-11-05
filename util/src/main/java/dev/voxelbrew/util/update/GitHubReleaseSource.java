package dev.voxelbrew.util.update;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Uses the same gh authentication and explicit release lookup as bb. Never stores a token. */
public final class GitHubReleaseSource implements ReleaseSource {
    public static final String REPOSITORY = "magx2/VoxelBrew";
    private final String executable;

    public GitHubReleaseSource() {
        this(
                Optional.ofNullable(System.getenv("VOXELBREW_GH"))
                        .filter(s -> !s.isBlank())
                        .orElse("gh"));
    }

    public GitHubReleaseSource(String executable) {
        this.executable = Objects.requireNonNull(executable);
    }

    @Override
    public Release fetch(ReleaseVersion requested, UpdateCancellation cancellation)
            throws IOException {
        String endpoint =
                "repos/"
                        + REPOSITORY
                        + "/releases/"
                        + (requested == null ? "latest" : "tags/v" + requested);
        return parse(
                run(
                        List.of(
                                "api",
                                "--hostname",
                                "github.com",
                                endpoint,
                                "--jq",
                                "[.tag_name, (.draft|tostring), (.prerelease|tostring), (.assets[]|.name)] | .[]"),
                        Duration.ofSeconds(45),
                        cancellation,
                        null),
                requested);
    }

    static Release parse(String output, ReleaseVersion requested) throws IOException {
        var lines = output.lines().toList();
        try {
            if (lines.size() < 3 || !lines.get(1).equals("false") || !lines.get(2).equals("false"))
                throw new IOException("Only published stable releases can be installed");
            var version = ReleaseVersion.release(lines.getFirst());
            if (!lines.getFirst().equals("v" + version)
                    || (requested != null && !version.equals(requested)))
                throw new IOException("GitHub returned a different release tag");
            var assets = new HashSet<String>();
            for (String asset : lines.subList(3, lines.size())) {
                if (!asset.matches("[A-Za-z0-9._-]+") || !assets.add(asset))
                    throw new IOException("Invalid or duplicate release asset");
            }
            return new Release(version, assets);
        } catch (IllegalArgumentException failure) {
            throw new IOException("Invalid GitHub release metadata", failure);
        }
    }

    @Override
    public void download(
            Release release, String asset, Path destination, UpdateCancellation cancellation)
            throws IOException {
        if (!release.assets().contains(asset) || !asset.matches("[A-Za-z0-9._-]+"))
            throw new IOException("Unknown release asset");
        if (Files.exists(destination)) throw new IOException("Download destination already exists");
        run(
                List.of(
                        "release",
                        "download",
                        release.tag(),
                        "--repo",
                        "github.com/" + REPOSITORY,
                        "--pattern",
                        asset,
                        "--output",
                        destination.toString()),
                Duration.ofMinutes(10),
                cancellation,
                destination);
        if (!Files.isRegularFile(destination) || Files.size(destination) == 0)
            throw new IOException("Empty download: " + asset);
    }

    private String run(
            List<String> args, Duration timeout, UpdateCancellation cancellation, Path download)
            throws IOException {
        cancellation.check();
        var command = new ArrayList<String>();
        command.add(executable);
        command.addAll(args);
        final Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException failure) {
            throw new IOException(
                    "GitHub CLI is required for updates. Install gh, run 'gh auth login', or set VOXELBREW_GH to its executable.",
                    failure);
        }
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        var readFailure = new AtomicReference<IOException>();
        Thread stdout = drain(process.getInputStream(), out, readFailure);
        Thread stderr = drain(process.getErrorStream(), err, readFailure);
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                cancellation.check();
                if (System.nanoTime() > deadline)
                    throw new IOException("GitHub update request timed out; try again");
                if (download != null
                        && Files.exists(download)
                        && Files.size(download) > UpdateArchive.MAX_ARCHIVE_BYTES)
                    throw new IOException("Release download exceeds size limit");
            }
            stdout.join(5000);
            stderr.join(5000);
            cancellation.check();
            if (stdout.isAlive() || stderr.isAlive())
                throw new IOException("GitHub process did not close its output streams");
            if (readFailure.get() != null) throw readFailure.get();
            if (process.exitValue() != 0)
                throw new IOException(
                        "Cannot access the published GitHub release. Check 'gh auth status', repository access, and the selected release. "
                                + err.toString(StandardCharsets.UTF_8).strip());
            return out.toString(StandardCharsets.UTF_8);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException("Update interrupted");
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            if (process.isAlive()) process.destroyForcibly();
            process.getInputStream().close();
            process.getErrorStream().close();
            process.getOutputStream().close();
        }
    }

    private static Thread drain(
            InputStream input, ByteArrayOutputStream result, AtomicReference<IOException> failure) {
        return Thread.ofVirtual()
                .start(
                        () -> {
                            try (input) {
                                byte[] buffer = new byte[8192];
                                for (int read; (read = input.read(buffer)) != -1; ) {
                                    if (result.size() + read <= 1024 * 1024)
                                        result.write(buffer, 0, read);
                                    else
                                        failure.compareAndSet(
                                                null,
                                                new IOException(
                                                        "GitHub response exceeds size limit"));
                                }
                            } catch (IOException problem) {
                                failure.compareAndSet(null, problem);
                            }
                        });
    }
}
