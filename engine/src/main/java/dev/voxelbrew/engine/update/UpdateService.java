package dev.voxelbrew.engine.update;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** Shared explicit update workflow. No network access occurs at application startup. */
public final class UpdateService {
    public record Request(ReleaseVersion version, boolean force) {
        public Request {
            if (version != null && version.snapshot())
                throw new IllegalArgumentException("Select a published release version");
        }
    }

    public record Check(
            ReleaseSource.Release release,
            String asset,
            boolean available,
            boolean installable,
            String message) {}

    private final ApplicationImage.Component component;
    private final ReleaseVersion current;
    private final String platform;
    private final ReleaseSource source;
    private final Optional<ApplicationImage> installation;

    public UpdateService(
            ApplicationImage.Component component,
            ReleaseVersion current,
            String platform,
            ReleaseSource source,
            Optional<ApplicationImage> installation) {
        this.component = Objects.requireNonNull(component);
        this.current = Objects.requireNonNull(current);
        this.platform = Objects.requireNonNull(platform);
        this.source = Objects.requireNonNull(source);
        this.installation = Objects.requireNonNull(installation);
        installation.ifPresent(
                image -> {
                    if (image.component() != component
                            || !image.platform().equals(platform)
                            || !image.version().equals(current))
                        throw new IllegalArgumentException(
                                "Updater and installation identities must match");
                });
    }

    public static UpdateService system(ApplicationImage.Component component) throws IOException {
        return new UpdateService(
                component,
                ReleaseVersion.parse(BuildVersion.current()),
                ApplicationImage.currentPlatform(),
                new GitHubReleaseSource(),
                ApplicationImage.detect(component));
    }

    public Check check(Request request, UpdateCancellation cancellation) throws IOException {
        var release = source.fetch(request.version(), cancellation);
        cancellation.check();
        if (request.version() != null && !release.version().equals(request.version()))
            throw new IOException("Release source returned the wrong version");
        String asset = component.asset(release.version(), platform);
        boolean available =
                request.version() != null
                        ? !release.version().equals(current) || request.force()
                        : release.version().compareTo(current) > 0
                                || (request.force() && release.version().equals(current));
        if (!release.assets().contains(asset))
            throw new IOException(
                    "Release "
                            + release.tag()
                            + " has no qualified "
                            + component
                            + " download for "
                            + platform);
        if (!release.assets().contains("SHA256SUMS"))
            throw new IOException("Release is missing required SHA256SUMS; update refused");
        String message =
                available
                        ? "Update available: " + current + " → " + release.version()
                        : "Already current: "
                                + current
                                + " (selected release "
                                + release.version()
                                + ")";
        if (installation.isEmpty())
            message +=
                    ". Installation requires a released app image; development distributions are not replaced.";
        return new Check(release, asset, available, installation.isPresent(), message);
    }

