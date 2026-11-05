package dev.voxelbrew.gui;

import dev.voxelbrew.engine.update.*;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.*;
import org.apache.commons.compress.archivers.zip.*;

/** A small offline release for exercising the real dialog up to the unsaved-document guard. */
final class UpdateFixture implements ReleaseSource {
    private final Path directory;
    private final ApplicationImage installed;
    private final Release release;
    volatile boolean slow;
    volatile int requests;
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
        release = new Release(replacement.version(), Set.of(asset, "SHA256SUMS"));
    }

    UpdateService service() {
        return new UpdateService(
                ApplicationImage.Component.GUI,
                installed.version(),
                installed.platform(),
                this,
                Optional.of(installed));
    }

    @Override
    public Release fetch(ReleaseVersion version, UpdateCancellation cancellation)
            throws IOException {
        requests++;
        requested = version;
        while (slow) {
            cancellation.check();
            try {
                Thread.sleep(25);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                cancellation.check();
            }
        }
        cancellation.check();
        return release;
    }

    @Override
    public void download(
            Release selected, String asset, Path destination, UpdateCancellation cancellation)
            throws IOException {
        cancellation.check();
        Files.copy(directory.resolve(asset), destination);
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
                        + "\nengineJar=engine.jar\nmainJar=gui.jar\n");
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
