package dev.voxelbrew.util.update;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.jar.*;
import org.apache.commons.compress.archivers.zip.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class UpdateTest {
    @TempDir Path directory;
    private final UpdateCancellation cancellation = new UpdateCancellation();
    private final ReleaseVersion current = ReleaseVersion.release("1.2.3");

    @Test
    void declaredFileIoModulesMustExistAndMatchTheInstallationVersion() throws Exception {
        for (String module : List.of("import", "export", "import-api", "export-api")) {
            var image = image(directory.resolve(module), current);
            Path marker = image.appDirectory().resolve(ApplicationImage.MARKER);
            Files.writeString(
                    marker, Files.readString(marker) + module + "Jar=" + module + ".jar\n");
            assertThrows(
                    IOException.class,
                    () -> ApplicationImage.open(image.root(), image.component(), image.platform()));
            Path artifact = image.appDirectory().resolve(module + ".jar");
            jar(artifact, "voxelbrew-" + module + "-version.properties", "version=9.9.9");
            assertThrows(
                    IOException.class,
                    () -> ApplicationImage.open(image.root(), image.component(), image.platform()));
            jar(artifact, "voxelbrew-" + module + "-version.properties", "version=" + current);
            assertEquals(
                    current,
                    ApplicationImage.open(image.root(), image.component(), image.platform())
                            .version());
        }
    }

    @Test
    void utilityIdentityIsRequiredWhenDeclaredAndLegacyMetadataStillOpens() throws Exception {
        var image = image(directory.resolve("installation"), current);
        assertEquals("util.jar", image.updaterJar());
        Path utility = image.appDirectory().resolve("util.jar");
        Files.delete(utility);
        assertThrows(
                IOException.class,
                () -> ApplicationImage.open(image.root(), image.component(), image.platform()));
        jar(utility, "voxelbrew-util-version.properties", "version=9.9.9");
        assertThrows(
                IOException.class,
                () -> ApplicationImage.open(image.root(), image.component(), image.platform()));
        Path marker = image.appDirectory().resolve(ApplicationImage.MARKER);
        Files.writeString(marker, Files.readString(marker).replace("utilJar=util.jar\n", ""));
        assertEquals(
                "engine.jar",
                ApplicationImage.open(image.root(), image.component(), image.platform())
                        .updaterJar());
    }

    @Test
    void versionsAndReleaseSelection() throws Exception {
        assertTrue(
                ReleaseVersion.release("1.10.0").compareTo(ReleaseVersion.release("1.9.99")) > 0);
        assertTrue(ReleaseVersion.parse("1.2.3-SNAPSHOT").compareTo(current) < 0);
        for (String invalid : List.of("01.2.3", "1.2", "1.2.3-rc1", "65535.0.0", "1.2.3/path"))
            assertThrows(IllegalArgumentException.class, () -> ReleaseVersion.parse(invalid));
        assertThrows(
                IllegalArgumentException.class, () -> ReleaseVersion.release("1.2.3-SNAPSHOT"));
        assertFalse(check("1.2.3", null, false).available());
        assertTrue(check("1.2.3", null, true).available());
        assertFalse(check("1.0.0", null, true).available());
        assertTrue(check("1.0.0", "1.0.0", false).available());
        assertTrue(check("1.3.0", null, false).available());
        assertFalse(check("1.3.0", null, false).installable());
    }

    @Test
    void rejectsUnsafeReleaseMetadataAndMissingAssets() throws Exception {
        assertEquals(
                current,
                GitHubReleaseSource.parse(
                                metadata("v1.2.3", "false", "false", "SHA256SUMS"), current)
                        .version());
        for (String metadata :
                List.of(
                        metadata("v1.2.3", "true", "false"),
                        metadata("v1.2.3", "false", "true"),
                        metadata("v1.2.3", "false", "false", "a", "a"),
                        metadata("v1.2.3", "false", "false", "../escape"),
                        metadata("1.2.3", "false", "false"),
                        metadata("v1.3.0", "false", "false"),
                        metadata("v1.2.3", "\"false\"", "false"),
                        "null",
                        "{}",
                        "{broken",
                        "[]",
                        metadata("v1.2.3", "false", "false") + " {}"))
            assertThrows(IOException.class, () -> GitHubReleaseSource.parse(metadata, current));
        var missing = new ReleaseSource.Release(current, Set.of("SHA256SUMS"));
        assertThrows(
                IOException.class,
                () ->
                        service(missing, Optional.empty())
                                .check(new UpdateService.Request(null, true), cancellation));
        var noChecksum =
                new ReleaseSource.Release(
                        current,
                        Set.of(ApplicationImage.Component.CLI.asset(current, "linux-x64")));
        assertThrows(
                IOException.class,
                () ->
                        service(noChecksum, Optional.empty())
                                .check(new UpdateService.Request(null, true), cancellation));
    }

    @Test
    void verifiesChecksumsAndCancelsWithoutAcceptingPartialData() throws Exception {
        Path archive = Files.writeString(directory.resolve("app.zip"), "original");
        Path checksums =
                Files.writeString(
                        directory.resolve("SHA256SUMS"),
                        UpdateArchive.sha256(archive, cancellation) + "  app.zip\n");
        UpdateArchive.verify(archive, checksums, "app.zip", cancellation);
        Files.writeString(archive, "corrupt");
        assertThrows(
                IOException.class,
                () -> UpdateArchive.verify(archive, checksums, "app.zip", cancellation));
        Files.writeString(
                checksums, "0".repeat(64) + "  app.zip\n" + "0".repeat(64) + "  app.zip\n");
        assertThrows(
                IOException.class,
                () -> UpdateArchive.verify(archive, checksums, "app.zip", cancellation));
        cancellation.cancel();
        assertThrows(
                CancellationException.class, () -> UpdateArchive.sha256(archive, cancellation));
    }

    @Test
    void extractionRejectsTraversalLinksAndCaseCollisions() throws Exception {
        int attempt = 0;
        for (String path :
                List.of(
                        "../escape",
                        "VoxelBrewCLI/../escape",
                        "/VoxelBrewCLI/root",
                        "Wrong/file",
                        "VoxelBrewCLI/a\\b",
                        "VoxelBrewCLI/a:b",
                        "VoxelBrewCLI/CON.txt",
                        "VoxelBrewCLI/a./file")) {
            Path archive = zip("bad" + attempt + ".zip", List.of(path), 0100644);
            Path destination = directory.resolve("bad" + attempt++);
            assertThrows(
                    IOException.class,
                    () -> UpdateArchive.extract(archive, destination, "VoxelBrewCLI", cancellation),
                    path);
        }
        Path links = zip("links.zip", List.of("VoxelBrewCLI/link"), 0120777);
        assertThrows(
                IOException.class,
                () ->
                        UpdateArchive.extract(
                                links, directory.resolve("links"), "VoxelBrewCLI", cancellation));
        Path duplicates =
                zip("duplicates.zip", List.of("VoxelBrewCLI/Name", "VoxelBrewCLI/name"), 0100644);
        assertThrows(
                IOException.class,
                () ->
                        UpdateArchive.extract(
                                duplicates,
                                directory.resolve("duplicates"),
                                "VoxelBrewCLI",
                                cancellation));
        assertFalse(Files.exists(directory.resolve("escape")));
    }

    @Test
    void extractionPreservesOnlySafeExecutablePermissions() throws Exception {
        Path archive = zip("good.zip", List.of("VoxelBrewCLI/bin/VoxelBrewCLI"), 0107777);
        Path result =
                UpdateArchive.extract(
                        archive, directory.resolve("good"), "VoxelBrewCLI", cancellation);
        assertEquals("data", Files.readString(result.resolve("bin/VoxelBrewCLI")));
        if (Files.getFileStore(result).supportsFileAttributeView("posix")) {
            assertTrue(Files.isExecutable(result.resolve("bin/VoxelBrewCLI")));
            assertFalse(
                    Files.getPosixFilePermissions(result.resolve("bin/VoxelBrewCLI"))
                            .contains(java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE));
        }
    }

    @Test
    void failedReplacementRestoresOriginalAndNeverOverwritesBackup() throws Exception {
        Path target = Files.createDirectory(directory.resolve("installation"));
        Path source = Files.createDirectory(directory.resolve("replacement"));
        Path backup = directory.resolve("backup");
        Files.writeString(target.resolve("version"), "old");
        Files.writeString(source.resolve("version"), "new");
        var failure =
                assertThrows(
                        IOException.class,
                        () ->
                                UpdateHelper.replace(
                                        target,
                                        source,
                                        backup,
                                        (from, to) -> {
                                            if (from.equals(source))
                                                throw new IOException("simulated locked directory");
                                            Files.move(from, to);
                                        }));
        assertTrue(failure.getMessage().contains("restored"));
        assertEquals("old", Files.readString(target.resolve("version")));
        UpdateHelper.replace(target, source, backup, (from, to) -> Files.move(from, to));
        assertEquals("new", Files.readString(target.resolve("version")));
        assertEquals("old", Files.readString(backup.resolve("version")));
        assertThrows(
                IOException.class,
                () ->
                        UpdateHelper.replace(
                                target, source, backup, (from, to) -> fail("must not move")));
    }

    @SuppressWarnings("try")
    @Test
    void busyInstallationAndFailedDownloadPreserveOriginal() throws Exception {
        var image = image(directory.resolve("installed with spaces"), current);
        var release = release("1.3.0");
        var service = service(release, Optional.of(image));
        var selected = service.check(new UpdateService.Request(null, false), cancellation);
        try (var lock = ApplicationImage.lock(image.updateLock(), false)) {
            assertThrows(
                    IOException.class,
                    () -> service.prepare(selected, cancellation, ignored -> {}));
        }
        assertThrows(
                IOException.class, () -> service.prepare(selected, cancellation, ignored -> {}));
        assertEquals(
                current,
                ApplicationImage.open(image.root(), ApplicationImage.Component.CLI, "linux-x64")
                        .version());
        try (var children = Files.list(image.root().getParent())) {
            assertTrue(
                    children.noneMatch(
                            path ->
                                    path.getFileName()
                                            .toString()
                                            .startsWith(".VoxelBrew-update-")));
        }
        assertThrows(
                IOException.class,
                () ->
                        service(release, Optional.empty())
                                .prepare(selected, cancellation, ignored -> {}));
    }

    private UpdateService.Check check(String release, String requested, boolean force)
            throws IOException {
        return service(release(release), Optional.empty())
                .check(
                        new UpdateService.Request(
                                requested == null ? null : ReleaseVersion.release(requested),
                                force),
                        cancellation);
    }

    private static String metadata(String tag, String draft, String prerelease, String... assets) {
        return "{\"tag_name\":\""
                + tag
                + "\",\"draft\":"
                + draft
                + ",\"prerelease\":"
                + prerelease
                + ",\"assets\":["
                + Arrays.stream(assets)
                        .map(name -> "{\"name\":\"" + name + "\"}")
                        .collect(java.util.stream.Collectors.joining(","))
                + "]}";
    }

    @Test
    void cancelledStageCleanupHandlesReadOnlyFilesAndRejectsOtherDirectories() throws Exception {
        Path stage = Files.createTempDirectory(directory, ".VoxelBrew-update-");
        Path file = Files.writeString(stage.resolve("runtime.exe"), "staged file");
        var dos =
                Files.getFileAttributeView(
                        file,
                        java.nio.file.attribute.DosFileAttributeView.class,
                        LinkOption.NOFOLLOW_LINKS);
        if (dos != null) dos.setReadOnly(true);
        UpdateFiles.deleteStage(stage);
        assertFalse(Files.exists(stage));
        Path unrelated = Files.createDirectory(directory.resolve("installation"));
        assertThrows(IOException.class, () -> UpdateFiles.deleteStage(unrelated));
        assertTrue(Files.isDirectory(unrelated));
    }

    private ReleaseSource.Release release(String value) {
        var version = ReleaseVersion.release(value);
        return new ReleaseSource.Release(
                version,
                Set.of("SHA256SUMS", ApplicationImage.Component.CLI.asset(version, "linux-x64")));
    }

    private UpdateService service(
            ReleaseSource.Release release, Optional<ApplicationImage> installation) {
        return new UpdateService(
                ApplicationImage.Component.CLI,
                current,
                "linux-x64",
                new ReleaseSource() {
                    @Override
                    public Release fetch(ReleaseVersion requested, UpdateCancellation token) {
                        return release;
                    }

                    @Override
                    public void download(
                            Release selected,
                            String asset,
                            Path destination,
                            UpdateCancellation token)
                            throws IOException {
                        Files.writeString(destination, "interrupted download");
                        throw new IOException("fixture network failure");
                    }
                },
                installation);
    }

    private Path zip(String file, List<String> paths, int mode) throws IOException {
        Path archive = directory.resolve(file);
        try (var zip = new ZipArchiveOutputStream(archive)) {
            for (String path : paths) {
                var entry = new ZipArchiveEntry(path);
                entry.setUnixMode(mode);
                zip.putArchiveEntry(entry);
                zip.write("data".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeArchiveEntry();
            }
        }
        return archive;
    }

    private ApplicationImage image(Path parent, ReleaseVersion version) throws IOException {
        Path root = Files.createDirectories(parent.resolve("VoxelBrewCLI"));
        var image =
                new ApplicationImage(
                        root,
                        ApplicationImage.Component.CLI,
                        "linux-x64",
                        version,
                        "engine.jar",
                        "cli.jar");
        Files.createDirectories(image.appDirectory().resolve("native"));
        Files.createDirectories(image.runtime().resolve("bin"));
        Files.createDirectories(image.launcher().getParent());
        Files.writeString(image.launcher(), "launcher");
        Files.writeString(image.runtime().resolve("bin/java"), "java");
        Files.writeString(
                image.appDirectory().resolve(ApplicationImage.MARKER),
                "format=1\ncomponent=CLI\nplatform=linux-x64\nversion="
                        + version
                        + "\nengineJar=engine.jar\nutilJar=util.jar\nmainJar=cli.jar\n");
        jar(
                image.appDirectory().resolve("util.jar"),
                "voxelbrew-util-version.properties",
                "version=" + version);
        jar(
                image.appDirectory().resolve("engine.jar"),
                "voxelbrew-engine-version.properties",
                "version=" + version);
        jar(image.appDirectory().resolve("cli.jar"), "dev/voxelbrew/cli/VoxelBrew.class", "class");
        return ApplicationImage.open(root, ApplicationImage.Component.CLI, "linux-x64");
    }

    private static void jar(Path path, String entry, String content) throws IOException {
        try (var jar = new JarOutputStream(Files.newOutputStream(path))) {
            jar.putNextEntry(new JarEntry(entry));
            jar.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jar.closeEntry();
        }
    }
}