    public Prepared prepare(Check check, UpdateCancellation cancellation, Consumer<String> progress)
            throws IOException {
        if (!check.available()) throw new IOException("No update selected");
        var image =
                installation.orElseThrow(
                        () ->
                                new IOException(
                                        "Cannot self-update a development distribution or standalone JAR. Use the released bundled application."));
        if (!check.asset().equals(component.asset(check.release().version(), platform)))
            throw new IOException("Wrong application update asset");
        ApplicationImage.Lease lease = ApplicationImage.lock(image.updateLock(), false);
        Path stage = null;
        boolean complete = false;
        try {
            cancellation.check();
            stage = Files.createTempDirectory(image.root().getParent(), ".VoxelBrew-update-");
            Path archive = stage.resolve(check.asset()), checksums = stage.resolve("SHA256SUMS");
            progress.accept("Downloading " + check.asset());
            source.download(check.release(), check.asset(), archive, cancellation);
            source.download(check.release(), "SHA256SUMS", checksums, cancellation);
            progress.accept("Verifying SHA-256 checksum");
            UpdateArchive.verify(archive, checksums, check.asset(), cancellation);
            progress.accept("Preparing application and native libraries");
            Path payload =
                    UpdateArchive.extract(
                            archive,
                            stage.resolve("payload"),
                            component.imageName(platform),
                            cancellation);
            var replacement = ApplicationImage.open(payload, component, platform);
            if (!replacement.version().equals(check.release().version()))
                throw new IOException("Downloaded image has the wrong product version");
            if (!platform.startsWith("windows-")
                    && (!Files.isExecutable(replacement.launcher())
                            || !Files.isExecutable(replacement.runtime().resolve("bin/java"))))
                throw new IOException("Downloaded image is missing Unix executable permissions");
            progress.accept("Preparing independent update helper");
            Path worker = Files.createDirectory(stage.resolve("worker"));
            UpdateFiles.copyTree(image.runtime(), worker.resolve("runtime"), cancellation);
            Files.copy(
                    image.appDirectory().resolve(image.engineJar()), worker.resolve("updater.jar"));
            cancellation.check();
            var p = new Properties();
            p.setProperty("target", image.root().toString());
            p.setProperty("component", component.name());
            p.setProperty("platform", platform);
            p.setProperty("oldVersion", image.version().toString());
            p.setProperty("newVersion", replacement.version().toString());
            p.setProperty("pid", Long.toString(ProcessHandle.current().pid()));
            p.setProperty(
                    "processStart",
                    ProcessHandle.current()
                            .info()
                            .startInstant()
                            .orElseThrow(
                                    () ->
                                            new IOException(
                                                    "Cannot identify current process start time"))
                            .toString());
            try (var output = Files.newOutputStream(stage.resolve("job.properties"))) {
                p.store(output, "VoxelBrew staged update");
            }
            UpdateHelper.status(
                    stage, "PREPARED", "Ready to install " + replacement.version() + " after exit");
            complete = true;
            return new Prepared(stage, lease, platform);
        } finally {
            if (!complete) {
                try {
                    lease.close();
                } finally {
                    if (stage != null) UpdateFiles.deleteStage(stage);
                }
            }
        }
    }

    public static final class Prepared implements AutoCloseable {
        private final Path stage;
        private ApplicationImage.Lease lease;
        private final String platform;
        private boolean handedOff, closed;

        Prepared(Path stage, ApplicationImage.Lease lease, String platform) {
            this.stage = stage;
            this.lease = lease;
            this.platform = platform;
        }

        public Path statusFile() {
            return stage.resolve("status.properties");
        }

        public Path logFile() {
            return stage.resolve("helper.log");
        }

        public synchronized void handOff() throws IOException {
            if (closed || handedOff) throw new IOException("Update is no longer available");
            Path worker = stage.resolve("worker");
            String executable = platform.startsWith("windows-") ? "javaw.exe" : "java";
            if (platform.startsWith("windows-")
                    && !Files.isRegularFile(worker.resolve("runtime/bin/" + executable)))
                executable = "java.exe";
            var process =
                    new ProcessBuilder(
                                    worker.resolve("runtime/bin/" + executable).toString(),
                                    "-cp",
                                    worker.resolve("updater.jar").toString(),
                                    UpdateHelper.class.getName(),
                                    stage.resolve("job.properties").toString())
                            .directory(worker.toFile())
                            .redirectOutput(logFile().toFile())
                            .redirectErrorStream(true)
                            .start();
            try {
                lease.close();
                lease = null;
                long deadline = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
                while (process.isAlive() && System.nanoTime() < deadline) {
                    var status = ApplicationImage.read(statusFile());
                    if ("READY".equals(status.getProperty("state"))) {
                        handedOff = true;
                        return;
                    }
                    if ("FAILED".equals(status.getProperty("state")))
                        throw new IOException(status.getProperty("message"));
                    Thread.sleep(50);
                }
                String detail =
                        Files.exists(logFile()) ? Files.readString(logFile()) : "No helper output";
                throw new IOException("Update helper did not become ready: " + detail);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Update handoff interrupted", interrupted);
            } finally {
                if (!handedOff) {
                    process.destroyForcibly();
                    try {
                        process.waitFor();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        @Override
        public synchronized void close() throws IOException {
            if (closed) return;
            closed = true;
            if (lease != null) lease.close();
            if (!handedOff) UpdateFiles.deleteStage(stage);
        }
    }
}
