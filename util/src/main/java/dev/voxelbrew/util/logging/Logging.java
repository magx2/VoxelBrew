package dev.voxelbrew.util.logging;

import dev.voxelbrew.util.config.ApplicationPaths;
import dev.voxelbrew.util.config.UserConfiguration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.logging.*;

/** Configures only VoxelBrew's logger hierarchy; never resets a host application's logging. */
public final class Logging {
    private static final Logger APPLICATION = Logger.getLogger("dev.voxelbrew");
    private static boolean initialized;

    private Logging() {}

    public static synchronized void initialize(String component) {
        if (initialized) return;
        try {
            configure(UserConfiguration.user(), ApplicationPaths.logs(), component);
        } catch (IOException | IllegalArgumentException failure) {
            APPLICATION.log(
                    Level.WARNING,
                    "Cannot configure file logging; using console diagnostics",
                    failure);
        }
        initialized = true;
    }

    public static synchronized void configure(
            UserConfiguration configuration, Path directory, String component) throws IOException {
        if (!component.matches("[a-zA-Z0-9_-]+"))
            throw new IllegalArgumentException("Invalid logging component");
        Map<String, String> options = configuration.read();
        Level level =
                Level.parse(options.getOrDefault("logging.level", "INFO").toUpperCase(Locale.ROOT));
        Level consoleLevel =
                Level.parse(
                        options.getOrDefault("logging.console.level", "WARNING")
                                .toUpperCase(Locale.ROOT));
        int bytes = Integer.parseInt(options.getOrDefault("logging.file.limit", "1048576"));
        int count = Integer.parseInt(options.getOrDefault("logging.file.count", "3"));
        if (bytes < 1024 || count < 1 || count > 20)
            throw new IllegalArgumentException("Invalid log rotation limits");
        Files.createDirectories(directory);
        // Escape FileHandler's pattern tokens in a user-supplied directory (including %).
        String pattern =
                directory.toAbsolutePath().toString().replace("%", "%%")
                        + java.io.File.separator
                        + component
                        + "-%u-%g.log";
        var file = new FileHandler(pattern, bytes, count, true);
        file.setEncoding("UTF-8");
        file.setFormatter(new SimpleFormatter());
        file.setLevel(level);
        var console = new ConsoleHandler();
        console.setLevel(consoleLevel);
        for (Handler handler : APPLICATION.getHandlers()) {
            APPLICATION.removeHandler(handler);
            handler.close();
        }
        APPLICATION.setUseParentHandlers(false);
        APPLICATION.setLevel(level);
        APPLICATION.addHandler(file);
        APPLICATION.addHandler(console);
    }

    public static synchronized void close() {
        for (Handler handler : APPLICATION.getHandlers()) {
            APPLICATION.removeHandler(handler);
            handler.close();
        }
        APPLICATION.setUseParentHandlers(true);
        APPLICATION.setLevel(null);
        initialized = false;
    }
}
