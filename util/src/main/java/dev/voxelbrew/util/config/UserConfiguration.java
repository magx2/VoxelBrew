package dev.voxelbrew.util.config;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** UTF-8 properties with cross-process read/merge/write locking and atomic publication. */
@lombok.extern.slf4j.Slf4j
public final class UserConfiguration {
    private static final Object[] LOCKS = new Object[32];

    static {
        Arrays.setAll(LOCKS, ignored -> new Object());
    }

    private final Path file;

    public UserConfiguration(Path file) {
        this.file = Objects.requireNonNull(file).toAbsolutePath().normalize();
        if (this.file.getParent() == null) throw new IllegalArgumentException("Expected a file path");
    }

    public static UserConfiguration user() {
        return new UserConfiguration(ApplicationPaths.configuration());
    }

    public Path path() {
        return file;
    }

    public Map<String, String> read() throws IOException {
        Properties properties = load();
        var result = new TreeMap<String, String>();
        properties.stringPropertyNames().forEach(key -> result.put(key, properties.getProperty(key)));
        return Collections.unmodifiableMap(result);
    }

    public String get(String key, String fallback) throws IOException {
        return read().getOrDefault(key, fallback);
    }

    public void set(String key, String value) throws IOException {
        update(Map.of(key, value), Set.of());
    }

    public void remove(String key) throws IOException {
        update(Map.of(), Set.of(key));
    }

    public void update(Map<String, String> values, Set<String> removed) throws IOException {
        log.debug("Updating configuration {} ({} keys set, {} removed)", file, values.size(), removed.size());
        var snapshot = Map.copyOf(values);
        var removals = Set.copyOf(removed);
        snapshot.keySet().forEach(UserConfiguration::checkKey);
        removals.forEach(UserConfiguration::checkKey);
        change(properties -> {
            removals.forEach(properties::remove);
            properties.putAll(snapshot);
        });
    }

    /** Used by migrations so an already configured value always wins. */
    public void setIfAbsent(String key, String value) throws IOException {
        checkKey(key);
        Objects.requireNonNull(value);
        change(properties -> properties.putIfAbsent(key, value));
    }

    private static void checkKey(String key) {
        if (key.isBlank()) throw new IllegalArgumentException("Configuration key cannot be blank");
    }

    private Properties load() throws IOException {
        var properties = new Properties();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (NoSuchFileException missing) {
            // The default configuration is empty until a preference is changed.
        } catch (IllegalArgumentException malformed) {
            throw new IOException("Malformed configuration: " + file, malformed);
        }
        return properties;
    }

    @SuppressWarnings("try")
    private void change(java.util.function.Consumer<Properties> operation) throws IOException {
        synchronized (LOCKS[Math.floorMod(file.hashCode(), LOCKS.length)]) {
            Files.createDirectories(file.getParent());
            Path lockFile = file.resolveSibling(file.getFileName() + ".lock");
            try (var channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    var lock = channel.lock()) {
                var properties = load();
                operation.accept(properties);
                var text = new StringWriter();
                properties.store(text, "VoxelBrew user configuration");
                Path temporary = Files.createTempFile(file.getParent(), ".config-", ".tmp");
                try {
                    try (var output = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                        var bytes = ByteBuffer.wrap(text.toString().getBytes(StandardCharsets.UTF_8));
                        while (bytes.hasRemaining()) output.write(bytes);
                        output.force(true);
                    }
                    Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    Files.deleteIfExists(temporary);
                }
            }
        }
    }
}
