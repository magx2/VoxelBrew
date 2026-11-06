package dev.voxelbrew.util.config;

import java.nio.file.Path;

/** User-local application files, independent of documents and installation directories. */
public final class ApplicationPaths {
    private ApplicationPaths() {}

    public static Path home() {
        String override = System.getProperty("voxelbrew.home");
        return (override == null ? Path.of(System.getProperty("user.home"), ".voxelbrew") : Path.of(override))
                .toAbsolutePath()
                .normalize();
    }

    public static Path configuration() {
        String override = System.getProperty("voxelbrew.config");
        return override == null
                ? home().resolve("config.properties")
                : Path.of(override).toAbsolutePath().normalize();
    }

    public static Path plugins() {
        return home().resolve("plugins");
    }

    public static Path logs() {
        return home().resolve("log");
    }
}
