package dev.voxelbrew.gui;

import dev.voxelbrew.util.config.UserConfiguration;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;

/** Window-local recent documents, persisted separately from document contents. */
@Slf4j
final class RecentFiles {
    private static final int LIMIT = 10;
    private static final String PREFIX = "recent.file.";
    private final UserConfiguration configuration;
    private final List<Path> paths = new ArrayList<>();

    @Inject
    RecentFiles(UserConfiguration configuration) {
        this.configuration = configuration;
        try {
            var values = configuration.read();
            for (int i = 0; i < LIMIT; i++) {
                String value = values.getOrDefault(PREFIX + i, "");
                if (value.isBlank()) continue;
                try {
                    Path path = Path.of(value).toAbsolutePath().normalize();
                    if (!paths.contains(path)) paths.add(path);
                } catch (InvalidPathException invalid) {
                    log.warn("Ignoring invalid recent file path", invalid);
                }
            }
        } catch (IOException | SecurityException failure) {
            log.warn("Cannot load recent files", failure);
        }
    }

    synchronized List<Path> paths() {
        return List.copyOf(paths);
    }

    synchronized void remember(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        paths.remove(normalized);
        paths.addFirst(normalized);
        if (paths.size() > LIMIT) paths.removeLast();
    }

    void save() {
        var snapshot = paths();
        var values = new LinkedHashMap<String, String>();
        for (int i = 0; i < LIMIT; i++)
            values.put(PREFIX + i, i < snapshot.size() ? snapshot.get(i).toString() : "");
        try {
            configuration.update(values, java.util.Set.of());
        } catch (IOException | SecurityException failure) {
            log.warn("Cannot save recent files", failure);
        }
    }
}
