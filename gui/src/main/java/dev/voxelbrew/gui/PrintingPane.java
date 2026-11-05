package dev.voxelbrew.gui;

import dev.voxelbrew.engine.export.*;
import dev.voxelbrew.engine.mesh.*;
import dev.voxelbrew.engine.persistence.DocumentCodec;
import dev.voxelbrew.engine.printing.*;
import dev.voxelbrew.exporter.ExporterRegistry;
import dev.voxelbrew.exporter.printing.PrusaSlicerBackend;
import dev.voxelbrew.exporter.printing.SliceService;
import dev.voxelbrew.exporter.printing.SlicerRegistry;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.util.StringConverter;

/** Build placement and actual toolpath preview, sharing all validation and slicing with CLI. */
@javax.inject.Singleton
final class PrintingPane extends BorderPane implements AutoCloseable {
    private final DocumentController document;
    private final PrintController jobs;
    private final SlicerRegistry slicers;
    private final ExecutorService validator;
    private Future<?> validationTask;
    private long validationSequence;
    private final Canvas canvas = new Canvas();
    private final ComboBox<String> bodies = new ComboBox<>(),
            instances = new ComboBox<>(),
            materials = new ComboBox<>(),
            supportIds = new ComboBox<>(),
            supportOwners = new ComboBox<>();
    private final ComboBox<SlicerBackend.Descriptor> backends = new ComboBox<>();
    private final ComboBox<SupportVolume.Role> supportRole = new ComboBox<>();
    private final Map<String, TextField> fields = new LinkedHashMap<>();
    private final CheckBox autoSupports = new CheckBox("Automatic supports"),
            bedOnly = new CheckBox("Build plate only"),
            overwrite = new CheckBox("Replace existing job");
    private final Slider layer = new Slider(0, 0, 0);
    private final Label layerLabel = new Label("No sliced layers"),
            validationLabel = new Label("Add a build instance"),
            status = new Label("Ready to slice"),
            summary = new Label();
    private final TextArea diagnostics = new TextArea();
    private final EnumMap<GcodeJob.Role, CheckBox> roles = new EnumMap<>(GcodeJob.Role.class);
    private final Button slice;
    private final Button cancel;
    private final ProgressIndicator progress = new ProgressIndicator();
    private ManufacturingModel model;
    private PrintValidation.Result validation;
    private PrintSetup lastSetup;
    private DocumentController.Build lastBuild;
    private String validatedRevision = "";
    private boolean updating, dark, closed;
    private List<Double> previewLayers = List.of();
    private SliceService.Result displayed;

