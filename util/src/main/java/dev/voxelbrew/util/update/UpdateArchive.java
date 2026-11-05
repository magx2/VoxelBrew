package dev.voxelbrew.util.update;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.*;
import java.util.*;
import java.util.zip.CRC32;
import org.apache.commons.compress.archivers.zip.ZipFile;

final class UpdateArchive {
    static final long MAX_ARCHIVE_BYTES = 1024L * 1024 * 1024;
    private static final long MAX_EXPANDED_BYTES = 4L * 1024 * 1024 * 1024;

    private UpdateArchive() {}

    static void verify(Path archive, Path checksums, String name, UpdateCancellation cancellation)
            throws IOException {
        if (Files.size(archive) == 0
                || Files.size(archive) > MAX_ARCHIVE_BYTES
                || Files.size(checksums) > 1024 * 1024)
            throw new IOException("Invalid release download size");
        var entries = new HashMap<String, String>();
        for (String line : Files.readAllLines(checksums)) {
            var match =
                    java.util.regex.Pattern.compile("([a-fA-F0-9]{64})  ([A-Za-z0-9._-]+)")
                            .matcher(line);
            if (!match.matches() || entries.put(match.group(2), match.group(1)) != null)
                throw new IOException("Invalid or duplicate SHA256SUMS entry");
        }
        if (!entries.containsKey(name)
                || !entries.get(name).equalsIgnoreCase(sha256(archive, cancellation)))
            throw new IOException("Release archive SHA-256 checksum does not match SHA256SUMS");
    }

    static String sha256(Path file, UpdateCancellation cancellation) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[65536];
                for (int read; (read = input.read(buffer)) != -1; ) {
                    cancellation.check();
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    static Path extract(
            Path archive, Path destination, String imageName, UpdateCancellation cancellation)
            throws IOException {
        Files.createDirectory(destination);
        long total = 0;
        int count = 0;
        var names = new HashSet<String>();
        try (var zip = ZipFile.builder().setPath(archive).get()) {
            var entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                cancellation.check();
                var entry = entries.nextElement();
                String name = entry.getName();
                if (++count > 100000
                        || entry.isUnixSymlink()
                        || !zip.canReadEntryData(entry)
                        || (entry.getMethod() != 0 && entry.getMethod() != 8))
                    throw new IOException("Unsupported release archive entry");
                if (!name.startsWith(imageName + "/")
                        || name.contains("\\")
                        || name.contains(":")
                        || name.indexOf('\0') >= 0
                        || name.startsWith("/"))
                    throw new IOException("Unsafe or wrong application archive path: " + name);
                String key = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
                if (!names.add(key.toLowerCase(Locale.ROOT)))
                    throw new IOException("Duplicate release archive path: " + name);
                for (String part : key.split("/", -1)) {
                    if (part.isEmpty()
                            || part.equals(".")
                            || part.equals("..")
                            || part.endsWith(".")
                            || part.endsWith(" ")
                            || part.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?"))
                        throw new IOException("Unsafe release archive path: " + name);
                }
                Path target = destination.resolve(name).normalize();
                if (!target.startsWith(destination))
                    throw new IOException("Archive path escapes staging directory");
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                var crc = new CRC32();
                long size = 0;
                try (var input = zip.getInputStream(entry);
                        var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                    byte[] buffer = new byte[65536];
                    for (int read; (read = input.read(buffer)) != -1; ) {
                        cancellation.check();
                        size += read;
                        total += read;
                        if (total > MAX_EXPANDED_BYTES)
                            throw new IOException("Expanded release archive exceeds size limit");
                        output.write(buffer, 0, read);
                        crc.update(buffer, 0, read);
                    }
                }
                if (size != entry.getSize() || crc.getValue() != entry.getCrc())
                    throw new IOException("Damaged release archive entry: " + name);
                if (Files.getFileStore(target).supportsFileAttributeView("posix")) {
                    var permissions =
                            EnumSet.of(
                                    PosixFilePermission.OWNER_READ,
                                    PosixFilePermission.OWNER_WRITE,
                                    PosixFilePermission.GROUP_READ,
                                    PosixFilePermission.OTHERS_READ);
                    if ((entry.getUnixMode() & 0100) != 0)
                        permissions.add(PosixFilePermission.OWNER_EXECUTE);
                    if ((entry.getUnixMode() & 0010) != 0)
                        permissions.add(PosixFilePermission.GROUP_EXECUTE);
                    if ((entry.getUnixMode() & 0001) != 0)
                        permissions.add(PosixFilePermission.OTHERS_EXECUTE);
                    Files.setPosixFilePermissions(target, permissions);
                }
            }
        }
        return destination.resolve(imageName);
    }
}
