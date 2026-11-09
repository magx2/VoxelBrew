package dev.voxelbrew.importer;

import dev.voxelbrew.engine.printing.GcodeJob;
import dev.voxelbrew.engine.printing.GcodeJob.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/** Independent motion parser: layers come from deposition, not comment counts. */
public final class GcodeReader {
    private GcodeReader() {}

    private static final Pattern PARAMETER =
            Pattern.compile("(?:^|\\s)([XYZEF])([-+]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][-+]?[0-9]+)?)");

    public static GcodeJob read(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            return read(input);
        }
    }

    /** Reads UTF-8 toolpaths; closing a provider's borrowed stream remains under host control. */
    public static GcodeJob read(InputStream input) throws IOException {
        var segments = new ArrayList<Segment>();
        var layers = new TreeSet<Double>();
        var report = new LinkedHashMap<String, String>();
        OptionalDouble filament = OptionalDouble.empty();
        Optional<String> time = Optional.empty();
        double x = 0, y = 0, z = 0, e = 0, width = 0.45, height = 0.2;
        boolean absolute = true, absoluteE = true;
        Role role = Role.UNKNOWN;
        try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith(";TYPE:")) role = role(line.substring(6).strip());
                else if (line.startsWith(";WIDTH:")) width = number(line.substring(7));
                else if (line.startsWith(";HEIGHT:")) height = number(line.substring(8));
                else if (line.startsWith("; filament used [mm] = "))
                    filament = OptionalDouble.of(number(line.substring(line.indexOf('=') + 1)));
                else if (line.startsWith("; estimated printing time (normal mode) = "))
                    time = Optional.of(line.substring(line.indexOf('=') + 1).strip());
                else if (line.startsWith("; VOXELBREW ")) {
                    int equals = line.indexOf('=', 12);
                    if (equals > 0)
                        report.put(
                                line.substring(12, equals).strip(),
                                line.substring(equals + 1).strip());
                }
                int comment = line.indexOf(';');
                String command = (comment < 0 ? line : line.substring(0, comment)).strip();
                if (command.isEmpty()) continue;
                String code = command.split("\\s+", 2)[0];
                if (code.equals("G20"))
                    throw new IOException("Inch G-code is unsupported; printer job must use millimeters");
                if (code.equals("G90")) {
                    absolute = true;
                    continue;
                }
                if (code.equals("G91")) {
                    absolute = false;
                    continue;
                }
                if (code.equals("M82")) {
                    absoluteE = true;
                    continue;
                }
                if (code.equals("M83")) {
                    absoluteE = false;
                    continue;
                }
                if (code.equals("G2") || code.equals("G3"))
                    throw new IOException("Arc G-code is unsupported by this job inspector");
                if (!code.equals("G0") && !code.equals("G1") && !code.equals("G92")) continue;
                var values = new HashMap<String, Double>();
                var matcher = PARAMETER.matcher(command);
                while (matcher.find()) values.put(matcher.group(1), number(matcher.group(2)));
                if (code.equals("G92")) {
                    x = values.getOrDefault("X", x);
                    y = values.getOrDefault("Y", y);
                    z = values.getOrDefault("Z", z);
                    e = values.getOrDefault("E", e);
                    continue;
                }
                double nx = values.containsKey("X") ? values.get("X") + (absolute ? 0 : x) : x;
                double ny = values.containsKey("Y") ? values.get("Y") + (absolute ? 0 : y) : y;
                double nz = values.containsKey("Z") ? values.get("Z") + (absolute ? 0 : z) : z;
                double ne = values.containsKey("E") ? values.get("E") + (absoluteE ? 0 : e) : e;
                double extrusion = ne - e;
                if (Math.hypot(nx - x, ny - y) > 1e-8) {
                    var segment = new Segment(
                            x, y, z, nx, ny, nz, extrusion, width, height, extrusion > 1e-8 ? role : Role.TRAVEL);
                    segments.add(segment);
                    if (segment.deposits() && segment.role().model()) layers.add(Math.rint(nz * 1e6) / 1e6);
                }
                x = nx;
                y = ny;
                z = nz;
                e = ne;
                if (segments.size() > 5_000_000) throw new IOException("Job exceeds preview limit of 5 million moves");
            }
        } catch (NumberFormatException failure) {
            throw new IOException("Invalid numeric G-code", failure);
        }
        if (segments.stream().noneMatch(Segment::deposits))
            throw new IOException("Slicer output contains no deposition moves");
        return new GcodeJob(segments, new ArrayList<>(layers), filament, time, report);
    }

    private static double number(String text) {
        double number = Double.parseDouble(text.strip());
        if (!Double.isFinite(number)) throw new NumberFormatException("Nonfinite G-code value");
        return number;
    }

    private static Role role(String value) {
        return switch (value) {
            case "Perimeter", "WALL-INNER" -> Role.PERIMETER;
            case "External perimeter", "WALL-OUTER" -> Role.EXTERNAL_PERIMETER;
            case "Internal infill", "FILL" -> Role.INFILL;
            case "Solid infill", "Top solid infill", "SKIN" -> Role.SOLID_INFILL;
            case "Bridge infill", "Overhang perimeter" -> Role.BRIDGE;
            case "Gap fill" -> Role.GAP_FILL;
            case "Support material", "SUPPORT" -> Role.SUPPORT;
            case "Support material interface", "SUPPORT-INTERFACE" -> Role.INTERFACE;
            case "Skirt/Brim", "SKIRT" -> Role.SKIRT_BRIM;
            case "Ironing", "IRONING" -> Role.IRONING;
            default -> Role.UNKNOWN;
        };
    }
}