    @javax.inject.Inject
    PrintingPane(
            DocumentController document,
            PrintController jobs,
            SlicerRegistry slicers,
            ExporterRegistry exporters,
            javafx.stage.Stage owner,
            @javax.inject.Named("validation") ExecutorService validator) {
        this.document = document;
        this.jobs = jobs;
        this.slicers = slicers;
        this.validator = validator;
        setId("printing-workspace");
        setPadding(new Insets(8));
        bodies.setId("print-body");
        instances.setId("print-instance");
        materials.setId("print-material");
        backends.setId("print-backend");
        supportIds.setId("print-support-id");
        supportOwners.setId("print-support-instance");
        supportRole.setId("print-support-role");
        autoSupports.setId("print-auto-supports");
        bedOnly.setId("print-bed-only");
        overwrite.setId("print-overwrite");
        materials.getItems().setAll("PLA", "PETG");
        supportRole.getItems().setAll(SupportVolume.Role.values());
        supportRole.setValue(SupportVolume.Role.SOLID_SUPPORT);
        backends.getItems().setAll(slicers.descriptors());
        backends.setConverter(
                new StringConverter<>() {
                    @Override
                    public String toString(SlicerBackend.Descriptor descriptor) {
                        return descriptor == null
                                ? ""
                                : descriptor.name() + " " + descriptor.version();
                    }

                    @Override
                    public SlicerBackend.Descriptor fromString(String text) {
                        throw new UnsupportedOperationException();
                    }
                });
        var placement =
                new VBox(
                        7,
                        new Label("CAD body"),
                        bodies,
                        button("Add selected body", "print-add", this::addInstance),
                        new Label("Build instance"),
                        instances);
        for (var name : List.of("x", "y", "z", "rotate-x", "rotate-y", "rotate-z"))
            placement
                    .getChildren()
                    .add(
                            row(
                                    "print-" + name,
                                    name.startsWith("rotate")
                                            ? name + " (degrees)"
                                            : name + " (mm)",
                                    "0"));
        placement
                .getChildren()
                .addAll(
                        button("Apply placement", "print-place", this::applyPlacement),
                        button("Duplicate +70 mm X", "print-duplicate", this::duplicate),
                        row("print-face", "Planar face ID from current CAD revision", "0"),
                        button("Lay face on bed", "print-lay-face", this::layFace),
                        button("Remove instance", "print-remove", this::removeInstance));
        instances.setOnAction(
                e -> {
                    if (!updating) loadPlacement();
                });

        var process =
                new VBox(
                        7,
                        new Label("Generic Marlin • 200 × 200 × 200 mm • 0.4 mm nozzle"),
                        new Label("Material"),
                        materials,
                        row("print-layer-height", "Layer height (mm)", "0.2"),
                        row("print-walls", "Perimeter walls", "2"),
                        row("print-infill", "Infill (%)", "20"),
                        autoSupports,
                        bedOnly,
                        row("print-threshold", "Support threshold (degrees from horizontal)", "45"),
                        row("print-gap", "Support contact gap (mm)", "0.2"),
                        row("print-interfaces", "Interface layers", "3"),
                        row("print-brim", "Brim width (mm)", "0"),
                        row("print-raft", "Raft layers", "0"),
                        new Label("Slicer backend"),
                        backends,
                        button(
                                "Apply process settings",
                                "print-apply-process",
                                this::applyProcess));
        var capabilities = new Label();
        capabilities.setWrapText(true);
        backends.valueProperty()
                .addListener(
                        (o, a, b) ->
                                capabilities.setText(
                                        b == null
                                                ? "Choose an available backend"
                                                : "Available: "
                                                        + b.capabilities()
                                                        + "\nUnavailable here: adaptive layers, organic supports, painted regions, multiple materials, generated support meshes."));
        process.getChildren()
                .addAll(capabilities, new Label(String.join("\n", slicers.diagnostics())));

        var supports =
                new VBox(
                        7,
                        new Label("Existing support instruction"),
                        supportIds,
                        new Label("Attached build instance"),
                        supportOwners,
                        new Label("Role"),
                        supportRole,
                        row("print-support-name", "Stable support ID", "support-1"));
        for (var name : List.of("x", "y", "z", "width", "depth", "height"))
            supports.getChildren()
                    .add(
                            row(
                                    "print-support-" + name,
                                    "Body-space " + name + " (mm)",
                                    List.of("x", "y", "z").contains(name) ? "0" : "5"));
        supports.getChildren()
                .addAll(
                        button("Add / update support", "print-support-apply", this::applySupport),
                        button("Remove support", "print-support-remove", this::removeSupport),
                        new Label(
                                "Portable export: choose build space, then model, support solids, or both."));
        var generated =
                button(
                        "Export generated support mesh",
                        "print-generated-support",
                        () -> {
                            throw new IllegalStateException(
                                    "This backend provides support toolpaths, not generated support meshes");
                        });
        generated.setDisable(true);
        generated.setTooltip(
                new Tooltip(
                        "Generated support meshes are unavailable. Explicit support pillars can be exported."));
        supports.getChildren().add(generated);
        supportIds.setOnAction(
                e -> {
                    if (!updating) loadSupport();
                });

        slice = button("Slice", "print-slice", this::slice);
        cancel = button("Cancel slicing", "print-cancel", jobs::cancel);
        var job =
                new VBox(
                        7,
                        row(
                                "print-executable",
                                "Slicer executable",
                                PrusaSlicerBackend.defaultExecutable().toString()),
                        row(
                                "print-output",
                                "G-code destination",
                                Path.of(System.getProperty("user.home"), "part.gcode").toString()),
                        overwrite,
                        new HBox(8, slice, cancel),
                        button(
                                "Export geometry / project…",
                                "print-export",
                                () -> ExportDialog.show(owner, document, exporters)),
                        summary,
                        diagnostics);
        diagnostics.setEditable(false);
        diagnostics.setPrefRowCount(5);
        diagnostics.setId("print-diagnostics");
        summary.setWrapText(true);
        var tabs =
                new TabPane(
                        tab("Placement", placement),
                        tab("Process", process),
                        tab("Supports", supports),
                        tab("Job", job));
        tabs.setId("print-controls");
        tabs.setPrefWidth(330);
        tabs.setMinWidth(285);
        setRight(tabs);
        var center = new StackPane(canvas);
        center.setMinSize(150, 150);
        canvas.setManaged(false);
        canvas.widthProperty().bind(center.widthProperty());
        canvas.heightProperty().bind(center.heightProperty());
        canvas.widthProperty().addListener((o, a, b) -> draw());
        canvas.heightProperty().addListener((o, a, b) -> draw());
        setCenter(center);
        layer.setId("print-layer");
        layer.setBlockIncrement(1);
        layer.valueProperty().addListener((o, a, b) -> draw());
        var filters = new FlowPane(6, 4);
        for (var role : GcodeJob.Role.values()) {
            var check = new CheckBox(role.name().toLowerCase(Locale.ROOT).replace('_', ' '));
            check.setId("print-role-" + role.name().toLowerCase(Locale.ROOT));
            check.setSelected(role != GcodeJob.Role.TRAVEL);
            check.setOnAction(e -> draw());
            roles.put(role, check);
            filters.getChildren().add(check);
        }
        progress.setPrefSize(18, 18);
        progress.setMaxSize(18, 18);
        status.setId("print-status");
        status.setWrapText(true);
        validationLabel.setId("print-validation");
        validationLabel.setWrapText(true);
        setBottom(
                new VBox(
                        5,
                        layerLabel,
                        layer,
                        filters,
                        validationLabel,
                        new HBox(8, progress, status)));
        BorderPane.setMargin(tabs, new Insets(0, 0, 0, 8));
        jobs.onChanged(this::updateJob);
        updateJob();
    }

