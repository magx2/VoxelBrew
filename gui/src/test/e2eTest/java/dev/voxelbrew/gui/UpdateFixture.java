package dev.voxelbrew.gui;

import com.sun.net.httpserver.*;
import dev.voxelbrew.util.update.*;
import java.io.IOException;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.*;
import org.apache.commons.compress.archivers.zip.*;

/** A small offline release for exercising the real dialog up to the unsaved-document guard. */
final class UpdateFixture implements AutoCloseable {
    private final Path directory;
    private final ApplicationImage installed;
    private final ReleaseSource.Release release;
    private final HttpServer server;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean credentialsSeen;
    volatile boolean slow;
    volatile boolean current, development;
    volatile int failureStatus;
    final AtomicInteger requests = new AtomicInteger();
    volatile ReleaseVersion requested;

    UpdateFixture(Path directory) throws Exception {
        this.directory = directory;
        installed = image(directory.resolve("installed"), "1.0.0");
        var replacement = image(directory.resolve("replacement"), "1.1.0");
        String asset =
                ApplicationImage.Component.GUI.asset(replacement.version(), replacement.platform());
        Path archive = directory.resolve(asset);
        try (var zip = new ZipArchiveOutputStream(archive);
                var files = Files.walk(replacement.root())) {
            for (var file : files.filter(Files::isRegularFile).toList()) {
                var entry =
                        new ZipArchiveEntry(
                                replacement
                                        .root()
                                        .getParent()
                                        .relativize(file)
                                        .toString()
                                        .replace('\\', '/'));
                entry.setUnixMode(0100755);
                zip.putArchiveEntry(entry);
                Files.copy(file, zip);
                zip.closeArchiveEntry();
            }
        }
        String digest =
                HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(Files.readAllBytes(archive)));
        Files.writeString(directory.resolve("SHA256SUMS"), digest + "  " + asset + "\n");
        release = new ReleaseSource.Release(replacement.version(), Set.of(asset, "SHA256SUMS"));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(workers);
        server.createContext("/", this::respond);
        server.start();
    }

    UpdateService service() {
        return new UpdateService(
                ApplicationImage.Component.GUI,
                installed.version(),
                installed.platform(),
                new GitHubReleaseSource(
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/")),
                development ? Optional.empty() : Optional.of(installed));
    }

    private void respond(HttpExchange exchange) throws IOException {
        try (exchange) {
            credentialsSeen |=
                    exchange.getRequestHeaders().containsKey("Authorization")
                            || exchange.getRequestHeaders().containsKey("Cookie");
            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/repos/")) {
                requests.incrementAndGet();
                requested =
                        path.endsWith("/latest")
                                ? null
                                : ReleaseVersion.release(path.substring(path.lastIndexOf('/') + 1));
                while (slow) {
                    try {
                        Thread.sleep(25);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                if (failureStatus != 0) {
                    exchange.sendResponseHeaders(failureStatus, -1);
                    return;
                }
                var selected =
                        current
                                ? new ReleaseSource.Release(
                                        installed.version(),
                                        Set.of(
                                                ApplicationImage.Component.GUI.asset(
                                                        installed.version(), installed.platform()),
                                                "SHA256SUMS"))
                                : release;
                String assets =
                        selected.assets().stream()
                                .map(name -> "{\"name\":\"" + name + "\"}")
                                .collect(java.util.stream.Collectors.joining(","));
                byte[] body =
                        ("{\"tag_name\":\""
                                        + selected.tag()
                                        + "\",\"draft\":false,\"prerelease\":false,\"assets\":["
                                        + assets
                                        + "]}")
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } else {
                String name = path.substring(path.lastIndexOf('/') + 1);
                if (!release.assets().contains(name)) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                Path file = directory.resolve(name);
                exchange.sendResponseHeaders(200, Files.size(file));
                Files.copy(file, exchange.getResponseBody());
            }
        }
    }

    @Override
    public void close() {
        server.stop(0);
        workers.shutdownNow();
        if (credentialsSeen) throw new AssertionError("GUI updates sent credentials");
    }

    private ApplicationImage image(Path parent, String version) throws IOException {
        var component = ApplicationImage.Component.GUI;
        String platform = ApplicationImage.currentPlatform();
        var image =
                new ApplicationImage(
                        Files.createDirectories(parent.resolve(component.imageName(platform))),
                        component,
                        platform,
                        ReleaseVersion.release(version),
                        "engine.jar",
                        "gui.jar");
        Files.createDirectories(image.appDirectory().resolve("native"));
        // A real file is needed for directory validation; this fixture never runs its placeholder
        // JVM.
        Files.writeString(image.appDirectory().resolve("native/fixture.txt"), "native placeholder");
        Files.createDirectories(image.runtime().resolve("bin"));
        Files.createDirectories(image.launcher().getParent());
        Files.writeString(image.launcher(), "launcher");
        Files.writeString(
                image.runtime()
                        .resolve("bin/java" + (platform.startsWith("windows") ? ".exe" : "")),
                "java");
        Files.writeString(
                image.appDirectory().resolve(ApplicationImage.MARKER),
                "format=1\ncomponent=GUI\nplatform="
                        + platform
                        + "\nversion="
                        + version
                        + "\nengineJar=engine.jar\nutilJar=util.jar\nmainJar=gui.jar\n");
        jar(
                image.appDirectory().resolve("util.jar"),
                "voxelbrew-util-version.properties",
                "version=" + version);
        jar(
                image.appDirectory().resolve("engine.jar"),
                "voxelbrew-engine-version.properties",
                "version=" + version);
        jar(image.appDirectory().resolve("gui.jar"), "dev/voxelbrew/gui/Launcher.class", "class");
        return image;
    }

    private static void jar(Path path, String name, String contents) throws IOException {
        try (var jar = new JarOutputStream(Files.newOutputStream(path))) {
            jar.putNextEntry(new JarEntry(name));
            jar.write(contents.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jar.closeEntry();
        }
    }
}
