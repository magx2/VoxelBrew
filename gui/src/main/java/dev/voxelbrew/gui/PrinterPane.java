package dev.voxelbrew.gui;

import dev.voxelbrew.engine.model.CadDocument;
import dev.voxelbrew.printer.api.*;
import dev.voxelbrew.util.config.UserConfiguration;
import dev.voxelbrew.util.i18n.Translations;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javafx.animation.*;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.Duration;

/** Device workspace. All transport work is serialized away from JavaFX. */
@javax.inject.Singleton
final class PrinterPane extends VBox implements AutoCloseable {
    private record Plate(String id, String name) {
        @Override
        public String toString() {
            return name;
        }
    }

    private final PrinterRuntime runtime;
    private final Translations text;
    private final Path directory;
    private final ExecutorService worker;
    private final ComboBox<Plate> plates = new ComboBox<>();
    private final ComboBox<PrinterDevice> devices = new ComboBox<>();
    private final Label status = new Label(), details = new Label(), message = new Label();
    private final ProgressBar transfer = new ProgressBar(0);
    private final Button upload, print, pause, resume, cancel;
    private final Timeline polling;
    private CadDocument document;
    private PrinterSession session;
    private PrinterStatus current = PrinterStatus.unknown("");
    private long generation;
    private boolean busy;
    private volatile boolean closed;
    private boolean startPending;
    private volatile long jobGeneration;

    @javax.inject.Inject
    PrinterPane(
            PrinterRuntime runtime,
            UserConfiguration configuration,
            Translations text,
            @javax.inject.Named("printer") ExecutorService worker) {
        this.runtime = runtime;
        this.text = text;
        this.worker = worker;
        directory = configuration.path().resolveSibling("devices");
        setSpacing(12);
        setPadding(new javafx.geometry.Insets(20));
        setId("printer-workspace");
        plates.setId("printer-plate");
        devices.setId("printer-device");
        plates.setMaxWidth(Double.MAX_VALUE);
        devices.setMaxWidth(Double.MAX_VALUE);
        var configure = button("configure", this::configure);
        upload = button("upload", () -> submit(false));
        print = button("print", () -> submit(true));
        pause = button("pause", () -> command(PrinterSession.Command.PAUSE));
        resume = button("resume", () -> command(PrinterSession.Command.RESUME));
        cancel = button("cancel", () -> command(PrinterSession.Command.CANCEL));
        status.setId("printer-status");
        details.setId("printer-details");
        message.setId("printer-message");
        message.setWrapText(true);
        details.setWrapText(true);
        getChildren()
                .addAll(
                        new Label(t("plate")),
                        plates,
                        new Label(t("device")),
                        devices,
                        configure,
                        details,
                        new HBox(10, upload, print),
                        transfer,
                        status,
                        new HBox(10, pause, resume, cancel),
                        message);
        transfer.setId("printer-transfer");
        transfer.setMaxWidth(Double.MAX_VALUE);
        plates.valueProperty().addListener((o, a, b) -> update());
        devices.valueProperty().addListener((o, a, b) -> connect());
        polling = new Timeline(new KeyFrame(Duration.seconds(2), event -> poll()));
        polling.setCycleCount(Animation.INDEFINITE);
        polling.play();
        reload();
        update();
    }

    private String t(String key) {
        return text.text("device." + key);
    }

    private Button button(String key, Runnable action) {
        var button = new Button(t(key));
        button.setId("printer-" + key);
        button.setOnAction(e -> action.run());
        return button;
    }

    void documentChanged(CadDocument value) {
        if (document == null
                || !document.gcode().equals(value.gcode())
                || !document.printArtifacts().equals(value.printArtifacts())) jobGeneration++;
        document = value;
        String selected = plates.getValue() == null
                ? value.printSetup().activePlateId()
                : plates.getValue().id();
        var choices = value.printSetup().plates().stream()
                .filter(p -> !value.gcode().getOrDefault(p.id(), List.of()).isEmpty())
                .map(p -> new Plate(p.id(), p.name()))
                .toList();
        if (!plates.getItems().equals(choices)) {
            plates.getItems().setAll(choices);
            plates.setValue(choices.stream()
                    .filter(p -> p.id().equals(selected))
                    .findFirst()
                    .orElse(choices.isEmpty() ? null : choices.getFirst()));
        }
        update();
    }

    boolean hasJobs() {
        return !plates.getItems().isEmpty();
    }

