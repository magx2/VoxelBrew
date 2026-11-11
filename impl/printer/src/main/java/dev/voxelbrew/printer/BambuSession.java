package dev.voxelbrew.printer;

import com.google.gson.*;
import dev.voxelbrew.printer.api.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.function.DoubleConsumer;
import java.util.zip.ZipFile;
import org.apache.commons.net.ftp.*;
import pl.grzeslowski.jbambuapi.mqtt.*;

/** LAN device commands use JBambuAPI; file transfer is a separate FTPS operation. */
final class BambuSession implements PrinterSession {
    private final PrinterDevice device;
    private final PrinterClient client;
    private final PrinterClientConfig config;
    private final JsonObject report = new JsonObject();
    private volatile long lastReport;
    private volatile String commandFailure = "";

    BambuSession(PrinterDevice device) throws IOException {
        this.device = device;
        config = PrinterClientConfig.buildDefault(
                device.address(), device.serial(), device.secret().toCharArray());
        PrinterClient opened = null;
        try {
            opened = new PrinterClient(config);
            opened.subscribe((topic, bytes) -> {
                try {
                    var message = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8))
                            .getAsJsonObject();
                    if (!message.has("print")) return;
                    synchronized (report) {
                        var delta = message.getAsJsonObject("print");
                        delta.entrySet().forEach(e -> report.add(e.getKey(), e.getValue()));
                        if (delta.has("result")
                                && "FAIL".equalsIgnoreCase(delta.get("result").getAsString()))
                            commandFailure = "Printer rejected command; check LAN / Developer Mode and device display";
                        lastReport = System.nanoTime();
                    }
                } catch (JsonParseException | IllegalStateException ignored) {
                    /* Ignore non-status packets. */
                }
            });
            opened.connect();
            client = opened;
            client.getChannel().sendCommand(PrinterClient.Channel.PushingCommand.defaultPushingCommand());
        } catch (Exception failure) {
            if (opened != null) opened.close();
            config.close();
            throw new IOException("Cannot connect to Bambu device; check host, serial and LAN access code");
        }
    }

    @Override
    public PrinterStatus status() throws IOException {
        if (!client.isConnected()) throw new IOException("Bambu device disconnected");
        synchronized (report) {
            if (lastReport == 0
                    || System.nanoTime() - lastReport > Duration.ofSeconds(30).toNanos())
                return PrinterStatus.unknown("Waiting for current printer status");
            String state = string("gcode_state");
            var mapped = switch (state) {
                case "IDLE" -> PrinterStatus.State.IDLE;
                case "RUNNING", "PREPARE" -> PrinterStatus.State.PRINTING;
                case "PAUSE" -> PrinterStatus.State.PAUSED;
                case "FINISH" -> PrinterStatus.State.COMPLETE;
                case "FAILED" -> PrinterStatus.State.ERROR;
                default -> PrinterStatus.State.UNKNOWN;
            };
            var percent = MoonrakerSession.number(report, "mc_percent");
            var minutes = MoonrakerSession.number(report, "mc_remaining_time");
            return new PrinterStatus(
                    mapped,
                    string("gcode_file"),
                    percent.isPresent() ? OptionalDouble.of(percent.getAsDouble() / 100) : percent,
                    MoonrakerSession.number(report, "nozzle_temper"),
                    MoonrakerSession.number(report, "bed_temper"),
                    minutes.isPresent() ? OptionalDouble.of(minutes.getAsDouble() * 60) : minutes,
                    commandFailure.isEmpty() ? state : commandFailure);
        }
    }

    private String string(String key) {
        return report.has(key) && !report.get(key).isJsonNull()
                ? report.get(key).getAsString()
                : "";
    }

    @Override
    public Upload upload(Path artifact, DoubleConsumer progress) throws IOException {
        if (!artifact.toString().endsWith(".gcode.3mf"))
            throw new IOException("Bambu requires a compatible sliced .gcode.3mf package");
        try (var zip = new ZipFile(artifact.toFile())) {
            if (zip.getEntry("Metadata/plate_1.gcode") == null)
                throw new IOException("Package does not contain plate 1 G-code");
        }
        var ftp = new FTPSClient(true);
        long size = Files.size(artifact);
        ftp.setCopyStreamListener(new org.apache.commons.net.io.CopyStreamAdapter() {
            @Override
            public void bytesTransferred(long total, int bytes, long streamSize) {
                progress.accept(size == 0 ? 0 : Math.min(1, (double) total / size));
            }
        });
        ftp.setConnectTimeout(10000);
        ftp.setDefaultTimeout(30000);
        ftp.setDataTimeout(Duration.ofMinutes(2));
        String name = "voxelbrew-" + UUID.randomUUID() + ".gcode.3mf";
        try {
            ftp.connect(device.address(), 990);
            if (!ftp.login("bblp", device.secret())) throw new IOException("Bambu FTPS authentication failed");
            ftp.execPBSZ(0);
            ftp.execPROT("P");
            ftp.enterLocalPassiveMode();
            ftp.setFileType(FTP.BINARY_FILE_TYPE);
            progress.accept(0);
            try (var input = Files.newInputStream(artifact)) {
                if (!ftp.storeFile(name, input)) throw new IOException("Bambu upload failed");
            }
            progress.accept(1);
            return new Upload(name, ArtifactChecks.sha256(artifact));
        } finally {
            if (ftp.isConnected()) ftp.disconnect();
        }
    }

    @Override
    public void start(Upload upload) throws IOException {
        if (!status().canStart()) throw new IOException("Printer is not idle");
        commandFailure = "";
        client.getChannel().sendCommand(new PrinterClient.Channel.RawStringCommand() {
            @Override
            public String topic() {
                return "device/" + device.serial() + "/request";
            }

            @Override
            public String buildRawStringCommand(long sequenceId) {
                var print = new JsonObject();
                print.addProperty("sequence_id", Long.toString(sequenceId));
                print.addProperty("command", "project_file");
                print.addProperty("param", "Metadata/plate_1.gcode");
                print.addProperty("url", "ftp:///" + upload.filename());
                print.addProperty("subtask_name", upload.filename());
                print.addProperty("use_ams", false);
                print.addProperty("bed_leveling", true);
                print.addProperty("flow_cali", false);
                print.addProperty("vibration_cali", true);
                print.addProperty("layer_inspect", false);
                print.addProperty("timelapse", false);
                print.addProperty("profile_id", "0");
                print.addProperty("project_id", "0");
                print.addProperty("task_id", "0");
                print.addProperty("subtask_id", "0");
                var root = new JsonObject();
                root.add("print", print);
                return root.toString();
            }
        });
    }

    @Override
    public void command(Command command) {
        client.getChannel()
                .sendCommand(
                        switch (command) {
                            case PAUSE -> PrinterClient.Channel.PrintCommand.PAUSE;
                            case RESUME -> PrinterClient.Channel.PrintCommand.RESUME;
                            case CANCEL -> PrinterClient.Channel.PrintCommand.STOP;
                        });
    }

    @Override
    public void close() {
        client.close();
        config.close();
    }
}
