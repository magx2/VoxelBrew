package dev.voxelbrew.util.update;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;

/** A complete jpackage installation. Development classpaths are deliberately not update targets. */
public record ApplicationImage(
        Path root,
        Component component,
        String platform,
        ReleaseVersion version,
        String engineJar,
        String mainJar) {
    public static final String MARKER = "voxelbrew-install.properties";

    public enum Component {
        CLI("VoxelBrewCLI", "dev/voxelbrew/cli/VoxelBrew.class"),
        GUI("VoxelBrew", "dev/voxelbrew/gui/Launcher.class");
        private final String applicationName, entry;

        Component(String applicationName, String entry) {
            this.applicationName = applicationName;
            this.entry = entry;
        }

        public String applicationName() {
            return applicationName;
        }

        public String imageName(String platform) {
            return applicationName + (platform.startsWith("macos-") ? ".app" : "");
        }

        public String asset(ReleaseVersion version, String platform) {
            return applicationName + "-" + version + "-" + platform + ".zip";
        }
    }

    public static String currentPlatform() {
        String arch = System.getProperty("os.arch");
        if (!Set.of("amd64", "x86_64").contains(arch))
            throw new IllegalArgumentException(
                    "Self-update is not qualified for architecture " + arch);
        String os = System.getProperty("os.name");
        if (os.startsWith("Windows")) return "windows-x64";
        if (os.startsWith("Mac")) return "macos-x64";
        if (os.startsWith("Linux")) return "linux-x64";
        throw new IllegalArgumentException("Unsupported update platform: " + os);
    }

    public Path appDirectory() {
        return root.resolve(
                platform.startsWith("windows-")
                        ? "app"
                        : platform.startsWith("macos-") ? "Contents/app" : "lib/app");
    }

    public Path runtime() {
        return root.resolve(
                platform.startsWith("windows-")
                        ? "runtime"
                        : platform.startsWith("macos-")
                                ? "Contents/runtime/Contents/Home"
                                : "lib/runtime");
    }

    public Path launcher() {
        return root.resolve(
                platform.startsWith("windows-")
                        ? component.applicationName + ".exe"
                        : platform.startsWith("macos-")
                                ? "Contents/MacOS/" + component.applicationName
                                : "bin/" + component.applicationName);
    }

    public Path runningLock() {
        return root.resolveSibling("." + root.getFileName() + ".running.lock");
    }

    public Path updateLock() {
        return root.resolveSibling("." + root.getFileName() + ".update.lock");
    }

    /** Format-1 images predating util kept the standalone helper in engine. */
    public String updaterJar() throws IOException {
        return fileName(read(appDirectory().resolve(MARKER)).getProperty("utilJar", engineJar));
    }

    public static Optional<ApplicationImage> detect(Component component) throws IOException {
        try {
            Path jar =
                    Path.of(
                            BuildVersion.class
                                    .getProtectionDomain()
                                    .getCodeSource()
                                    .getLocation()
                                    .toURI());
            if (!Files.isRegularFile(jar)) return Optional.empty();
            Path app = jar.getParent();
            if (!Files.isRegularFile(app.resolve(MARKER))) return Optional.empty();
            String platform = currentPlatform();
            Path root =
                    platform.startsWith("windows-") ? app.getParent() : app.getParent().getParent();
            var image = open(root, component, platform);
            if (!Path.of(System.getProperty("java.home"))
                    .toRealPath()
                    .equals(image.runtime().toRealPath())) return Optional.empty();
            if (!image.version.toString().equals(BuildVersion.current()))
                throw new IOException("Installation/build version mismatch");
            return Optional.of(image);
        } catch (java.net.URISyntaxException failure) {
            throw new IOException("Cannot locate installation", failure);
        }
    }

    public static ApplicationImage open(Path directory, Component component, String platform)
            throws IOException {
        if (!Set.of("windows-x64", "linux-x64", "macos-x64").contains(platform))
            throw new IOException("Unqualified platform: " + platform);
        Path root = directory.toAbsolutePath().normalize();
        if (!root.getFileName().toString().equals(component.imageName(platform))
                || Files.isSymbolicLink(root))
            throw new IOException(
                    "Expected an unlinked " + component.imageName(platform) + " app directory");
        var temporary = new ApplicationImage(root, component, platform, null, "", "");
        Properties p = read(temporary.appDirectory().resolve(MARKER));
        try {
            if (!"1".equals(p.getProperty("format"))
                    || !component.name().equals(p.getProperty("component"))
                    || !platform.equals(p.getProperty("platform")))
                throw new IOException(
                        "Installation identity does not match selected application/platform");
            String engine = fileName(p.getProperty("engineJar")),
                    main = fileName(p.getProperty("mainJar"));
            var result =
                    new ApplicationImage(
                            root,
                            component,
                            platform,
                            ReleaseVersion.parse(p.getProperty("version")),
                            engine,
                            main);
            for (Path file :
                    List.of(
                            result.launcher(),
                            result.runtime()
                                    .resolve(
                                            "bin/java"
                                                    + (platform.startsWith("windows-")
                                                            ? ".exe"
                                                            : "")),
                            result.appDirectory().resolve(engine),
                            result.appDirectory().resolve(main))) {
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                        || !file.toRealPath().startsWith(root.toRealPath()))
                    throw new IOException("Incomplete or linked app image: " + file);
            }
            if (!Files.isDirectory(result.appDirectory().resolve("native")))
                throw new IOException("App image has no native libraries");
            if (p.containsKey("utilJar")) {
                Path utility = result.appDirectory().resolve(fileName(p.getProperty("utilJar")));
                if (!Files.isRegularFile(utility, LinkOption.NOFOLLOW_LINKS)
                        || !utility.toRealPath().startsWith(root.toRealPath()))
                    throw new IOException("Incomplete or linked utility JAR: " + utility);
                try (var jar = new JarFile(utility.toFile())) {
                    var entry = jar.getJarEntry("voxelbrew-util-version.properties");
                    if (entry == null) throw new IOException("Utilities have no build identity");
                    var build = new Properties();
                    try (var in = jar.getInputStream(entry)) {
                        build.load(in);
                    }
                    if (!result.version.toString().equals(build.getProperty("version")))
                        throw new IOException("Utilities/installation version mismatch");
                }
            }
            try (var jar = new JarFile(result.appDirectory().resolve(engine).toFile())) {
                var entry = jar.getJarEntry("voxelbrew-engine-version.properties");
                if (entry == null) throw new IOException("Engine has no build identity");
                var build = new Properties();
                try (var in = jar.getInputStream(entry)) {
                    build.load(in);
                }
                if (!result.version.toString().equals(build.getProperty("version")))
                    throw new IOException("Engine/installation version mismatch");
            }
            try (var jar = new JarFile(result.appDirectory().resolve(main).toFile())) {
                if (jar.getJarEntry(component.entry) == null)
                    throw new IOException("Wrong application entry point");
            }
            return result;
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw new IOException("Invalid installation metadata", failure);
        }
    }

    static Properties read(Path file) throws IOException {
        var p = new Properties();
        try (var input = Files.newInputStream(file)) {
            p.load(input);
        }
        return p;
    }

    private static String fileName(String name) throws IOException {
        if (name == null || !name.matches("[A-Za-z0-9._-]+\\.jar"))
            throw new IOException("Invalid application JAR name");
        return name;
    }

    /** Hold for the entire process lifetime; a directory swap needs the exclusive counterpart. */
    public static Lease running(Component component) throws IOException {
        var image = detect(component);
        if (image.isEmpty()) return new Lease(null, null);
        try {
            return lock(image.get().runningLock(), true);
        } catch (AccessDeniedException readOnly) {
            // Read-only installations remain runnable; updating still requires a writable parent.
            return new Lease(null, null);
        }
    }

    static Lease lock(Path file, boolean shared) throws IOException {
        if (Files.isSymbolicLink(file))
            throw new IOException("Linked installation lock is not allowed");
        var channel =
                FileChannel.open(
                        file,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.READ,
                        StandardOpenOption.WRITE);
        try {
            var lock = channel.tryLock(0, Long.MAX_VALUE, shared);
            if (lock == null)
                throw new IOException(
                        "Installation is busy; close other instances or wait for its update");
            return new Lease(channel, lock);
        } catch (IOException | OverlappingFileLockException failure) {
            channel.close();
            throw new IOException(
                    "Installation is busy; close other instances or wait for its update", failure);
        }
    }

    public static final class Lease implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;

        Lease(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        @Override
        public void close() throws IOException {
            try {
                if (lock != null && lock.isValid()) lock.release();
            } finally {
                if (channel != null) channel.close();
            }
        }
    }
}
