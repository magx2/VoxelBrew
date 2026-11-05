package dev.voxelbrew.util.update;

/** Product semantic versions; a final release sorts after its development snapshot. */
public record ReleaseVersion(int major, int minor, int patch, boolean snapshot)
        implements Comparable<ReleaseVersion> {
    public ReleaseVersion {
        if (major < 0
                || minor < 0
                || patch < 0
                || major >= 65535
                || minor >= 65535
                || patch >= 65535)
            throw new IllegalArgumentException("Version components must be between 0 and 65534");
    }

    public static ReleaseVersion parse(String text) {
        if (text == null) throw new IllegalArgumentException("Expected version A.B.C");
        var match =
                java.util.regex.Pattern.compile(
                                "v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(-SNAPSHOT)?")
                        .matcher(text);
        if (!match.matches())
            throw new IllegalArgumentException("Expected version A.B.C (for example 0.1.0)");
        return new ReleaseVersion(
                Integer.parseInt(match.group(1)),
                Integer.parseInt(match.group(2)),
                Integer.parseInt(match.group(3)),
                match.group(4) != null);
    }

    public static ReleaseVersion release(String text) {
        var version = parse(text);
        if (version.snapshot)
            throw new IllegalArgumentException(
                    "Updates require a published A.B.C release, not a snapshot");
        return version;
    }

    @Override
    public int compareTo(ReleaseVersion other) {
        int value = Integer.compare(major, other.major);
        if (value == 0) value = Integer.compare(minor, other.minor);
        if (value == 0) value = Integer.compare(patch, other.patch);
        return value == 0 ? Boolean.compare(other.snapshot, snapshot) : value;
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch + (snapshot ? "-SNAPSHOT" : "");
    }
}
