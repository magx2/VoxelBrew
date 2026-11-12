package dev.voxelbrew.printer.api;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;

/** A physical device, independent of a slicer preset. Secrets are intentionally redacted. */
public record Printer(
        String id,
        String name,
        Kind kind,
        String profileId,
        String address,
        String serial,
        String model,
        String secret) {

    public enum Kind {
        MOONRAKER,
        BAMBU
    }

    public Printer {
        if (id == null || !id.matches("[a-zA-Z0-9-]{1,64}")) throw new IllegalArgumentException("Invalid device ID");
        if (name == null || name.isBlank() || name.strip().length() > 128)
            throw new IllegalArgumentException("Printer name must contain 1–128 characters");
        name = name.strip();
        if (name.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Printer name cannot contain control characters");
        Objects.requireNonNull(kind);
        if (profileId == null || !profileId.matches("[a-zA-Z0-9.-]{1,128}"))
            throw new IllegalArgumentException("Printer profile ID is required");
        if (address == null || address.isBlank()) throw new IllegalArgumentException("Device address is required");
        serial = Objects.requireNonNullElse(serial, "").trim();
        model = Objects.requireNonNullElse(model, "").trim();
        secret = Objects.requireNonNullElse(secret, "");
        address = address.trim();
        if (kind == Kind.MOONRAKER) {
            URI uri;
            try {
                uri = new URI(address);
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException("Enter a valid Moonraker HTTP(S) server URL", e);
            }
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null
                    || uri.getHost().isBlank()
                    || uri.getPort() == 0
                    || uri.getPort() > 65535
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null
                    || (uri.getRawAuthority() != null && uri.getRawAuthority().endsWith(":")))
                throw new IllegalArgumentException(
                        "Enter a valid Moonraker HTTP(S) server URL without credentials or query");
        } else {
            if (!validHost(address))
                throw new IllegalArgumentException("Enter a valid printer IP address or host name");
            if (serial.isBlank() || model.isBlank() || secret.isBlank())
                throw new IllegalArgumentException("Bambu host, serial, model and access code are required");
        }
    }

    private static boolean validHost(String address) {
        if (address.length() > 253 || !address.matches("[A-Za-z0-9.-]+")) return false;
        if (address.matches("[0-9.]+")) {
            var parts = address.split("\\.", -1);
            if (parts.length != 4) return false;
            for (var part : parts) {
                if (part.isEmpty() || part.length() > 3) return false;
                if (Integer.parseInt(part) > 255) return false;
            }
            return true;
        }
        for (var label : address.split("\\.", -1)) {
            if (label.isEmpty() || label.length() > 63 || !label.matches("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?"))
                return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return name;
    }
}
