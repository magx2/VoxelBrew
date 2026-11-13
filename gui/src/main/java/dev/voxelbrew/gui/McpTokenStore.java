package dev.voxelbrew.gui;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** Credentials are separate from ordinary preferences and restricted before writing. */
final class McpTokenStore {
    private McpTokenStore() {}

    static String load(Path file, java.util.function.Supplier<String> generate) throws IOException {
        if (Files.isSymbolicLink(file)) throw new IOException("MCP credential file must not be a symbolic link");
        if (Files.exists(file)) {
            restrict(file);
            String value = Files.readString(file).strip();
            if (!value.matches("[A-Za-z0-9_-]{43}")) throw new IOException("Invalid MCP credential file");
            return value;
        }
        String token = generate.get();
        save(file, token);
        return token;
    }

    static void save(Path file, String token) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "mcp-token-", ".tmp");
        try {
            restrict(temporary);
            Files.writeString(temporary, token);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void restrict(Path file) throws IOException {
        var posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        if (posix != null) posix.setPermissions(PosixFilePermissions.fromString("rw-------"));
        else {
            var acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
            if (acl == null) throw new IOException("Cannot restrict MCP credential permissions");
            acl.setAcl(List.of(AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(acl.getOwner())
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                    .build()));
        }
    }
}
