package dev.voxelbrew.gui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.prefs.Preferences;

final class Appearance {
    enum Choice { SYSTEM, LIGHT, DARK }
    private final Preferences preferences=Preferences.userNodeForPackage(Appearance.class);
    Choice load() {try{return Choice.valueOf(preferences.get("appearance","SYSTEM"));}catch(IllegalArgumentException ignored){return Choice.SYSTEM;}}
    void save(Choice choice) {preferences.put("appearance",choice.name());}
    static boolean systemDark() {
        if(System.getProperty("os.name").startsWith("Windows"))return windowsDark("reg.exe");
        String value=command(List.of("gsettings","get","org.gnome.desktop.interface","color-scheme"));
        if(value.contains("prefer-dark"))return true;
        if(value.contains("prefer-light"))return false;
        if(command(List.of("gsettings","get","org.gnome.desktop.interface","gtk-theme")).toLowerCase(Locale.ROOT).contains("dark"))return true;
        if(System.getenv("WSL_DISTRO_NAME")!=null && Files.isRegularFile(Path.of("/mnt/c/Windows/System32/reg.exe")))return windowsDark("/mnt/c/Windows/System32/reg.exe");
        return false;
    }
    private static boolean windowsDark(String executable) {
        String value=command(List.of(executable,"query","HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize","/v","AppsUseLightTheme"));
        return value.matches("(?s).*AppsUseLightTheme\\s+REG_DWORD\\s+0x0\\s*.*");
    }
    private static String command(List<String> args) {
        try {
            var process=new ProcessBuilder(args).redirectErrorStream(true).start();
            if(!process.waitFor(1500,TimeUnit.MILLISECONDS)){process.destroyForcibly();return "";}
            return process.exitValue()==0?new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8):"";
        } catch(IOException failure){return "";}catch(InterruptedException failure){Thread.currentThread().interrupt();return "";}
    }
}
