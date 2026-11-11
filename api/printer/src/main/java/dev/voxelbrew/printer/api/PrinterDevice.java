package dev.voxelbrew.printer.api;

import java.net.URI;
import java.util.Objects;

/** A physical device, independent of a slicer preset. Secrets are intentionally redacted. */
public record PrinterDevice(
        String id,
        String name,
        Kind kind,
        String address,
        String serial,
        String model,
        String secret,
        double nozzleMm) {
    public PrinterDevice(
            String id, String name, Kind kind, String address, String serial, String model, String secret) {
        this(id, name, kind, address, serial, model, secret, 0.4);
    }

    public enum Kind {
        MOONRAKER,
        BAMBU
    }

    public PrinterDevice {
        if (!Double.isFinite(nozzleMm) || nozzleMm <= 0 || nozzleMm > 5)
            throw new IllegalArgumentException("Invalid nozzle diameter");
        if (id == null || !id.matches("[a-zA-Z0-9-]{1,64}")) throw new IllegalArgumentException("Invalid device ID");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Device name is required");
        Objects.requireNonNull(kind);
        if (address == null || address.isBlank()) throw new IllegalArgumentException("Device address is required");
        serial = Objects.requireNonNullElse(serial, "").trim();
        model = Objects.requireNonNullElse(model, "").trim();
        secret = Objects.requireNonNullElse(secret, "");
        address = address.trim();
        if (kind == Kind.MOONRAKER) {
            var uri = URI.create(address);
            if (!java.util.Set.of("http", "https").contains(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null)
                throw new IllegalArgumentException("Expected an HTTP(S) server URL without credentials or query");
        } else {
            if (address.contains("/")
                    || address.contains(":")
                    || serial.isBlank()
                    || model.isBlank()
                    || secret.isBlank())
                throw new IllegalArgumentException("Bambu host, serial, model and access code are required");
        }
    }

    @Override
    public String toString() {
        return name;
    }
}
