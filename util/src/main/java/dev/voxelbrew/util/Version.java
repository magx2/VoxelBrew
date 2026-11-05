package dev.voxelbrew.util;

/** Application version embedded by Gradle, or {@code 0.0.0} for IDE/class-directory runs. */
public final class Version {
    private Version() {}

    public static String version() {
        String version = Version.class.getPackage().getImplementationVersion();
        return version == null ? "0.0.0" : version;
    }
}