    private Tab tab(String name, VBox content) {
        content.setPadding(new Insets(8));
        content.getChildren().stream()
                .filter(n -> n instanceof Label)
                .forEach(n -> ((Label) n).setWrapText(true));
        var scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        var tab = new Tab(name, scroll);
        tab.setClosable(false);
        tab.setId("print-tab-" + name.toLowerCase(Locale.ROOT));
        return tab;
    }

    private VBox row(String id, String label, String initial) {
        var field = new TextField(initial);
        field.setId(id);
        fields.put(id, field);
        var caption = new Label(label);
        caption.setWrapText(true);
        return new VBox(3, caption, field);
    }

    private Button button(String title, String id, Runnable action) {
        var button = new Button(title);
        button.setId(id);
        button.setOnAction(
                e -> {
                    try {
                        action.run();
                    } catch (RuntimeException failure) {
                        status.setText(Objects.toString(failure.getMessage(), failure.toString()));
                    }
                });
        return button;
    }

    private double number(String id) {
        var field = fields.get(id);
        try {
            double value = Double.parseDouble(field.getText());
            if (!Double.isFinite(value)) throw new NumberFormatException();
            field.setStyle("");
            return value;
        } catch (NumberFormatException failure) {
            field.setStyle("-fx-border-color: #d04a3c;");
            throw new IllegalArgumentException(
                    id.replace("print-", "") + " must be a finite number");
        }
    }

    private int integer(String id) {
        double value = number(id);
        if (value != Math.rint(value))
            throw new IllegalArgumentException(id + " must be a whole number");
        return Math.toIntExact((long) value);
    }

    private PrintSetup setup() {
        return document.snapshot().document().printSetup();
    }

