package dev.voxelbrew.engine.parameters;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Runtime metadata shared across document edits; save count advances only after publication. */
public final class BuiltinParameters {
    /** Built-in metadata is text except for the successful-save counter. */
    public static boolean isString(String name) {
        return name.startsWith("vb.") && !name.equals("vb.project.version");
    }

    private long fileVersion;
    private Path projectPath;

    /** File location is runtime context, never embedded into portable project definitions. */
    public synchronized void projectPath(Path path) {
        projectPath = path == null ? null : path.toAbsolutePath().normalize();
    }

    private synchronized String projectLocation(String property) {
        if (projectPath == null) return property.equals("name") ? "Untitled" : "";
        String filename = projectPath.getFileName().toString();
        return switch (property) {
            case "path" -> projectPath.toString();
            case "folder" ->
                projectPath.getParent() == null ? "" : projectPath.getParent().toString();
            case "filename" -> filename;
            case "name" -> {
                int dot = filename.lastIndexOf('.');
                yield dot > 0 ? filename.substring(0, dot) : filename;
            }
            default -> throw new IllegalArgumentException("Unknown project property: " + property);
        };
    }

    public synchronized long fileVersion() {
        return fileVersion;
    }

    public synchronized void fileVersion(long value) {
        if (value < 0) throw new IllegalArgumentException("Negative file version");
        fileVersion = value;
    }

    public Object value(String name) {
        if (name.equals("vb.project.version")) return ParameterUnit.NUMBER.quantity(fileVersion());
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        return switch (name) {
            case "vb.project.name" -> projectLocation("name");
            case "vb.project.path" -> projectLocation("path");
            case "vb.project.folder" -> projectLocation("folder");
            case "vb.project.filename" -> projectLocation("filename");
            case "vb.version" -> dev.voxelbrew.util.Version.version();
            case "vb.date" -> now.toLocalDate().toString();
            case "vb.time" -> now.format(DateTimeFormatter.ISO_OFFSET_TIME);
            case "vb.date_time" -> now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            case "vb.os.name" -> System.getProperty("os.name", "unknown");
            case "vb.os.version" -> System.getProperty("os.version", "unknown");
            case "vb.os.arch" -> System.getProperty("os.arch", "unknown");
            default -> throw new IllegalArgumentException("Unknown built-in parameter: " + name);
        };
    }

    public List<ParameterSet.Parameter> parameters() {
        return List.of(
                row("version", "Running application version"),
                row("project.version", "Successful save count; unsaved and legacy documents start at zero"),
                row("project.name", "Project filename without its extension; Untitled before first save"),
                row("project.path", "Absolute project file path; empty before first save"),
                row("project.folder", "Absolute project folder; empty before first save"),
                row("project.filename", "Project filename including its extension; empty before first save"),
                row("date", "Current ISO date in UTC"),
                row("date_time", "Current ISO date and time in UTC"),
                row("time", "Current ISO time in UTC"),
                row("os.name", "Operating system name"),
                row("os.version", "Operating system version"),
                row("os.arch", "Operating system architecture"));
    }

    private ParameterSet.Parameter row(String suffix, String comment) {
        String name = "vb." + suffix;
        String value = Expressions.format(value(name));
        String expression = isString(name) ? "=\"" + value.replace("\"", "\"\"") + "\"" : value;
        return new ParameterSet.Parameter(name, name, ParameterUnit.NUMBER, expression, comment + " (read-only)");
    }
}