    private void update() {
        boolean selected = plates.getValue() != null && devices.getValue() != null;
        var incompatible = selected && document != null
                ? PrinterJobs.incompatibility(document, plates.getValue().id(), devices.getValue())
                : Optional.<String>empty();
        boolean compatible = selected && incompatible.isEmpty();
        upload.setDisable(busy || !compatible || session == null);
        print.setDisable(upload.isDisable() || !current.canStart() || startPending);
        pause.setDisable(busy || session == null || current.state() != PrinterStatus.State.PRINTING);
        resume.setDisable(busy || session == null || current.state() != PrinterStatus.State.PAUSED);
        cancel.setDisable(busy
                || session == null
                || (current.state() != PrinterStatus.State.PRINTING && current.state() != PrinterStatus.State.PAUSED));
        plates.setDisable(busy);
        devices.setDisable(busy);
        details.setText(!selected ? t("choose") : compatible ? t("ready") : incompatible.orElse(t("bambuPackage")));
        status.setText(current.state() + "  " + current.filename() + "\n" + t("progress") + ": "
                + number(current.progress(), 100, "%")
                + "   " + t("nozzle") + ": " + number(current.nozzle(), 1, " °C") + "   " + t("bed") + ": "
                + number(current.bed(), 1, " °C")
                + "   " + t("remaining") + ": " + number(current.remainingSeconds(), 1.0 / 60, " min") + "\n"
                + current.detail());
    }

    private String number(OptionalDouble value, double scale, String unit) {
        return value.isPresent()
                ? String.format(Locale.ROOT, "%.1f%s", value.getAsDouble() * scale, unit)
                : t("unknown");
    }

    private void reload() {
        worker.submit(() -> {
            try {
                var loaded = runtime.devices(directory);
                Platform.runLater(() -> {
                    if (closed) return;
                    String selected =
                            devices.getValue() == null ? "" : devices.getValue().id();
                    devices.getItems().setAll(loaded);
                    devices.setValue(loaded.stream()
                            .filter(d -> d.id().equals(selected))
                            .findFirst()
                            .orElse(loaded.isEmpty() ? null : loaded.getFirst()));
                });
            } catch (Exception failure) {
                error(failure);
            }
        });
    }

    private void connect() {
        busy = false;
        startPending = false;
        long token = ++generation;
        var device = devices.getValue();
        var previous = session;
        session = null;
        current = PrinterStatus.unknown(t("connecting"));
        update();
        worker.submit(() -> {
            if (previous != null) previous.close();
            if (device == null) return;
            try {
                var connected = runtime.connect(device);
                PrinterStatus initial;
                try {
                    initial = connected.status();
                } catch (Exception failure) {
                    connected.close();
                    throw failure;
                }
                Platform.runLater(() -> {
                    if (closed || token != generation) {
                        Thread.ofVirtual().start(connected::close);
                        return;
                    }
                    session = connected;
                    current = initial;
                    update();
                });
            } catch (Exception failure) {
                Platform.runLater(() -> {
                    if (!closed && token == generation) {
                        current = PrinterStatus.unknown(t("offline"));
                        message.setText(failure.getMessage());
                        update();
                    }
                });
            }
        });
    }

    private void poll() {
        if (closed || busy || session == null) return;
        var connected = session;
        long token = generation;
        busy = true;
        worker.submit(() -> {
            PrinterStatus next;
            try {
                next = connected.status();
            } catch (Exception failure) {
                next = PrinterStatus.unknown(t("offline"));
            }
            var result = next;
            Platform.runLater(() -> {
                if (!closed && token == generation) {
                    busy = false;
                    current = result;
                    if (current.state() == PrinterStatus.State.PRINTING
                            || current.state() == PrinterStatus.State.PAUSED) startPending = false;
                    update();
                }
            });
        });
    }

    private void submit(boolean start) {
        if (busy || session == null || document == null || plates.getValue() == null) return;
        if (start && (startPending || !current.canStart())) return;
        var lines = document.gcode().getOrDefault(plates.getValue().id(), List.of());
        if (lines.isEmpty()) return;
        if (devices.getValue() == null
                || PrinterJobs.incompatibility(document, plates.getValue().id(), devices.getValue())
                        .isPresent()) return;
        var artifact = document.printArtifacts().get(plates.getValue().id());
        long revision = jobGeneration;
        var connected = session;
        busy = true;
        message.setText(t("uploading"));
        update();
        worker.submit(() -> {
            Path temporary = null;
            try {
                temporary =
                        Files.createTempFile("voxelbrew-print-", artifact == null ? ".gcode" : "." + artifact.format());
                if (artifact == null) Files.writeString(temporary, String.join("\n", lines));
                else Files.write(temporary, artifact.bytes());
                var receipt = connected.upload(
                        temporary,
                        fraction -> Platform.runLater(() -> {
                            if (!closed) transfer.setProgress(fraction);
                        }));
                if (revision != jobGeneration || closed)
                    throw new java.io.IOException("Job changed during upload; print was not started");
                if (start) {
                    Platform.runLater(() -> {
                        if (!closed) {
                            startPending = true;
                            update();
                        }
                    });
                    connected.start(receipt);
                }
                Platform.runLater(() -> {
                    if (!closed) message.setText(t(start ? "submitted" : "uploaded"));
                });
            } catch (Exception failure) {
                error(failure);
            } finally {
                if (temporary != null)
                    try {
                        Files.deleteIfExists(temporary);
                    } catch (java.io.IOException ignored) {
                    }
                Platform.runLater(() -> {
                    if (!closed) {
                        busy = false;
                        update();
                        poll();
                    }
                });
            }
        });
    }

