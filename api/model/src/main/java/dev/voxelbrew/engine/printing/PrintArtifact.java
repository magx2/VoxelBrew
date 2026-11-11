package dev.voxelbrew.engine.printing;

import java.security.*;
import java.util.*;

/** Immutable, portable bytes and provenance for a sliced device job. */
public record PrintArtifact(String format, String base64, String sha256, Map<String, String> metadata) {
    public PrintArtifact {
        if (!Set.of("gcode", "gcode.3mf").contains(format))
            throw new IllegalArgumentException("Unsupported print artifact format");
        if (base64 == null || base64.length() > 64 * 1024 * 1024)
            throw new IllegalArgumentException("Print artifact exceeds 48 MiB");
        byte[] bytes = Base64.getDecoder().decode(base64);
        if (bytes.length == 0 || !hash(bytes).equals(sha256))
            throw new IllegalArgumentException("Invalid print artifact checksum");
        metadata = Map.copyOf(metadata);
    }

    public static PrintArtifact of(String format, byte[] bytes, Map<String, String> metadata) {
        return new PrintArtifact(format, Base64.getEncoder().encodeToString(bytes), hash(bytes), metadata);
    }

    public byte[] bytes() {
        return Base64.getDecoder().decode(base64);
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public String toString() {
        return "PrintArtifact[" + format + ", " + sha256 + "]";
    }
}
