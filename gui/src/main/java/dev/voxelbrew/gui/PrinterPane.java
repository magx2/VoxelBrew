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
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
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

    private record ConnectionDraft(String address, String serial, String model, String secret) {}

    private final PrinterRuntime runtime;
    private final dev.voxelbrew.slicer.api.PrinterProfileCatalog profiles;
    private final PrinterVisibility printerVisibility;
    private final Translations text;
    private final Path directory;
    private final ExecutorService worker;
    private final ComboBox<Plate> plates = new ComboBox<>();
    private final ComboBox<Printer> devices = new ComboBox<>();
    private final Label status = new Label(), details = new Label(), message = new Label();
    private final Label connectionDetail = new Label();
    private final Label progressValue = new Label(),
            nozzleValue = new Label(),
            bedValue = new Label(),
            remainingValue = new Label();
    private final ProgressBar transfer = new ProgressBar(0);
    private final VBox monitor = new VBox(16);
    private final Button upload, print, pause, resume, cancel, exportGcode;
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
            dev.voxelbrew.slicer.api.PrinterProfileCatalog profiles,
            PrinterVisibility printerVisibility,
            UserConfiguration configuration,
            Translations text,
            @javax.inject.Named("printer") ExecutorService worker) {
        this.runtime = runtime;
        this.profiles = profiles;
        this.printerVisibility = printerVisibility;
        this.text = text;
        this.worker = worker;
        directory = configuration.path().resolveSibling("devices");
        setId("printer-workspace");
        getStyleClass().add("printer-workspace");
        plates.setId("printer-plate");
        devices.setId("printer-device");
        plates.setMaxWidth(Double.MAX_VALUE);
        devices.setMaxWidth(Double.MAX_VALUE);
        plates.setPromptText(t("platePrompt"));
        devices.setPromptText(t("devicePrompt"));
        plates.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(Plate item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.name());
            }
        });
        var configure = button("configure", this::configure);
        configure.getStyleClass().add("printer-configure");
        upload = button("upload", () -> submit(false));
        exportGcode = button("exportGcode", this::exportGcode);
        print = button("print", () -> submit(true));
        print.getStyleClass().add("printer-primary");
        pause = button("pause", () -> command(PrinterSession.Command.PAUSE));
        resume = button("resume", () -> command(PrinterSession.Command.RESUME));
        cancel = button("cancel", () -> command(PrinterSession.Command.CANCEL));
        cancel.getStyleClass().add("printer-danger");
        status.setId("printer-status");
        details.setId("printer-details");
        message.setId("printer-message");
        message.setWrapText(true);
        details.setWrapText(true);
        message.getStyleClass().add("printer-message");
        details.getStyleClass().add("printer-guidance");
        var heading = new Label(t("workspaceTitle"));
        heading.setId("printer-heading");
        heading.getStyleClass().add("printer-title");
        var subtitle = new Label(t("workspaceSubtitle"));
        subtitle.getStyleClass().add("printer-subtitle");
        var introduction = new VBox(5, heading, subtitle);
        var plateStep = step("01", t("plate"), t("plateHint"), plates);
        var deviceStep = step("02", t("device"), t("deviceHint"), devices);
        var configureRow = new HBox(configure);
        configureRow.setAlignment(Pos.CENTER_RIGHT);
        var setup = new VBox(22, plateStep, deviceStep, configureRow);
        setup.getStyleClass().add("printer-card");
        var sendTitle = new Label(t("sendTitle"));
        sendTitle.getStyleClass().add("printer-section-title");
        var actions = new HBox(10, exportGcode, upload, print);
        actions.setAlignment(Pos.CENTER_LEFT);
        var send = new VBox(12, sendTitle, details, actions, transfer, message);
        send.getStyleClass().add("printer-card");
        var monitorTitle = new Label(t("monitorTitle"));
        monitorTitle.getStyleClass().add("printer-section-title");
        status.getStyleClass().add("printer-state");
        var statusRow = new HBox(12, monitorTitle, status);
        statusRow.setAlignment(Pos.CENTER_LEFT);
        connectionDetail.getStyleClass().add("printer-guidance");
        connectionDetail.setWrapText(true);
        var metrics = new HBox(
                10,
                metric(t("progress"), progressValue),
                metric(t("nozzle"), nozzleValue),
                metric(t("bed"), bedValue),
                metric(t("remaining"), remainingValue));
        metrics.getStyleClass().add("printer-metrics");
        var controls = new HBox(10, pause, resume, cancel);
        monitor.getChildren().addAll(statusRow, connectionDetail, metrics, controls);
        monitor.getStyleClass().add("printer-card");
        var content = new VBox(18, introduction, setup, send, monitor);
        content.getStyleClass().add("printer-content");
        content.setMaxWidth(820);
        var canvas = new StackPane(content);
        canvas.setAlignment(Pos.TOP_CENTER);
        canvas.setPadding(new Insets(32, 28, 48, 28));
        var scroll = new ScrollPane(canvas);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("printer-scroll");
        getChildren().add(scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        transfer.setId("printer-transfer");
        transfer.setMaxWidth(Double.MAX_VALUE);
        transfer.setVisible(false);
        transfer.managedProperty().bind(transfer.visibleProperty());
        message.setVisible(false);
        message.managedProperty().bind(message.visibleProperty());
        message.textProperty().addListener((o, old, value) -> message.setVisible(value != null && !value.isBlank()));
        plates.valueProperty().addListener((o, a, b) -> update());
        devices.valueProperty().addListener((o, a, b) -> connect());
        polling = new Timeline(new KeyFrame(Duration.seconds(2), event -> poll()));
        polling.setCycleCount(Animation.INDEFINITE);
        polling.play();
        reload();
        update();
    }

    QuickMenu quickMenu(
            javafx.scene.Parent theme, java.util.function.Supplier<java.util.List<QuickMenu.Action>> workspaces) {
        return new QuickMenu(
                this,
                theme,
                text,
                () -> new QuickMenu.Content(
                        QuickMenu.controls(
                                this,
                                "printer-configure",
                                "printer-upload",
                                "printer-print",
                                "printer-pause",
                                "printer-resume"),
                        QuickMenu.controls(this, "printer-cancel")),
                workspaces,
                (dx, dy) -> {},
                target -> {
                    for (var node = target; node != null && node != this; node = node.getParent()) {
                        if (node instanceof javafx.scene.control.Control
                                && !(node instanceof Label)
                                && !(node instanceof ScrollPane)) return false;
                    }
                    return true;
                });
    }

    private String t(String key) {
        return text.text("device." + key);
    }

    private Button button(String key, Runnable action) {
        var button = new Button(t(key));
        button.setId("printer-" + key);
        button.setAccessibleHelp(t(key));
        button.setTooltip(new Tooltip(t(key)));
        button.setOnAction(e -> action.run());
        return button;
    }

    private VBox step(String number, String title, String hint, ComboBox<?> selector) {
        var index = new Label(number);
        index.getStyleClass().add("printer-step-number");
        var label = new Label(title);
        label.getStyleClass().add("printer-field-title");
        var description = new Label(hint);
        description.getStyleClass().add("printer-field-hint");
        var heading = new HBox(12, index, new VBox(2, label, description));
        heading.setAlignment(Pos.CENTER_LEFT);
        return new VBox(10, heading, selector);
    }

    private VBox metric(String name, Label value) {
        var label = new Label(name);
        label.getStyleClass().add("printer-metric-label");
        value.getStyleClass().add("printer-metric-value");
        var box = new VBox(5, label, value);
        box.getStyleClass().add("printer-metric");
        HBox.setHgrow(box, Priority.ALWAYS);
        box.setMaxWidth(Double.MAX_VALUE);
        return box;
    }

    void documentChanged(CadDocument value) {
        if (document == null
                || !document.gcode().equals(value.gcode())
                || !document.printArtifacts().equals(value.printArtifacts())) jobGeneration++;
        document = value;
        String selected = plates.getValue() == null
                ? value.printSetup().activePlateId()
                : plates.getValue().id();
        var allPlates = value.printSetup().plates();
        var choices = java.util.stream.IntStream.range(0, allPlates.size())
                .mapToObj(i -> {
                    var plate = allPlates.get(i);
                    return new Plate(
                            plate.id(), plate.name().isBlank() ? t("unnamedPlate") + " " + (i + 1) : plate.name());
                })
                .filter(p -> !value.gcode().getOrDefault(p.id(), List.of()).isEmpty())
                .toList();
        if (!plates.getItems().equals(choices)) {
            plates.getItems().setAll(choices);
        }
        var choice = choices.stream()
                .filter(p -> p.id().equals(selected))
                .findFirst()
                .orElse(choices.isEmpty() ? null : choices.getFirst());
        if (!Objects.equals(plates.getSelectionModel().getSelectedItem(), choice))
            plates.getSelectionModel().select(choice);
        if (choice != null) {
            var artifact = value.printArtifacts().get(choice.id());
            if (artifact != null && "printer".equals(artifact.metadata().get("target_kind"))) {
                var target = artifact.metadata().get("target_id");
                devices.getItems().stream()
                        .filter(d -> d.id().equals(target))
                        .findFirst()
                        .ifPresent(devices::setValue);
            }
        }
        update();
    }

    boolean hasJobs() {
        return !plates.getItems().isEmpty();
    }

    private void update() {
        exportGcode.setDisable(busy || plates.getValue() == null);
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
        monitor.setVisible(devices.getValue() != null);
        monitor.setManaged(monitor.isVisible());
        status.setText(t("state." + current.state().name().toLowerCase(Locale.ROOT)));
        connectionDetail.setText(!current.filename().isBlank() ? current.filename() : current.detail());
        progressValue.setText(number(current.progress(), 100, "%"));
        nozzleValue.setText(number(current.nozzle(), 1, " °C"));
        bedValue.setText(number(current.bed(), 1, " °C"));
        remainingValue.setText(number(current.remainingSeconds(), 1.0 / 60, " min"));
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
                    if (document != null && plates.getValue() != null) {
                        var artifact =
                                document.printArtifacts().get(plates.getValue().id());
                        if (artifact != null
                                && "printer".equals(artifact.metadata().get("target_kind")))
                            selected = artifact.metadata().getOrDefault("target_id", "");
                    }
                    devices.getItems().setAll(loaded);
                    String target = selected;
                    devices.setValue(loaded.stream()
                            .filter(d -> d.id().equals(target))
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

    private void exportGcode() {
        if (document == null || plates.getValue() == null) return;
        var lines = document.gcode().getOrDefault(plates.getValue().id(), List.of());
        if (lines.isEmpty()) return;
        var chooser = new FileChooser();
        chooser.setTitle(t("exportGcode"));
        chooser.setInitialFileName(plates.getValue().name() + ".gcode");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("G-code (*.gcode)", "*.gcode"));
        var chosen = chooser.showSaveDialog(getScene().getWindow());
        if (chosen == null) return;
        var path = chosen.toPath();
        if (Files.exists(path)) {
            var confirmation = new Alert(
                    Alert.AlertType.CONFIRMATION, text.text("file.replace", path), ButtonType.YES, ButtonType.NO);
            if (Dialogs.show(confirmation, getScene().getWindow(), text).orElse(ButtonType.NO) != ButtonType.YES)
                return;
        }
        try {
            Files.writeString(path, String.join("\n", lines) + "\n");
        } catch (java.io.IOException failure) {
            error(failure);
        }
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
        transfer.setProgress(0);
        transfer.setVisible(true);
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
                        transfer.setVisible(false);
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
        var list = new ComboBox<Printer>();
        list.getItems().setAll(devices.getItems());
        list.setPromptText(t("new"));
        var name = new TextField();
        name.setId("device-config-name");
        var address = new TextField();
        var serial = new TextField();
        var model = new TextField();
        var profile = new ComboBox<dev.voxelbrew.slicer.api.PrinterProfileCatalog.Entry>();
        profile.setId("device-config-profile");
        try {
            var hidden = printerVisibility.hidden();
            profile.getItems()
                    .setAll(profiles.load().entries().stream()
                            .filter(entry -> !entry.bundled()
                                    || !hidden.contains(
                                            entry.definition().printer().id()))
                            .toList());
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
        profile.setMaxWidth(Double.MAX_VALUE);
        var secret = new PasswordField();
        var kind = new ComboBox<Printer.Kind>();
        kind.setId("device-config-kind");
        address.setId("device-config-address");
        serial.setId("device-config-serial");
        model.setId("device-config-model");
        secret.setId("device-config-secret");
        kind.getItems().setAll(Printer.Kind.values());
        kind.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(Printer.Kind value) {
                return value == null ? "" : t(value == Printer.Kind.BAMBU ? "kind.bambu" : "kind.moonraker");
            }

            @Override
            public Printer.Kind fromString(String value) {
                return kind.getValue();
            }
        });
        kind.setValue(Printer.Kind.MOONRAKER);
        var drafts = new EnumMap<Printer.Kind, ConnectionDraft>(Printer.Kind.class);
        var loading = new boolean[1];
        list.valueProperty().addListener((o, a, d) -> {
            if (d != null) {
                loading[0] = true;
                try {
                    drafts.clear();
                    kind.setValue(d.kind());
                    name.setText(d.name());
                    address.setText(d.address());
                    serial.setText(d.serial());
                    model.setText(d.model());
                    profile.getItems().stream()
                            .filter(p -> p.definition().printer().id().equals(d.profileId()))
                            .findFirst()
                            .ifPresent(profile::setValue);
                    secret.setText(d.secret());
                } finally {
                    loading[0] = false;
                }
            }
        });
        var add = new Button(t("new"));
        add.setOnAction(e -> {
            loading[0] = true;
            try {
                list.setValue(null);
                drafts.clear();
                kind.setValue(Printer.Kind.MOONRAKER);
                name.clear();
                address.clear();
                serial.clear();
                model.clear();
                profile.setValue(null);
                secret.clear();
            } finally {
                loading[0] = false;
            }
        });
        var form = new VBox(14);
        form.setId("device-config-form");
        form.getStyleClass().add("device-config-form");
        var selection = new HBox(10, list, add);
        selection.getStyleClass().add("device-config-selection");
        HBox.setHgrow(list, Priority.ALWAYS);
        list.setMaxWidth(Double.MAX_VALUE);
        var error = new Label();
        error.setWrapText(true);
        error.getStyleClass().add("device-config-message");
        error.visibleProperty().bind(error.textProperty().isNotEmpty());
        error.managedProperty().bind(error.visibleProperty());
        var addressError = new Label();
        addressError.setId("device-config-address-error");
        addressError.setWrapText(true);
        addressError.getStyleClass().add("device-config-message");
        addressError.visibleProperty().bind(addressError.textProperty().isNotEmpty());
        addressError.managedProperty().bind(addressError.visibleProperty());
        var validateAddress = (Runnable) () -> {
            try {
                new Printer(
                        "preview", "Preview", kind.getValue(), "preview", address.getText(), "serial", "model", "code");
                addressError.setText("");
            } catch (IllegalArgumentException failure) {
                addressError.setText(failure.getMessage());
            }
        };
        address.textProperty().addListener((o, previous, current) -> validateAddress.run());
        var addressLabel = new Label();
        addressLabel.setId("device-config-address-label");
        var modelLabel = new Label();
        modelLabel.setId("device-config-model-label");
        var secretLabel = new Label();
        secretLabel.setId("device-config-secret-label");
        var fields = (java.util.function.Consumer<Printer.Kind>) selected -> {
            boolean bambu = selected == Printer.Kind.BAMBU;
            addressLabel.setText(t(bambu ? "bambuAddress" : "moonrakerAddress"));
            modelLabel.setText(t(bambu ? "bambuModel" : "moonrakerModel"));
            secretLabel.setText(t(bambu ? "bambuSecret" : "moonrakerSecret"));
            var identity =
                    new HBox(12, deviceField(new Label(t("name")), name), deviceField(new Label(t("type")), kind));
            for (var field : identity.getChildren()) HBox.setHgrow(field, Priority.ALWAYS);
            var details = new HBox(12);
            if (bambu) details.getChildren().add(deviceField(new Label(t("serial")), serial));
            details.getChildren().add(deviceField(modelLabel, model));
            for (var field : details.getChildren()) HBox.setHgrow(field, Priority.ALWAYS);
            form.getChildren()
                    .setAll(
                            identity,
                            deviceField(new Label(t("profile")), profile),
                            new VBox(6, deviceField(addressLabel, address), addressError),
                            details,
                            deviceField(secretLabel, secret),
                            error);
        };
        kind.valueProperty().addListener((o, previous, selected) -> {
            if (!loading[0] && previous != null) {
                drafts.put(
                        previous,
                        new ConnectionDraft(address.getText(), serial.getText(), model.getText(), secret.getText()));
                var draft = drafts.get(selected);
                address.setText(draft == null ? "" : draft.address());
                serial.setText(draft == null ? "" : draft.serial());
                model.setText(draft == null ? "" : draft.model());
                secret.setText(draft == null ? "" : draft.secret());
            }
            fields.accept(selected);
            if (!loading[0]) validateAddress.run();
        });
        fields.accept(kind.getValue());
        var save = new ButtonType(t("save"), ButtonBar.ButtonData.OK_DONE);
        var remove = new ButtonType(t("remove"), ButtonBar.ButtonData.OTHER);
        var test = new ButtonType(t("test"), ButtonBar.ButtonData.OTHER);
        dialog.getDialogPane().getButtonTypes().addAll(remove, test, ButtonType.CANCEL, save);
        dialog.getDialogPane().setId("device-config-dialog");
        var content = new VBox(18, selection, new Separator(), form);
        content.setId("device-config-content");
        dialog.getDialogPane().setContent(content);
        var saveButton = (Button) dialog.getDialogPane().lookupButton(save);
        saveButton.setId("device-config-save");
        var removeButton = (Button) dialog.getDialogPane().lookupButton(remove);
        var testButton = (Button) dialog.getDialogPane().lookupButton(test);
        var cancelButton = (Button) dialog.getDialogPane().lookupButton(ButtonType.CANCEL);
        cancelButton.setId("device-config-cancel");
        saveButton.getStyleClass().add("printer-dialog-primary");
        removeButton.getStyleClass().add("device-config-remove");
        removeButton.setDisable(list.getValue() == null);
        list.valueProperty().addListener((o, before, selected) -> removeButton.setDisable(selected == null));
        var actions = List.of(saveButton, removeButton, testButton, cancelButton);
        for (var action : List.of(save, remove, test))
            dialog.getDialogPane().lookupButton(action).addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
                e.consume();
                try {
                    if (action == remove && list.getValue() == null) return;
                    var value = action == remove
                            ? list.getValue()
                            : new Printer(
                                    list.getValue() == null
                                            ? UUID.randomUUID().toString()
                                            : list.getValue().id(),
                                    name.getText(),
                                    kind.getValue(),
                                    java.util.Objects.requireNonNull(profile.getValue(), "Choose a printer profile")
                                            .definition()
                                            .printer()
                                            .id(),
                                    address.getText(),
                                    kind.getValue() == Printer.Kind.BAMBU ? serial.getText() : "",
                                    model.getText(),
                                    secret.getText());
                    content.setDisable(true);
                    actions.forEach(button -> button.setDisable(true));
                    worker.submit(() -> {
                        try {
                            if (action == test) {
                                try (var connection = runtime.connect(value)) {
                                    connection.status();
                                }
                            } else if (action == remove) runtime.remove(directory, value.id());
                            else runtime.save(directory, value);
                            Platform.runLater(() -> {
                                content.setDisable(false);
                                actions.forEach(button -> button.setDisable(false));
                                removeButton.setDisable(list.getValue() == null);
                                if (action == test) {
                                    error.getStyleClass().add("device-config-success");
                                    error.setText(t("tested"));
                                } else {
                                    reload();
                                    cancelButton.fire();
                                }
                            });
                        } catch (Exception failure) {
                            Platform.runLater(() -> {
                                content.setDisable(false);
                                actions.forEach(button -> button.setDisable(false));
                                removeButton.setDisable(list.getValue() == null);
                                error.getStyleClass().remove("device-config-success");
                                error.setText(failure.getMessage());
                            });
                        }
                    });
                } catch (IllegalArgumentException failure) {
                    error.getStyleClass().remove("device-config-success");
                    error.setText(failure.getMessage());
                }
            });
        Dialogs.show(dialog, getScene().getWindow(), text);
    }

    private static VBox deviceField(Label label, Control input) {
        label.getStyleClass().add("device-config-label");
        input.setMaxWidth(Double.MAX_VALUE);
        var field = new VBox(6, label, input);
        field.getStyleClass().add("device-config-field");
        field.setMaxWidth(Double.MAX_VALUE);
        return field;
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