    private ModelView.Instance selected() {
        return setup().instances().stream()
                .filter(i -> i.id().equals(instances.getValue()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Choose a build instance"));
    }

    private void commit(PrintSetup setup) {
        document.printSetup(setup);
    }

    private void addInstance() {
        String body = Objects.requireNonNull(bodies.getValue(), "Choose a rebuilt CAD body");
        var mesh =
                document.build().bodies().stream()
                        .filter(b -> b.id().equals(body))
                        .findFirst()
                        .orElseThrow()
                        .mesh();
        var bounds = MeshGeometry.bounds(mesh);
        var all = new ArrayList<>(setup().instances());
        String id = "part-" + UUID.randomUUID();
        all.add(
                new ModelView.Instance(
                        id,
                        body,
                        Transform.translation(
                                10 + 70 * all.size() - bounds.minX(),
                                10 - bounds.minY(),
                                -bounds.minZ())));
        commit(setup().withInstances(all));
        instances.setValue(id);
    }

    private void applyPlacement() {
        var selected = selected();
        var transform =
                Transform.translation(number("print-x"), number("print-y"), number("print-z"))
                        .compose(
                                Transform.rotation(
                                        number("print-rotate-x"),
                                        number("print-rotate-y"),
                                        number("print-rotate-z")));
        replace(
                new ModelView.Instance(
                        selected.id(),
                        Objects.requireNonNull(bodies.getValue(), "Choose a CAD body"),
                        transform));
    }

    private void replace(ModelView.Instance selected) {
        commit(
                setup().withInstances(
                                setup().instances().stream()
                                        .map(i -> i.id().equals(selected.id()) ? selected : i)
                                        .toList()));
    }

    private void duplicate() {
        var source = selected();
        String id = "part-" + UUID.randomUUID();
        var all = new ArrayList<>(setup().instances());
        all.add(
                new ModelView.Instance(
                        id,
                        source.bodyId(),
                        Transform.translation(70, 0, 0).compose(source.transform())));
        var supports = new ArrayList<>(setup().supports());
        for (var s : setup().supports())
            if (s.instanceId().equals(source.id()))
                supports.add(
                        new SupportVolume(
                                "support-" + UUID.randomUUID(),
                                id,
                                s.role(),
                                s.x(),
                                s.y(),
                                s.z(),
                                s.width(),
                                s.depth(),
                                s.height()));
        commit(setup().withInstances(all).withSupports(supports));
        instances.setValue(id);
    }

    private void removeInstance() {
        var id = selected().id();
        commit(
                setup().withInstances(
                                setup().instances().stream()
                                        .filter(i -> !i.id().equals(id))
                                        .toList()));
    }

    private void layFace() {
        var selected = selected();
        var mesh =
                document.build().bodies().stream()
                        .filter(b -> b.id().equals(selected.bodyId()))
                        .findFirst()
                        .orElseThrow()
                        .mesh();
        replace(
                new ModelView.Instance(
                        selected.id(),
                        selected.bodyId(),
                        MeshGeometry.layOnFace(mesh, integer("print-face"), selected.transform())));
    }

    void selectedFace(String body, int face) {
        if (face >= 0) {
            bodies.setValue(body);
            fields.get("print-face").setText(Integer.toString(face));
        }
    }

    private void loadPlacement() {
        if (instances.getValue() == null) return;
        var selected = selected();
        bodies.setValue(selected.bodyId());
        var m = selected.transform().matrix();
        double ry = Math.asin(Math.max(-1, Math.min(1, -m[8]))),
                rx = Math.atan2(m[9], m[10]),
                rz = Math.atan2(m[4], m[0]);
        if (Math.abs(Math.cos(ry)) < 1e-8) {
            rx = 0;
            rz = Math.atan2(-m[1], m[5]);
        }
        String[] names = {"x", "y", "z", "rotate-x", "rotate-y", "rotate-z"};
        double[] values = {
            m[3],
            m[7],
            m[11],
            dev.voxelbrew.engine.units.Measurements.degrees(rx),
            dev.voxelbrew.engine.units.Measurements.degrees(ry),
            dev.voxelbrew.engine.units.Measurements.degrees(rz)
        };
        for (int i = 0; i < names.length; i++)
            fields.get("print-" + names[i]).setText(Double.toString(values[i]));
    }

    private void applyProcess() {
        var backend =
                Objects.requireNonNull(backends.getValue(), "Choose an installed slicer backend");
        var process =
                new ProcessSettings(
                        number("print-layer-height"),
                        integer("print-walls"),
                        number("print-infill"),
                        autoSupports.isSelected(),
                        bedOnly.isSelected(),
                        number("print-threshold"),
                        number("print-gap"),
                        integer("print-interfaces"),
                        number("print-brim"),
                        integer("print-raft"));
        commit(
                setup().withProfile(
                                backend.id(),
                                "generic-marlin-200-0.4",
                                Objects.requireNonNull(materials.getValue(), "Choose a material"),
                                process));
    }

    private void applySupport() {
        var volume =
                new SupportVolume(
                        fields.get("print-support-name").getText(),
                        Objects.requireNonNull(
                                supportOwners.getValue(), "Choose an attached instance"),
                        Objects.requireNonNull(supportRole.getValue()),
                        number("print-support-x"),
                        number("print-support-y"),
                        number("print-support-z"),
                        number("print-support-width"),
                        number("print-support-depth"),
                        number("print-support-height"));
        var all = new ArrayList<>(setup().supports());
        all.removeIf(s -> s.id().equals(volume.id()));
        all.add(volume);
        commit(setup().withSupports(all));
        supportIds.setValue(volume.id());
    }

    private void removeSupport() {
        String id = Objects.requireNonNull(supportIds.getValue(), "Choose a support instruction");
        commit(
                setup().withSupports(
                                setup().supports().stream()
                                        .filter(s -> !s.id().equals(id))
                                        .toList()));
    }

    private void loadSupport() {
        setup().supports().stream()
                .filter(s -> s.id().equals(supportIds.getValue()))
                .findFirst()
                .ifPresent(
                        s -> {
                            fields.get("print-support-name").setText(s.id());
                            supportOwners.setValue(s.instanceId());
                            supportRole.setValue(s.role());
                            String[] names = {"x", "y", "z", "width", "depth", "height"};
                            double[] values = {
                                s.x(), s.y(), s.z(), s.width(), s.depth(), s.height()
                            };
                            for (int i = 0; i < names.length; i++)
                                fields.get("print-support-" + names[i])
                                        .setText(Double.toString(values[i]));
                        });
    }

    private void slice() {
        if (!document.canExport())
            throw new IllegalStateException(
                    "Slice requires a complete successful rebuild at the timeline end");
        jobs.start(
                        document.snapshot().document(),
                        slicers.require(setup().backendId()),
                        Path.of(fields.get("print-executable").getText()),
                        Path.of(fields.get("print-output").getText()),
                        overwrite.isSelected())
                .exceptionally(failure -> null);
    }

    void documentChanged() {
        if (closed) return;
        var snapshot = document.snapshot().document();
        jobs.documentChanged(snapshot);
        if (document.rebuilding()) jobs.invalidateGeometry();
        updating = true;
        try {
            if (lastBuild != document.build()) {
                String selected = bodies.getValue();
                bodies.getItems()
                        .setAll(
                                document.build().bodies().stream()
                                        .map(DocumentController.Body::id)
                                        .toList());
                bodies.setValue(
                        selected == null
                                ? bodies.getItems().stream().findFirst().orElse(null)
                                : selected);
                lastBuild = document.build();
            }
            if (!setup().equals(lastSetup)) {
                String selected = instances.getValue();
                instances
                        .getItems()
                        .setAll(setup().instances().stream().map(ModelView.Instance::id).toList());
                instances.setValue(
                        instances.getItems().contains(selected)
                                ? selected
                                : instances.getItems().stream().findFirst().orElse(null));
                supportOwners.getItems().setAll(instances.getItems());
                if (!supportOwners.getItems().contains(supportOwners.getValue()))
                    supportOwners.setValue(instances.getValue());
                supportIds
                        .getItems()
                        .setAll(setup().supports().stream().map(SupportVolume::id).toList());
                materials.setValue(setup().materialId());
                backends.setValue(
                        backends.getItems().stream()
                                .filter(b -> b.id().equals(setup().backendId()))
                                .findFirst()
                                .orElse(null));
                var p = setup().process();
                String[] names = {
                    "layer-height",
                    "walls",
                    "infill",
                    "threshold",
                    "gap",
                    "interfaces",
                    "brim",
                    "raft"
                };
                double[] values = {
                    p.layerHeight(),
                    p.walls(),
                    p.infillPercent(),
                    p.supportThreshold(),
                    p.supportContactGap(),
                    p.interfaceLayers(),
                    p.brimWidth(),
                    p.raftLayers()
                };
                for (int i = 0; i < names.length; i++)
                    fields.get("print-" + names[i]).setText(Double.toString(values[i]));
                autoSupports.setSelected(p.automaticSupports());
                bedOnly.setSelected(p.supportsOnBedOnly());
                lastSetup = setup();
                loadPlacement();
            }
        } finally {
            updating = false;
        }
        String revision = DocumentCodec.fingerprint(snapshot);
        if (!document.canExport()) {
            model = null;
            validation = null;
            validatedRevision = "";
            validationSequence++;
            validationLabel.setText(
                    "Finish rebuilding the complete document before preparing a print.");
        } else if (!revision.equals(validatedRevision)) {
            validatedRevision = revision;
            validation = null;
            try {
                var meshes = new LinkedHashMap<String, MeshData>();
                document.build().bodies().forEach(b -> meshes.put(b.id(), b.mesh()));
                model =
                        new ManufacturingModel(
                                snapshot.id(),
                                revision,
                                setup(),
                                PrintProfile.resolve(setup()),
                                meshes);
                var captured = model;
                long ticket = ++validationSequence;
                if (validationTask != null) validationTask.cancel(false);
                validationLabel.setText("Validating build placement and meshes…");
                validationTask =
                        validator.submit(
                                () -> {
                                    var result = PrintValidation.validate(captured);
                                    Platform.runLater(
                                            () -> {
                                                if (closed || ticket != validationSequence) return;
                                                validation = result;
                                                validationLabel.setText(
                                                        result.valid()
                                                                ? "Print setup valid — millimeters"
                                                                : result.diagnostics().toString());
                                                updateJob();
                                            });
                                });
            } catch (RuntimeException failure) {
                model = null;
                validationLabel.setText(failure.getMessage());
            }
        }
        updateJob();
        draw();
    }

    private void updateJob() {
        slice.setDisable(
                !document.canExport()
                        || jobs.running()
                        || validation == null
                        || !validation.valid());
        cancel.setDisable(!jobs.running());
        progress.setVisible(jobs.running());
        status.setText(jobs.state() + ": " + jobs.message());
        diagnostics.setText(String.join("\n", jobs.diagnostics()));
        var result = jobs.result();
        if (result != displayed) {
            displayed = result;
            if (result == null) {
                previewLayers = List.of();
                summary.setText("");
            } else {
                var job = result.job();
                previewLayers =
                        job.segments().stream()
                                .filter(GcodeJob.Segment::deposits)
                                .map(s -> Math.rint(s.z1() * 1e6) / 1e6)
                                .distinct()
                                .sorted()
                                .toList();
                summary.setText(
                        job.report().get("printer")
                                + "\n"
                                + job.report().get("material")
                                + " • "
                                + job.report().get("nozzle_mm")
                                + " mm nozzle\n"
                                + job.modelLayers().size()
                                + " model layers\nEstimated: "
                                + job.estimatedTime().orElse("unknown")
                                + "\nFilament: "
                                + (job.filamentMm().isPresent()
                                        ? String.format(
                                                Locale.ROOT,
                                                "%.1f mm",
                                                job.filamentMm().getAsDouble())
                                        : "unknown"));
                for (var entry : roles.entrySet())
                    entry.getValue()
                            .setDisable(
                                    job.segments().stream()
                                            .noneMatch(s -> s.role() == entry.getKey()));
            }
            layer.setMax(Math.max(0, previewLayers.size() - 1));
            layer.setValue(0);
            layer.setDisable(previewLayers.isEmpty());
        }
        draw();
    }

    void setDark(boolean value) {
        dark = value;
        draw();
    }

    private void draw() {
        var graphics = canvas.getGraphicsContext2D();
        double w = canvas.getWidth(), h = canvas.getHeight();
        graphics.setFill(dark ? Color.web("#182028") : Color.web("#edf1f4"));
        graphics.fillRect(0, 0, w, h);
        if (w < 1 || h < 1) return;
        double scale = Math.max(0.01, Math.min((w - 48) / 200, (h - 48) / 200)),
                ox = (w - 200 * scale) / 2,
                oy = (h + 200 * scale) / 2;
        graphics.setStroke(dark ? Color.web("#3b4651") : Color.web("#bac4cc"));
        graphics.setLineWidth(1);
        for (int n = 0; n <= 200; n += 10) {
            graphics.strokeLine(ox + n * scale, oy, ox + n * scale, oy - 200 * scale);
            graphics.strokeLine(ox, oy - n * scale, ox + 200 * scale, oy - n * scale);
        }
        graphics.setFill(dark ? Color.LIGHTGRAY : Color.DARKSLATEGRAY);
        graphics.fillText("0,0", ox, oy + 16);
        graphics.fillText("200 × 200 mm", ox, oy - 200 * scale - 8);
        if (displayed != null && !previewLayers.isEmpty()) {
            int index = Math.min(previewLayers.size() - 1, (int) Math.round(layer.getValue()));
            double z = previewLayers.get(index);
            layerLabel.setText(
                    "Layer "
                            + (index + 1)
                            + " / "
                            + previewLayers.size()
                            + String.format(Locale.ROOT, " • Z %.3f mm", z));
            for (var segment : displayed.job().segments())
                if (Math.abs(segment.z1() - z) < 1e-5 && roles.get(segment.role()).isSelected()) {
                    graphics.setStroke(color(segment.role()));
                    graphics.setLineWidth(
                            segment.deposits() ? Math.max(1, segment.width() * scale) : 0.5);
                    graphics.strokeLine(
                            ox + segment.x0() * scale,
                            oy - segment.y0() * scale,
                            ox + segment.x1() * scale,
                            oy - segment.y1() * scale);
                }
        } else {
            layerLabel.setText("Build placement • slice to preview actual toolpaths");
            if (model == null) return;
            int index = 0;
            for (var instance : model.instances()) {
                if (!model.meshes().containsKey(instance.bodyId())) continue;
                var mesh = model.placedMesh(instance);
                graphics.setFill(Color.hsb(195 + (index++ * 48) % 160, 0.6, 0.8, 0.3));
                for (int t = 0; t < mesh.triangleCount(); t++) {
                    double[] x = new double[3], y = new double[3];
                    for (int c = 0; c < 3; c++) {
                        int v = mesh.vertexIndex(t, c);
                        x[c] = ox + mesh.coordinate(v, 0) * scale;
                        y[c] = oy - mesh.coordinate(v, 1) * scale;
                    }
                    graphics.fillPolygon(x, y, 3);
                }
            }
            for (var support : model.setup().supports()) {
                var owner =
                        model.instances().stream()
                                .filter(i -> i.id().equals(support.instanceId()))
                                .findFirst();
                if (owner.isEmpty()) continue;
                var box =
                        MeshGeometry.transform(MeshGeometry.box(support), owner.get().transform());
                graphics.setStroke(
                        support.role() == SupportVolume.Role.BLOCKER
                                ? Color.CRIMSON
                                : support.role() == SupportVolume.Role.ENFORCER
                                        ? Color.MEDIUMPURPLE
                                        : Color.LIMEGREEN);
                graphics.setLineWidth(2);
                for (int t = 0; t < box.triangleCount(); t++)
                    for (int c = 0; c < 3; c++) {
                        int a = box.vertexIndex(t, c), b = box.vertexIndex(t, (c + 1) % 3);
                        graphics.strokeLine(
                                ox + box.coordinate(a, 0) * scale,
                                oy - box.coordinate(a, 1) * scale,
                                ox + box.coordinate(b, 0) * scale,
                                oy - box.coordinate(b, 1) * scale);
                    }
            }
        }
    }

    private static Color color(GcodeJob.Role role) {
        return switch (role) {
            case EXTERNAL_PERIMETER -> Color.DEEPSKYBLUE;
            case PERIMETER -> Color.ROYALBLUE;
            case INFILL, SOLID_INFILL, GAP_FILL, BRIDGE, IRONING -> Color.DARKORANGE;
            case SUPPORT -> Color.LIMEGREEN;
            case INTERFACE -> Color.MEDIUMPURPLE;
            case SKIRT_BRIM -> Color.CRIMSON;
            case TRAVEL, UNKNOWN -> Color.GRAY;
        };
    }

    PrintController jobs() {
        return jobs;
    }

    @Override
    public void close() {
        closed = true;
        validationSequence++;
        jobs.close();
        validator.shutdownNow();
    }
}
