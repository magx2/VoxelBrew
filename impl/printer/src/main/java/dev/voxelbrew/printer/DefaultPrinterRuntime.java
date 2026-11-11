package dev.voxelbrew.printer;

import dev.voxelbrew.printer.api.*;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

public final class DefaultPrinterRuntime implements PrinterRuntime {
    @Override
    public List<PrinterDevice> devices(Path directory) throws IOException {
        if (!Files.exists(directory)) return List.of();
        var devices = new ArrayList<PrinterDevice>();
        try (var paths = Files.list(directory)) {
            for (var file : paths.filter(p -> p.toString().endsWith(".properties"))
                    .sorted()
                    .toList()) {
                var p = new Properties();
                try (var reader = Files.newBufferedReader(file)) {
                    p.load(reader);
                }
                try {
                    devices.add(new PrinterDevice(
                            p.getProperty("id"),
                            p.getProperty("name"),
                            PrinterDevice.Kind.valueOf(p.getProperty("kind")),
                            p.getProperty("address"),
                            p.getProperty("serial"),
                            p.getProperty("model"),
                            p.getProperty("secret")));
                } catch (RuntimeException failure) {
                    throw new IOException("Invalid device configuration: " + file.getFileName());
                }
            }
        }
        return List.copyOf(devices);
    }

    @Override
    public void save(Path directory, PrinterDevice device) throws IOException {
        Files.createDirectories(directory);
        restrict(directory, true);
        var temporary = Files.createTempFile(directory, ".device-", ".tmp");
        try {
            restrict(temporary, false);
            var p = new Properties();
            p.setProperty("id", device.id());
            p.setProperty("name", device.name());
            p.setProperty("kind", device.kind().name());
            p.setProperty("address", device.address());
            p.setProperty("serial", device.serial());
            p.setProperty("model", device.model());
            p.setProperty("secret", device.secret());
            try (var writer = Files.newBufferedWriter(temporary)) {
                p.store(writer, "VoxelBrew device; contains credentials");
            }
            Files.move(
                    temporary,
                    file(directory, device.id()),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void restrict(Path file, boolean directory) throws IOException {
        var posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        if (posix != null) posix.setPermissions(PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
        else {
            var acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
            if (acl == null) throw new IOException("Cannot restrict device credential permissions");
            var entry = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(Files.getOwner(file))
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class));
            if (directory) entry.setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT);
            acl.setAcl(List.of(entry.build()));
        }
    }

    private static Path file(Path directory, String id) {
        if (id == null || !id.matches("[a-zA-Z0-9-]{1,64}")) throw new IllegalArgumentException("Invalid device ID");
        return directory.resolve(id + ".properties");
    }

    @Override
    public void remove(Path directory, String id) throws IOException {
        Files.deleteIfExists(file(directory, id));
    }

    @Override
    public PrinterSession connect(PrinterDevice device) throws IOException {
        return device.kind() == PrinterDevice.Kind.MOONRAKER ? new MoonrakerSession(device) : new BambuSession(device);
    }
}
