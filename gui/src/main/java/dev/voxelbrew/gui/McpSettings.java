package dev.voxelbrew.gui;

import dev.voxelbrew.util.config.UserConfiguration;
import java.io.IOException;
import java.util.Map;
import java.util.Set;

/** Persistent, CAD-independent preferences for the embedded listener. */
record McpSettings(boolean autoStart, int port, int maxConnections, int idleTimeoutMinutes, int activityHistorySize) {
    McpSettings {
        if (port != 0 && (port < 1024 || port > 65535))
            throw new IllegalArgumentException("Port must be 0 or 1024–65535");
        if (maxConnections < 1 || maxConnections > 16) throw new IllegalArgumentException("Connections must be 1–16");
        if (idleTimeoutMinutes < 1 || idleTimeoutMinutes > 120)
            throw new IllegalArgumentException("Idle timeout must be 1–120 minutes");
        if (activityHistorySize < 10 || activityHistorySize > 1000)
            throw new IllegalArgumentException("History size must be 10–1000");
    }

    static McpSettings load(UserConfiguration store) throws IOException {
        var values = store.read();
        String auto = values.getOrDefault("mcp.autoStart", "false");
        if (!auto.equals("true") && !auto.equals("false")) throw new IllegalArgumentException("Invalid mcp.autoStart");
        return new McpSettings(
                Boolean.parseBoolean(auto),
                number(values, "port", 0),
                number(values, "maxConnections", 4),
                number(values, "idleTimeoutMinutes", 30),
                number(values, "activityHistorySize", 100));
    }

    private static int number(Map<String, String> values, String key, int fallback) {
        return Integer.parseInt(values.getOrDefault("mcp." + key, Integer.toString(fallback)));
    }

    void save(UserConfiguration store) throws IOException {
        store.update(
                Map.of(
                        "mcp.autoStart",
                        Boolean.toString(autoStart),
                        "mcp.port",
                        Integer.toString(port),
                        "mcp.maxConnections",
                        Integer.toString(maxConnections),
                        "mcp.idleTimeoutMinutes",
                        Integer.toString(idleTimeoutMinutes),
                        "mcp.activityHistorySize",
                        Integer.toString(activityHistorySize)),
                Set.of());
    }
}