    private void command(PrinterSession.Command command) {
        if (busy || session == null) return;
        var connected = session;
        busy = true;
        update();
        worker.submit(() -> {
            try {
                connected.command(command);
            } catch (Exception failure) {
                error(failure);
            } finally {
                Platform.runLater(() -> {
                    if (!closed) {
                        busy = false;
                        update();
                        poll();
                    }
                });
            }
        });
    }

    private void error(Exception failure) {
        Platform.runLater(() -> {
            if (!closed) message.setText(failure.getMessage());
        });
    }

    void configure() {
        var dialog = new Dialog<ButtonType>();
        dialog.setTitle(t("configure"));
        if (getScene() != null) dialog.initOwner(getScene().getWindow());
        var list = new ComboBox<PrinterDevice>();
        list.getItems().setAll(devices.getItems());
        list.setPromptText(t("new"));
        var name = new TextField();
        var address = new TextField();
        var serial = new TextField();
        var model = new TextField();
        var secret = new PasswordField();
        var nozzle = new TextField("0.4");
        var kind = new ComboBox<PrinterDevice.Kind>();
        kind.getItems().setAll(PrinterDevice.Kind.values());
        kind.setValue(PrinterDevice.Kind.MOONRAKER);
        list.valueProperty().addListener((o, a, d) -> {
            if (d != null) {
                name.setText(d.name());
                address.setText(d.address());
                serial.setText(d.serial());
                model.setText(d.model());
                secret.setText(d.secret());
                kind.setValue(d.kind());
                nozzle.setText(Double.toString(d.nozzleMm()));
            }
        });
        var add = new Button(t("new"));
        add.setOnAction(e -> {
            list.setValue(null);
            name.clear();
            address.clear();
            serial.clear();
            model.clear();
            secret.clear();
        });
        var grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        grid.addRow(0, list, add);
        grid.addRow(1, new Label(t("name")), name);
        grid.addRow(2, new Label(t("type")), kind);
        grid.addRow(3, new Label(t("address")), address);
        grid.addRow(4, new Label(t("serial")), serial);
        grid.addRow(5, new Label(t("model")), model);
        grid.addRow(6, new Label(t("secret")), secret);
        grid.addRow(7, new Label(t("nozzle") + " (mm)"), nozzle);
        var error = new Label();
        error.setWrapText(true);
        grid.add(error, 0, 8, 2, 1);
        var save = new ButtonType(t("save"), ButtonBar.ButtonData.OK_DONE);
        var remove = new ButtonType(t("remove"), ButtonBar.ButtonData.OTHER);
        var test = new ButtonType(t("test"), ButtonBar.ButtonData.OTHER);
        dialog.getDialogPane().getButtonTypes().addAll(save, remove, test, ButtonType.CANCEL);
        dialog.getDialogPane().setContent(grid);
        for (var action : List.of(save, remove, test))
            dialog.getDialogPane().lookupButton(action).addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
                e.consume();
                try {
                    if (action == remove && list.getValue() == null) return;
                    var value = action == remove
                            ? list.getValue()
                            : new PrinterDevice(
                                    list.getValue() == null
                                            ? UUID.randomUUID().toString()
                                            : list.getValue().id(),
                                    name.getText(),
                                    kind.getValue(),
                                    address.getText(),
                                    serial.getText(),
                                    model.getText(),
                                    secret.getText(),
                                    Double.parseDouble(nozzle.getText()));
                    dialog.getDialogPane().setDisable(true);
                    worker.submit(() -> {
                        try {
                            if (action == test) {
                                try (var connection = runtime.connect(value)) {
                                    connection.status();
                                }
                            } else if (action == remove) runtime.remove(directory, value.id());
                            else runtime.save(directory, value);
                            Platform.runLater(() -> {
                                dialog.getDialogPane().setDisable(false);
                                if (action == test) error.setText(t("tested"));
                                else {
                                    dialog.close();
                                    reload();
                                }
                            });
                        } catch (Exception failure) {
                            Platform.runLater(() -> {
                                dialog.getDialogPane().setDisable(false);
                                error.setText(failure.getMessage());
                            });
                        }
                    });
                } catch (IllegalArgumentException failure) {
                    error.setText(failure.getMessage());
                }
            });
        dialog.showAndWait();
    }

    @Override
    public void close() {
        closed = true;
        generation++;
        jobGeneration++;
        polling.stop();
        var connected = session;
        worker.submit(() -> {
            if (connected != null) connected.close();
        });
    }
}
