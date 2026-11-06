package dev.voxelbrew.gui;

import dev.voxelbrew.util.config.UserConfiguration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.prefs.Preferences;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class Appearance {
    enum Choice {
        SYSTEM,
        LIGHT,
        DARK
    }

    private final UserConfiguration configuration;
    private final java.util.function.Supplier<String> legacyAppearance;

    Appearance() {
        this(
                UserConfiguration.user(),
                () -> Preferences.userNodeForPackage(Appearance.class).get("appearance", null));
    }

    @javax.inject.Inject
    Appearance(UserConfiguration configuration) {
        this(
                configuration,
                () -> Preferences.userNodeForPackage(Appearance.class).get("appearance", null));
    }

    Choice load() {
        try {
            String value = configuration.get("appearance", null);
            if (value == null) {
                String legacy = legacyAppearance.get();
                if (legacy != null) {
                    Choice.valueOf(legacy);
                    configuration.setIfAbsent("appearance", legacy);
                    value = configuration.get("appearance", legacy);
                }
            }
            return Choice.valueOf(value == null ? "SYSTEM" : value);
        } catch (IllegalArgumentException ignored) {
            return Choice.SYSTEM;
        } catch (IOException | SecurityException failure) {
            log.warn("Cannot load appearance preference", failure);
            return Choice.SYSTEM;
        }
    }

    void save(Choice choice) {
        try {
            configuration.set("appearance", choice.name());
        } catch (IOException failure) {
            log.warn("Cannot save appearance preference", failure);
        }
    }

    static boolean systemDark() {
        if (System.getProperty("os.name").startsWith("Windows")) return windowsDark("reg.exe");
        String value = command(List.of("gsettings", "get", "org.gnome.desktop.interface", "color-scheme"));
        if (value.contains("prefer-dark")) return true;
        if (value.contains("prefer-light")) return false;
        if (command(List.of("gsettings", "get", "org.gnome.desktop.interface", "gtk-theme"))
                .toLowerCase(Locale.ROOT)
                .contains("dark")) return true;
        if (System.getenv("WSL_DISTRO_NAME") != null && Files.isRegularFile(Path.of("/mnt/c/Windows/System32/reg.exe")))
            return windowsDark("/mnt/c/Windows/System32/reg.exe");
        return false;
    }

    private static boolean windowsDark(String executable) {
        String value = command(List.of(
                executable,
                "query",
                "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                "/v",
                "AppsUseLightTheme"));
        return value.matches("(?s).*AppsUseLightTheme\\s+REG_DWORD\\s+0x0\\s*.*");
    }

    private static String command(List<String> args) {
        try {
            var process = new ProcessBuilder(args).redirectErrorStream(true).start();
            if (!process.waitFor(1500, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return "";
            }
            return process.exitValue() == 0
                    ? new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                    : "";
        } catch (IOException failure) {
            return "";
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return "";
        }
    }
}
