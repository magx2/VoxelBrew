package dev.voxelbrew.gui;

import dev.voxelbrew.engine.mesh.*;
import dev.voxelbrew.engine.model.BodyInstance;
import dev.voxelbrew.engine.model.Transform;
import dev.voxelbrew.engine.parameters.*;
import dev.voxelbrew.engine.persistence.DocumentCodec;
import dev.voxelbrew.engine.printing.*;
import dev.voxelbrew.exporter.api.ExporterCatalog;
import dev.voxelbrew.slicer.api.PrinterCatalog;
import dev.voxelbrew.slicer.api.SlicerBackend;
import dev.voxelbrew.slicer.api.SlicerCatalog;
import dev.voxelbrew.slicer.api.Slicing;
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
    private final SlicerWorkspace workspace = new SlicerWorkspace();
    private final PrinterCatalog printers;
    private final PrinterVisibility printerVisibility;
    private Set<String> hiddenPrinters = Set.of();
    private PrinterCatalog.Result printerCatalog;
    private final Button printerChoice = new Button();
    private final Label printerDetails = new Label();
    private final javafx.scene.image.ImageView printerImage = new javafx.scene.image.ImageView();
    private final DocumentController document;
    private final PrintController jobs;
    private final SlicerCatalog slicers;
    private final SlicerSettings slicerSettings;
    private final SlicerAvailability availability;
    private final SlicerStatusView backendStatus;
    private final ExecutorService validator;
    private final dev.voxelbrew.util.i18n.Translations translations;
    private Future<?> validationTask;
    private long validationSequence;
    private final Label plateSize = new Label();
    private boolean threeDimensional;
    private Path outputProject;
    private Path outputDestination;
    private Button autoArrange;
    private Button orientAll;
    private boolean orienting;
    private final ToggleButton toolpathPreview = new ToggleButton();
    private double plateWidth = 200, plateDepth = 200, plateMinX, plateMinY;
    private final Canvas canvas = new Canvas();
    private final HBox plateStrip = new HBox(6);
    private TextField plateNameEditor;
    private final VBox previewControls = new VBox(10);
    private final Label placementHint = new Label();
    private double viewScale, viewOx, viewOy;
    private PrintDepthBuffer placementRaster;
    private final PrintCamera topCamera = new PrintCamera(false), spatialCamera = new PrintCamera(true);
    private boolean navigating, primaryNavigation;
    private double navigationX, navigationY;
    private double dragX, dragY;
    private BodyInstance dragSource, dragPreview;
    private String dragRevision;
    private final ComboBox<PrintRotationGizmo.Space> rotationSpace = new ComboBox<>();
    private final CheckBox localGizmoAxes = new CheckBox();
    private final CheckBox showRotationGizmos = new CheckBox();
    private List<PrintRotationGizmo.Ring> rotationRings = List.of();
    private PrintRotationGizmo.Drag rotationDrag;
    private PrintRotationGizmo.Axis hoveredRotation;
    private double rotationDegrees;
    private final ComboBox<String> targetPlate = new ComboBox<>();
    private final ComboBox<String> bodies = new ComboBox<>(),
            instances = new ComboBox<>(),
            materials = new ComboBox<>(),
            supportIds = new ComboBox<>(),
            supportOwners = new ComboBox<>();
    private final ComboBox<SlicerBackend.Descriptor> backends = new ComboBox<>();
    private final ComboBox<SupportVolume.Role> supportRole = new ComboBox<>();
    private final Map<String, FormulaField> fields = new LinkedHashMap<>();
    private final CheckBox supportsEnabled = new CheckBox();
    private final CheckBox autoSupports = new CheckBox(""), bedOnly = new CheckBox(""), overwrite = new CheckBox("");
    private final Slider layer = new Slider(0, 0, 0);
    private final Label layerLabel = new Label(""),
            validationLabel = new Label(""),
            status = new Label(""),
            summary = new Label();
    private final TextArea diagnostics = new TextArea();
    private final EnumMap<GcodeJob.Role, CheckBox> roles = new EnumMap<>(GcodeJob.Role.class);
    private final Button slice;
    private final Button sliceAll;
    private final Button cancel;
    private final ProgressIndicator progress = new ProgressIndicator();
    private ManufacturingModel model;
    private PrintValidation.Result validation;
    private PrintSetup lastSetup;
    private DocumentController.Build lastBuild;
    private String validatedRevision = "";
    private final Tooltip selectedObjectTooltip = UiHelp.tooltip("");
    private final Label selectedObjectName = new Label();
    private final TabPane tabs = new TabPane();
    private int selectedFaceIndex;
    private String selectedFaceBody;
    private boolean updating, dark, closed;
    private List<Double> previewLayers = List.of();
    private Slicing.Result displayed;

    @javax.inject.Inject
    PrintingPane(
            PrinterCatalog printers,
            PrinterVisibility printerVisibility,
            DocumentController document,
            PrintController jobs,
            SlicerCatalog slicers,
            SlicerSettings slicerSettings,
            SlicerAvailability availability,
            ExporterCatalog exporters,
            javafx.stage.Stage owner,
            @javax.inject.Named("validation") ExecutorService validator,
            dev.voxelbrew.util.i18n.Translations translations,
            dev.voxelbrew.util.config.UserConfiguration configuration,
            @javax.inject.Named("slicerExecutable") java.nio.file.Path defaultExecutable) {
        this.translations = translations;
        this.printers = printers;
        this.printerVisibility = printerVisibility;
        autoSupports.setText(translations.text("gui.automatic_supports"));
        bedOnly.setText(translations.text("gui.build_plate_only"));
        overwrite.setText(translations.text("gui.replace_existing_job"));
        layerLabel.setText(translations.text("gui.no_sliced_layers"));
        validationLabel.setText(translations.text("gui.add_a_build_instance"));
        status.setText(translations.text("gui.ready_to_slice"));
        this.document = document;
        this.jobs = jobs;
        this.slicers = slicers;
        this.slicerSettings = slicerSettings;
        this.availability = availability;
        backendStatus = new SlicerStatusView(availability, translations, "print-backend-status");
        this.validator = validator;
        setId("printing-workspace");
        getStyleClass().add("slicer-workspace");
        canvas.setId("print-canvas");
        canvas.setFocusTraversable(true);
        installDragging();
        setPadding(new Insets(8));
        bodies.setId("print-body");
        UiText.choices(bodies, this::bodyName);
        bodies.setMinWidth(220);
        UiText.choices(instances, this::instanceName);
        UiText.choices(supportOwners, this::instanceName);
        for (var choice : List.of(bodies, instances, supportOwners)) {
            choice.setCellFactory(list -> namedCell(choice));
            choice.setButtonCell(namedCell(choice));
        }
        for (var choice : List.of(instances, supportOwners, supportIds)) {
            choice.setMinWidth(220);
            choice.disableProperty().bind(javafx.beans.binding.Bindings.isEmpty(choice.getItems()));
            choice.setPromptText(translations.text(choice == supportIds ? "print.noSupports" : "print.noInstances"));
        }
        instances.setId("print-instance");
        materials.setId("print-material");
        backends.setId("print-backend");
        supportIds.setId("print-support-id");
        supportOwners.setId("print-support-instance");
        supportRole.setId("print-support-role");
        UiText.enums(supportRole, translations, "print.support.");
        autoSupports.setId("print-auto-supports");
        bedOnly.setId("print-bed-only");
        overwrite.setId("print-overwrite");
        overwrite.setSelected(true);
        for (var check : List.of(autoSupports, bedOnly, overwrite)) UiHelp.install(check, translations);
        materials.getItems().setAll("PLA", "PETG");
        supportRole.getItems().setAll(SupportVolume.Role.values());
        supportRole.setValue(SupportVolume.Role.SOLID_SUPPORT);
        backends.getItems().setAll(slicers.descriptors());
        backends.setConverter(new StringConverter<>() {
            @Override
            public String toString(SlicerBackend.Descriptor descriptor) {
                return descriptor == null ? "" : descriptor.name();
            }

            @Override
            public SlicerBackend.Descriptor fromString(String text) {
                throw new UnsupportedOperationException();
            }
        });
        bodies.setMinWidth(0);
        bodies.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(bodies, Priority.ALWAYS);
        var addBody = button(translations.text("gui.add_selected_body"), "print-add", this::addInstance);
        addBody.setMinWidth(Region.USE_PREF_SIZE);
        var bodyRow = new HBox(8, bodies, addBody);
        bodyRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        var placement = new VBox(
                7,
                new Label(translations.text("gui.cad_body")),
                bodyRow,
                new Separator(),
                new Label(translations.text("gui.build_instance")),
                instances);
        showRotationGizmos.setId("print-show-rotation-gizmos");
        showRotationGizmos.setText(translations.text("print.showRotationGizmos"));
        UiHelp.install(showRotationGizmos, translations);
        showRotationGizmos.selectedProperty().addListener((observable, before, after) -> {
            cancelDrag();
            draw();
        });
        rotationSpace.setId("print-rotation-space");
        rotationSpace.getItems().setAll(PrintRotationGizmo.Space.values());
        rotationSpace.setValue(PrintRotationGizmo.Space.WORLD);
        UiText.enums(rotationSpace, translations, "print.rotationSpace.");
        UiHelp.install(rotationSpace, translations);
        rotationSpace
                .disableProperty()
                .bind(showRotationGizmos.selectedProperty().not());
        rotationSpace.setOnAction(event -> {
            localGizmoAxes.setSelected(rotationSpace.getValue() == PrintRotationGizmo.Space.LOCAL);
            cancelDrag();
            draw();
        });
        rotationSpace.setMinWidth(0);
        rotationSpace.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(rotationSpace, Priority.ALWAYS);
        var gizmoControls = new HBox(8, showRotationGizmos, rotationSpace);
        gizmoControls.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        placement.getChildren().addAll(placementCoordinates(false), placementCoordinates(true));
        var applyPlacement = button(translations.text("gui.apply_placement"), "print-place", this::applyPlacement);
        applyPlacement.setMaxWidth(Double.MAX_VALUE);
        placement
                .getChildren()
                .addAll(
                        applyPlacement,
                        gizmoControls,
                        new Separator(),
                        equalRow(
                                button(translations.text("gui.lay_face_on_bed"), "print-lay-face", this::layFace),
                                button(translations.text("print.autoOrient"), "print-auto-orient", this::autoOrient)));
        instances.setOnAction(e -> {
            if (!updating) {
                loadPlacement();
                draw();
            }
        });
        targetPlate.setId("print-target-plate");
        targetPlate.setMaxWidth(Double.MAX_VALUE);
        UiText.choices(targetPlate, this::plateName);
        targetPlate.setMinWidth(0);
        HBox.setHgrow(targetPlate, Priority.ALWAYS);
        var moveObject = button(translations.text("print.moveObject"), "print-move-plate", () -> {
            String target = targetPlate.getValue();
            if (target != null) commit(setup().moveInstance(selected().id(), target));
        });
        moveObject.setMinWidth(Region.USE_PREF_SIZE);
        var moveRow = new HBox(8, targetPlate, moveObject);
        moveRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        placement.getChildren().addAll(new Separator(), new Label(translations.text("print.moveToPlate")), moveRow);

        var process = new VBox(
                7,
                new Label(translations.text("gui.slicer_backend")),
                backends,
                backendStatus,
                new Label(translations.text("gui.material")),
                materials,
                row("print-layer-height", translations.text("gui.layer_height_mm"), "0.2"),
                row("print-walls", translations.text("gui.perimeter_walls"), "2"),
                row("print-infill", translations.text("gui.infill"), "20"),
                autoSupports,
                bedOnly,
                row("print-threshold", translations.text("gui.support_threshold_degrees_from_horizontal"), "45"),
                row("print-gap", translations.text("gui.support_contact_gap_mm"), "0.2"),
                row("print-interfaces", translations.text("gui.interface_layers"), "3"),
                row("print-brim", translations.text("gui.brim_width_mm"), "0"),
                row("print-raft", translations.text("gui.raft_layers"), "0"));

        backends.valueProperty().addListener((o, a, b) -> backendStatus.showBackend(b == null ? null : b.id()));
        var capabilities = new Label();
        capabilities.setWrapText(true);
        backends.valueProperty()
                .addListener(
                        (o, a, b) -> capabilities.setText(
                                b == null
                                        ? translations.text("gui.choose_an_available_backend")
                                        : translations.text(
                                                        "print.available",
                                                        b.capabilities().stream()
                                                                .map(c -> translations.text(
                                                                        "print.capability." + c.name()))
                                                                .collect(java.util.stream.Collectors.joining(", ")))
                                                + translations.text(
                                                        "gui.unavailable_here_adaptive_layers_organic_supports_painted_regions_multiple_materials_generated_")));
        process.getChildren().addAll(capabilities, new Label(String.join("\n", slicers.diagnostics())));

        supportsEnabled.setId("print-supports-enabled");
        supportsEnabled.setText(translations.text("print.supportsEnabled"));
        supportsEnabled.setSelected(true);
        UiHelp.install(supportsEnabled, translations);
        supportsEnabled.setOnAction(event -> {
            if (!updating) commit(setup().withSupportsDisabled(!supportsEnabled.isSelected()));
        });
        autoSupports.disableProperty().bind(supportsEnabled.selectedProperty().not());
        bedOnly.disableProperty().bind(supportsEnabled.selectedProperty().not());
        for (String id : List.of("threshold", "gap", "interfaces", "raft"))
            fields.get("print-" + id)
                    .disableProperty()
                    .bind(supportsEnabled.selectedProperty().not());
        var supports = new VBox(
                7,
                new Label(translations.text("gui.existing_support_instruction")),
                supportIds,
                new Label(translations.text("gui.attached_build_instance")),
                supportOwners,
                new Label(translations.text("gui.role")),
                supportRole,
                row("print-support-name", translations.text("gui.stable_support_id"), "support-1"));
        for (var name : List.of("x", "y", "z", "width", "depth", "height"))
            supports.getChildren()
                    .add(row(
                            "print-support-" + name,
                            translations.text("print.bodySpace", name),
                            List.of("x", "y", "z").contains(name) ? "0" : "5"));
        supports.getChildren()
                .addAll(
                        button(translations.text("gui.add_update_support"), "print-support-apply", this::applySupport),
                        button(translations.text("gui.remove_support"), "print-support-remove", this::removeSupport),
                        new Label(translations.text(
                                "gui.portable_export_choose_build_space_then_model_support_solids_or_both")));
        var generated =
                button(translations.text("gui.export_generated_support_mesh"), "print-generated-support", () -> {
                    throw new IllegalStateException(translations.text(
                            "gui.this_backend_provides_support_toolpaths_not_generated_support_meshes"));
                });
        generated.setDisable(true);
        UiHelp.describe(
                generated,
                translations.text(
                        "gui.generated_support_meshes_are_unavailable_explicit_support_pillars_can_be_exported"));
        supports.getChildren().add(generated);
        supports.disableProperty().bind(supportsEnabled.selectedProperty().not());
        for (var child : supports.getChildren()) {
            if (child instanceof Control control) control.setMaxWidth(Double.MAX_VALUE);
            if (child instanceof Label label) label.setWrapText(true);
        }
        var supportsHelp = new Label(translations.text("print.supportsEnabledHelp"));
        supportsHelp.setWrapText(true);
        var supportsTab = new VBox(7, supportsEnabled, supportsHelp, new Separator(), supports);

        supportIds.setOnAction(e -> {
            if (!updating) loadSupport();
        });

        slice = button(translations.text("gui.slice"), "print-slice", this::slice);
        sliceAll = button(translations.text("print.sliceAll"), "print-slice-all", () -> slice(true));
        cancel = button(translations.text("gui.cancel_slicing"), "print-cancel", jobs::cancel);
        var job = new VBox(
                7,
                row("print-output", translations.text("gui.g_code_destination"), ""),
                overwrite,
                summary,
                diagnostics);
        diagnostics.setEditable(false);
        diagnostics.setPrefRowCount(5);
        diagnostics.setId("print-diagnostics");
        summary.setWrapText(true);
        tabs.getTabs()
                .setAll(
                        tab("Placement", placement),
                        tab("Process", process),
                        tab("Supports", supportsTab),
                        tab("Job", job));
        tabs.setId("print-controls");
        tabs.setPrefWidth(360);
        tabs.setMinWidth(320);
        printerChoice.setId("print-printer");
        printerChoice.setMaxWidth(Double.MAX_VALUE);
        printerChoice.setOnAction(event -> PrinterPickerDialog.choose(
                        getScene().getWindow(),
                        translations,
                        printerCatalog.entries().stream()
                                .filter(this::printerVisible)
                                .toList(),
                        setup().printerId())
                .ifPresent(chosen -> commit(setup().withPrinter(chosen.definition()))));
        printerDetails.setWrapText(true);
        printerDetails.setId("print-printer-details");
        printerImage.setFitWidth(96);
        printerImage.setFitHeight(72);
        printerImage.setPreserveRatio(true);
        var printerPanel = new VBox(
                6,
                new Label(translations.text("print.printerSelector")),
                printerChoice,
                new HBox(8, printerImage, printerDetails),
                equalRow(
                        button(
                                translations.text("print.reloadPrinters"),
                                "print-reload-printers",
                                this::reloadPrinters),
                        button(
                                translations.text("print.openPrinters"),
                                "print-open-printers",
                                this::openPrintersFolder)));
        printerPanel.setPadding(new Insets(8));
        printerPanel.setMaxWidth(360);
        var sidebar = new VBox(6, printerPanel, tabs);
        VBox.setVgrow(tabs, Priority.ALWAYS);
        setRight(sidebar);
        reloadPrinters();
        localGizmoAxes.setId("print-gizmo-local");
        localGizmoAxes.setText(translations.text("print.localGizmoAxes"));
        UiHelp.install(localGizmoAxes, translations);
        localGizmoAxes.setManaged(false);
        localGizmoAxes.setVisible(false);
        localGizmoAxes.setOnAction(event -> rotationSpace.setValue(
                localGizmoAxes.isSelected() ? PrintRotationGizmo.Space.LOCAL : PrintRotationGizmo.Space.WORLD));
        selectedObjectName.setId("print-selected-object-name");
        selectedObjectName.setManaged(false);
        var removeObject = button("", "print-remove", this::removeInstance);
        var closeIcon = new javafx.scene.shape.SVGPath();
        closeIcon.setContent("M 1 1 L 9 9 M 9 1 L 1 9");
        closeIcon.getStyleClass().add("object-close-icon");
        closeIcon.setMouseTransparent(true);
        removeObject.setGraphic(closeIcon);
        removeObject.getStyleClass().add("object-close-button");
        removeObject.setAccessibleText(translations.text("gui.remove_instance"));
        removeObject.setTooltip(UiHelp.tooltip(translations.text("gui.remove_instance")));
        selectedObjectName.setGraphic(removeObject);
        selectedObjectName.setContentDisplay(ContentDisplay.RIGHT);
        selectedObjectName.setVisible(false);
        selectedObjectName.getStyleClass().add("selected-object-label");
        var center = new StackPane(canvas, localGizmoAxes, selectedObjectName);
        center.setMinSize(150, 150);
        canvas.setManaged(false);
        canvas.widthProperty().bind(center.widthProperty());
        canvas.heightProperty().bind(center.heightProperty());
        canvas.widthProperty().addListener((o, a, b) -> draw());
        canvas.heightProperty().addListener((o, a, b) -> draw());
        var viewMode = new ToggleGroup();
        var topView = new RadioButton("2D");
        var solidView = new RadioButton("3D");
        topView.setId("print-view-2d");
        solidView.setId("print-view-3d");
        UiHelp.install(topView, translations);
        UiHelp.install(solidView, translations);
        topView.setToggleGroup(viewMode);
        solidView.setToggleGroup(viewMode);
        topView.setSelected(true);
        viewMode.selectedToggleProperty().addListener((o, a, b) -> {
            cancelDrag();
            navigating = false;
            threeDimensional = b == solidView;
            draw();
        });
        plateSize.setId("print-plate-size");
        plateSize.setTooltip(new Tooltip(translations.text("print.editPlate")));
        plateSize.setOnMouseClicked(e -> {
            plateSize.requestFocus();
            if (e.getClickCount() == 2) editPlate();
        });
        plateSize.setFocusTraversable(true);
        plateSize.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ENTER) {
                editPlate();
                e.consume();
            }
        });
        var preview = new BorderPane(center);
        toolpathPreview.setText(translations.text("print.preview"));
        toolpathPreview.setId("print-preview");
        toolpathPreview.setTooltip(UiHelp.tooltip(translations.text("print.previewHelp")));
        toolpathPreview.setOnAction(event -> {
            updatePreviewControls();
            draw();
        });
        var viewBar = new FlowPane(
                12,
                8,
                topView,
                solidView,
                new Separator(javafx.geometry.Orientation.VERTICAL),
                plateSize,
                toolpathPreview);
        var fitView = new Button(translations.text("print.fitView"));
        fitView.setId("print-fit-view");
        fitView.setTooltip(UiHelp.tooltip(translations.text("print.cameraHelp")));
        fitView.setOnAction(event -> {
            cancelDrag();
            camera().fit();
            draw();
        });
        autoArrange = button(translations.text("print.autoArrange"), "print-auto-arrange", this::autoArrange);
        orientAll = button(translations.text("print.orientAll"), "print-orient-all", () -> autoOrient(true));
        viewBar.getChildren().addAll(fitView, new HBox(6, autoArrange, orientAll));
        canvas.setAccessibleHelp(
                translations.text("print.cameraHelp") + "\n" + translations.text("print.rotationGizmoHelp"));
        placementHint.setTooltip(UiHelp.tooltip(translations.text("print.cameraHelp")));
        viewBar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        viewBar.getStyleClass().add("slicer-toolbar");
        plateStrip.setId("print-plates");
        var plateScroll = new ScrollPane(plateStrip);
        plateScroll.setFitToHeight(true);
        plateScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        plateScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        var plateBar = new BorderPane(plateScroll);
        plateBar.setRight(button(
                translations.text("print.addPlate"),
                "print-add-plate",
                () -> commit(setup().addPlate("plate-"
                        + document.snapshot().document().uuidGenerator().next()))));
        plateBar.getStyleClass().add("slicer-toolbar");
        placementHint.setText(translations.text("print.dragHint") + " " + translations.text("print.rotationGizmoHelp"));
        placementHint.setWrapText(true);
        placementHint.getStyleClass().add("slicer-hint");
        preview.setTop(new VBox(8, plateBar, viewBar));
        preview.setBottom(placementHint);
        setCenter(preview);
        layer.setId("print-layer");
        layer.setBlockIncrement(1);
        layer.valueProperty().addListener((o, a, b) -> draw());
        var filters = new TilePane(12, 8);
        filters.setPrefColumns(4);
        for (var role : GcodeJob.Role.values()) {
            var check = new CheckBox(translations.text("print.role." + role.name()));
            check.setId("print-role-" + role.name().toLowerCase(Locale.ROOT));
            UiHelp.install(check, translations);
            check.setSelected(role != GcodeJob.Role.TRAVEL);
            check.setOnAction(e -> draw());
            var swatch = new javafx.scene.shape.Rectangle(10, 10, color(role));
            check.setGraphic(swatch);
            check.setMinWidth(155);
            roles.put(role, check);
            filters.getChildren().add(check);
        }
        progress.setPrefSize(18, 18);
        progress.setMaxSize(18, 18);
        status.setId("print-status");
        status.setWrapText(true);
        validationLabel.setId("print-validation");
        validationLabel.setWrapText(true);
        var legend = new TitledPane(translations.text("print.toolpathLegend"), filters);
        legend.setExpanded(false);
        legend.setId("print-legend");
        layerLabel.getStyleClass().add("slicer-section-title");
        previewControls.getChildren().addAll(layerLabel, layer, legend);
        previewControls.getStyleClass().add("slicer-preview-controls");
        previewControls.managedProperty().bind(previewControls.visibleProperty());
        var jobStatus = new VBox(4, status, validationLabel);
        jobStatus.setMinWidth(0);
        status.setMinWidth(0);
        validationLabel.setMinWidth(0);
        slice.setMinWidth(Region.USE_PREF_SIZE);
        cancel.setMinWidth(Region.USE_PREF_SIZE);
        HBox.setHgrow(jobStatus, Priority.ALWAYS);
        var actions = new HBox(12, progress, jobStatus, cancel, sliceAll, slice);
        actions.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        actions.getStyleClass().add("slicer-status-bar");
        slice.getStyleClass().add("slicer-primary-action");
        slice.setText(translations.text("print.slicePlate"));
        availability.statuses.addListener(
                (javafx.collections.MapChangeListener<String, SlicerAvailability.Status>) change -> {
                    if (!closed) updateJob();
                });
        availability.refresh();
        setBottom(new VBox(8, previewControls, actions));
        BorderPane.setMargin(tabs, new Insets(0, 0, 0, 8));
        fields.forEach((key, field) -> workspace.add(key, field::getText, field::setText));
        for (var choice : List.of(bodies, instances, materials, supportIds, supportOwners, targetPlate))
            workspace.add(choice.getId(), choice::getValue, value -> choice.setValue(value.isEmpty() ? null : value));
        workspace.add(
                "print-backend",
                () -> backends.getValue() == null ? "" : backends.getValue().id(),
                value -> backends.setValue(backends.getItems().stream()
                        .filter(b -> b.id().equals(value))
                        .findFirst()
                        .orElse(null)));
        workspace.add(
                "print-support-role",
                () -> supportRole.getValue().name(),
                value -> supportRole.setValue(SupportVolume.Role.valueOf(value)));
        workspace.add(
                "print-rotation-space",
                () -> rotationSpace.getValue().name(),
                value -> rotationSpace.setValue(PrintRotationGizmo.Space.valueOf(value)));
        var checks = new ArrayList<CheckBox>(roles.values());
        checks.addAll(List.of(autoSupports, bedOnly, overwrite, showRotationGizmos));
        for (var check : checks)
            workspace.add(
                    check.getId(),
                    () -> Boolean.toString(check.isSelected()),
                    value -> check.setSelected(Boolean.parseBoolean(value)));
        workspace.add(
                "print-view-3d",
                () -> Boolean.toString(threeDimensional),
                value -> viewMode.selectToggle(Boolean.parseBoolean(value) ? solidView : topView));
        workspace.add(
                "print-tab",
                () -> Integer.toString(tabs.getSelectionModel().getSelectedIndex()),
                value -> tabs.getSelectionModel().select(Integer.parseInt(value)));
        workspace.add(
                "print-legend",
                () -> Boolean.toString(legend.isExpanded()),
                value -> legend.setExpanded(Boolean.parseBoolean(value)));
        for (String id : List.of("layer-height", "walls", "infill", "threshold", "gap", "interfaces", "brim", "raft")) {
            var field = fields.get("print-" + id);
            field.setOnAction(event -> saveProcess());
            field.focusedProperty().addListener((o, before, focused) -> {
                if (!focused) saveProcess();
            });
        }
        backends.setOnAction(event -> saveProcess());
        materials.setOnAction(event -> saveProcess());
        autoSupports.setOnAction(event -> saveProcess());
        bedOnly.setOnAction(event -> saveProcess());
        workspace.initialize();
        document.printWorkspace(workspace::capture);
        jobs.onChanged(this::updateJob);
        updateJob();
    }

    private Tab tab(String name, VBox content) {
        content.setPadding(new Insets(8));
        for (var child : content.getChildren()) {
            if (child instanceof Control control) control.setMaxWidth(Double.MAX_VALUE);
        }
        content.getChildren().stream().filter(n -> n instanceof Label).forEach(n -> ((Label) n).setWrapText(true));
        var scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        var tab = new Tab(translations.text("tab." + name), scroll);
        tab.setClosable(false);
        tab.setId("print-tab-" + name.toLowerCase(Locale.ROOT));
        return tab;
    }

    private VBox row(String id, String label, String initial) {
        return row(id, label, initial, true);
    }

    private VBox placementCoordinates(boolean rotation) {
        var coordinates = new HBox(8);
        for (String axis : List.of("x", "y", "z")) {
            String id = "print-" + (rotation ? "rotate-" : "") + axis;
            String caption =
                    rotation ? translations.text("print.rotation", axis.toUpperCase(Locale.ROOT)) : axis + " (mm)";
            var column = row(id, caption, "0", false);
            column.setMinWidth(0);
            column.setPrefWidth(0);
            HBox.setHgrow(column, Priority.ALWAYS);
            fields.get(id).setMinWidth(0);
            fields.get(id).setPrefColumnCount(5);
            coordinates.getChildren().add(column);
        }
        var hint = new Label(translations.text("print.property." + (rotation ? "rotation" : "position")));
        hint.setWrapText(true);
        hint.getStyleClass().add("slicer-property-help");
        return new VBox(4, coordinates, hint);
    }

    private VBox row(String id, String label, String initial, boolean showHelp) {
        java.util.function.Supplier<ParameterSet> parameters =
                () -> document.snapshot().document().parameters();
        var unit = id.startsWith("print-rotate-") || id.equals("print-threshold")
                ? ParameterUnit.DEG
                : Set.of("print-face", "print-walls", "print-infill", "print-interfaces", "print-raft")
                                .contains(id)
                        ? ParameterUnit.NUMBER
                        : ParameterUnit.MM;
        var field = Set.of("print-support-name", "print-output").contains(id)
                ? FormulaField.text(initial, parameters)
                : FormulaField.number(initial, parameters, unit);
        field.localized(translations);
        field.setId(id);
        fields.put(id, field);
        var caption = new Label(label);
        caption.setWrapText(true);
        String helpKey = id.startsWith("print-rotate-")
                ? "rotation"
                : Set.of("print-x", "print-y", "print-z").contains(id)
                        ? "position"
                        : id.startsWith("print-support-") ? "support" : id.substring(6);
        String help = translations.textOrDefault("print.property." + helpKey, "");
        var row = new VBox(4, caption, field);
        if (!help.isBlank()) {
            caption.setTooltip(UiHelp.tooltip(help));
            var hint = new Label(help);
            hint.setWrapText(true);
            hint.getStyleClass().add("slicer-property-help");
            if (showHelp) row.getChildren().add(hint);
            field.setAccessibleHelp(help);
        }
        return row;
    }

    private Button button(String title, String id, Runnable action) {
        var button = new Button(title);
        button.setId(id);
        button.setMaxWidth(Double.MAX_VALUE);
        UiHelp.install(button, translations);
        button.setOnAction(e -> {
            e.consume();
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
            double value = field.value();
            if (!Double.isFinite(value)) throw new NumberFormatException();
            field.setStyle("");
            return value;
        } catch (IllegalArgumentException failure) {
            field.setStyle("-fx-border-color: #d04a3c;");
            throw new IllegalArgumentException(id.replace("print-", "") + ": " + failure.getMessage(), failure);
        }
    }

    private int integer(String id) {
        double value = number(id);
        if (value != Math.rint(value)) throw new IllegalArgumentException(translations.text("print.integer", id));
        return Math.toIntExact((long) value);
    }

    private PrintSetup setup() {
        return document.snapshot().document().printSetup();
    }

    private BodyInstance selected() {
        return setup().instances().stream()
                .filter(i -> i.id().equals(instances.getValue()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(translations.text("gui.choose_a_build_instance")));
    }

    private void commit(PrintSetup setup) {
        document.printSetup(setup);
    }

    private void addInstance() {
        String body = Objects.requireNonNull(bodies.getValue(), translations.text("gui.choose_a_rebuilt_cad_body"));
        var mesh = document.build().bodies().stream()
                .filter(b -> b.id().equals(body))
                .findFirst()
                .orElseThrow()
                .mesh();
        var bounds = MeshGeometry.bounds(mesh);
        var all = new ArrayList<>(setup().instances());
        String id = "part-" + document.snapshot().document().uuidGenerator().next();
        all.add(new BodyInstance(
                id,
                body,
                Transform.translation(
                        plateMinX + (plateWidth - (bounds.maxX() - bounds.minX())) / 2 - bounds.minX(),
                        plateMinY + (plateDepth - (bounds.maxY() - bounds.minY())) / 2 - bounds.minY(),
                        -bounds.minZ())));
        commit(setup().withInstances(all));
        instances.setValue(id);
    }

    private void autoArrange() {
        if (model == null || !document.canExport() || jobs.running()) return;
        cancelDrag();
        var arranged = PrintArrangement.arrange(model, 5);
        if (arranged.isEmpty()) {
            status.setText(translations.text("print.autoArrangeFailed"));
            return;
        }
        toolpathPreview.setSelected(false);
        var next = setup().withInstances(arranged.get());
        if (!next.equals(setup())) commit(next);
    }

    private void applyPlacement() {
        var selected = selected();
        var transform = Transform.translation(number("print-x"), number("print-y"), number("print-z"))
                .compose(Transform.rotation(
                        number("print-rotate-x"), number("print-rotate-y"), number("print-rotate-z")));
        replace(new BodyInstance(
                selected.id(),
                Objects.requireNonNull(bodies.getValue(), translations.text("gui.choose_a_cad_body")),
                transform));
    }

    private void replace(BodyInstance selected) {
        commit(setup().withInstances(setup().instances().stream()
                .map(i -> i.id().equals(selected.id()) ? selected : i)
                .toList()));
    }

    private void removeInstance() {
        var id = selected().id();
        commit(setup().withInstances(setup().instances().stream()
                        .filter(i -> !i.id().equals(id))
                        .toList())
                .withSupports(setup().supports().stream()
                        .filter(support -> !support.instanceId().equals(id))
                        .toList()));
    }

    private void layFace() {
        var selected = selected();
        var mesh = document.build().bodies().stream()
                .filter(b -> b.id().equals(selected.bodyId()))
                .findFirst()
                .orElseThrow()
                .mesh();
        replace(new BodyInstance(
                selected.id(),
                selected.bodyId(),
                MeshGeometry.layOnFace(
                        mesh,
                        Objects.equals(selectedFaceBody, selected.bodyId()) ? selectedFaceIndex : 0,
                        selected.transform())));
    }

    void selectedFace(String body, int face) {
        if (face >= 0) {
            bodies.setValue(body);
            selectedFaceIndex = face;
            selectedFaceBody = body;
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
            fields.get("print-" + names[i])
                    .setText(java.math.BigDecimal.valueOf(values[i])
                            .setScale(4, java.math.RoundingMode.HALF_UP)
                            .stripTrailingZeros()
                            .toPlainString());
    }

    private void applyProcess() {
        var backend = Objects.requireNonNull(
                backends.getValue(), translations.text("gui.choose_an_installed_slicer_backend"));
        var process = new ProcessSettings(
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
        var next = setup().withProfile(
                        backend.id(),
                        setup().printerId(),
                        Objects.requireNonNull(materials.getValue(), translations.text("gui.choose_a_material")),
                        process);
        if (!next.equals(setup())) commit(next);
    }

    private void applySupport() {
        var volume = new SupportVolume(
                fields.get("print-support-name").evaluatedText(),
                Objects.requireNonNull(supportOwners.getValue(), translations.text("gui.choose_an_attached_instance")),
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
        String id =
                Objects.requireNonNull(supportIds.getValue(), translations.text("gui.choose_a_support_instruction"));
        commit(setup().withSupports(setup().supports().stream()
                .filter(s -> !s.id().equals(id))
                .toList()));
    }

    private void loadSupport() {
        setup().supports().stream()
                .filter(s -> s.id().equals(supportIds.getValue()))
                .findFirst()
                .ifPresent(s -> {
                    fields.get("print-support-name").setText(s.id());
                    supportOwners.setValue(s.instanceId());
                    supportRole.setValue(s.role());
                    String[] names = {"x", "y", "z", "width", "depth", "height"};
                    double[] values = {s.x(), s.y(), s.z(), s.width(), s.depth(), s.height()};
                    for (int i = 0; i < names.length; i++)
                        fields.get("print-support-" + names[i])
                                .setText(java.math.BigDecimal.valueOf(values[i])
                                        .setScale(4, java.math.RoundingMode.HALF_UP)
                                        .stripTrailingZeros()
                                        .toPlainString());
                });
    }

    private void slice() {
        slice(false);
    }

    private void slice(boolean allPlates) {
        if (!availability.working(setup().backendId()))
            throw new IllegalStateException(availability.text(setup().backendId()));
        if (!document.canExport())
            throw new IllegalStateException(
                    translations.text("gui.slice_requires_a_complete_successful_rebuild_at_the_timeline_end"));
        SlicerBackend backend;
        try {
            backend = slicerSettings.backend(setup().backendId());
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(translations.text("slicer.settings.failed", failure.getMessage()), failure);
        }
        applyProcess();
        var snapshot = document.snapshot().document();
        var destination = Path.of(fields.get("print-output").evaluatedText());
        var future = allPlates
                ? jobs.startAll(snapshot, backend, null, batchDestinations(destination), overwrite.isSelected())
                : jobs.start(snapshot, backend, null, destination, overwrite.isSelected());
        tabs.getSelectionModel().select(3);
        future.exceptionally(failure -> null);
    }

    private Map<String, Path> batchDestinations(Path destination) {
        var outputs = new LinkedHashMap<String, Path>();
        var plates = setup().plates();
        Path project = document.path() == null ? Path.of("Untitled.vxb") : document.path();
        for (int i = 0; i < plates.size(); i++) {
            var plate = plates.get(i);
            outputs.put(
                    plate.id(),
                    destination.resolveSibling(jobDestination(project, plateName(plate.id()), i + 1, plates.size())
                            .getFileName()));
        }
        return outputs;
    }

    void documentChanged() {
        if (closed) return;
        var snapshot = document.snapshot().document();
        if (!workspace.belongsTo(snapshot.uuidGenerator())) {
            lastSetup = null;
            lastBuild = null;
            outputDestination = null;
        }
        updating = true;
        var pendingWorkspace = workspace.pending(snapshot.uuidGenerator());
        updating = false;
        if (dragSource != null && !DocumentCodec.fingerprint(snapshot).equals(dragRevision)) cancelDrag();
        if (!Objects.equals(outputProject, document.path())) {
            outputProject = document.path();
        }
        updateOutputDestination();
        var printer = PrintProfile.resolve(setup()).printer();
        plateWidth =
                printer.bed().stream().mapToDouble(PrintProfile.Point::x).max().orElse(200)
                        - printer.bed().stream()
                                .mapToDouble(PrintProfile.Point::x)
                                .min()
                                .orElse(0);
        plateDepth =
                printer.bed().stream().mapToDouble(PrintProfile.Point::y).max().orElse(200)
                        - printer.bed().stream()
                                .mapToDouble(PrintProfile.Point::y)
                                .min()
                                .orElse(0);
        plateMinX =
                printer.bed().stream().mapToDouble(PrintProfile.Point::x).min().orElse(0);
        plateMinY =
                printer.bed().stream().mapToDouble(PrintProfile.Point::y).min().orElse(0);
        updatePrinterDetails();
        plateSize.setText(String.format(Locale.ROOT, "%.1f \u00d7 %.1f mm", plateWidth, plateDepth));
        jobs.documentChanged(snapshot);
        if (document.rebuilding()) jobs.invalidateGeometry();
        boolean refreshNames = lastBuild != document.build() || !setup().equals(lastSetup);
        updating = true;
        try {
            if (lastBuild != document.build()) {
                String selected = bodies.getValue();
                bodies.getSelectionModel().clearSelection();
                bodies.getItems()
                        .setAll(document.build().bodies().stream()
                                .map(DocumentController.Body::id)
                                .toList());
                if (!bodies.getItems().isEmpty())
                    bodies.getSelectionModel()
                            .select(Math.max(0, bodies.getItems().indexOf(selected)));
                lastBuild = document.build();
            }
            if (!setup().equals(lastSetup)) {
                rebuildPlateStrip();
                String selected = instances.getValue();
                instances
                        .getItems()
                        .setAll(setup().instances().stream()
                                .map(BodyInstance::id)
                                .toList());
                instances.setValue(
                        instances.getItems().contains(selected)
                                ? selected
                                : instances.getItems().stream().findFirst().orElse(null));
                supportOwners.getItems().setAll(instances.getItems());
                if (!supportOwners.getItems().contains(supportOwners.getValue()))
                    supportOwners.setValue(instances.getValue());
                supportIds
                        .getItems()
                        .setAll(setup().supports().stream()
                                .map(SupportVolume::id)
                                .toList());
                if (!supportIds.getItems().contains(supportIds.getValue()))
                    supportIds.setValue(
                            supportIds.getItems().stream().findFirst().orElse(null));
                materials.setValue(setup().materialId());
                backends.setValue(backends.getItems().stream()
                        .filter(b -> b.id().equals(setup().backendId()))
                        .findFirst()
                        .orElse(null));
                var p = setup().process();
                String[] names = {"layer-height", "walls", "infill", "threshold", "gap", "interfaces", "brim", "raft"};
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
                    fields.get("print-" + names[i])
                            .setText(java.math.BigDecimal.valueOf(values[i])
                                    .setScale(4, java.math.RoundingMode.HALF_UP)
                                    .stripTrailingZeros()
                                    .toPlainString());
                supportsEnabled.setSelected(!setup().supportsDisabled());
                autoSupports.setSelected(p.automaticSupports());
                bedOnly.setSelected(p.supportsOnBedOnly());
                lastSetup = setup();
                loadPlacement();
                loadSupport();
            }
            workspace.restore(snapshot.uuidGenerator(), setup().workspaceSettings(), pendingWorkspace);
            if (refreshNames) {
                // Body renames keep instance IDs unchanged, so JavaFX does not refresh their cells.
                for (var choice : List.of(bodies, instances, supportOwners)) {
                    choice.setCellFactory(list -> namedCell(choice));
                    choice.setButtonCell(namedCell(choice));
                }
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
                    translations.text("gui.finish_rebuilding_the_complete_document_before_preparing_a_print"));
        } else if (!revision.equals(validatedRevision)) {
            validatedRevision = revision;
            validation = null;
            try {
                var meshes = new LinkedHashMap<String, MeshData>();
                document.build().bodies().forEach(b -> meshes.put(b.id(), b.mesh()));
                model = new ManufacturingModel(snapshot.id(), revision, setup(), PrintProfile.resolve(setup()), meshes);
                var captured = model;
                long ticket = ++validationSequence;
                if (validationTask != null) validationTask.cancel(false);
                validationLabel.setText(translations.text("gui.validating_build_placement_and_meshes"));
                validationTask = validator.submit(() -> {
                    var result = PrintValidation.validate(captured);
                    Platform.runLater(() -> {
                        if (closed || ticket != validationSequence) return;
                        validation = result;
                        validationLabel.setText(
                                result.valid()
                                        ? translations.text("gui.print_setup_valid_millimeters")
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
        updatePreviewControls();
        boolean placementUnavailable =
                model == null || model.instances().isEmpty() || !document.canExport() || jobs.running() || orienting;
        autoArrange.setDisable(placementUnavailable);
        orientAll.setDisable(placementUnavailable);
        backendStatus.showBackend(
                backends.getValue() == null ? null : backends.getValue().id());
        slice.setDisable(!availability.working(setup().backendId())
                || !document.canExport()
                || jobs.running()
                || validation == null
                || !validation.valid());
        sliceAll.setDisable(!availability.working(setup().backendId())
                || !document.canExport()
                || jobs.running()
                || setup().plates().stream().allMatch(plate -> plate.instances().isEmpty()));
        cancel.setDisable(!jobs.running());
        cancel.setVisible(jobs.running());
        cancel.setManaged(jobs.running());
        progress.setVisible(jobs.running());
        status.setText(translations.text("print.state." + jobs.state().name())
                + ": "
                + (jobs.state() == PrintController.State.IDLE
                        ? translations.text("gui.ready_to_slice")
                        : jobs.state() == PrintController.State.SUCCEEDED
                                ? translations.text("print.jobReady")
                                : jobs.message()));
        status.setTooltip(UiHelp.tooltip(jobs.message()));
        diagnostics.setText(String.join("\n", jobs.diagnostics()));
        var result = jobs.result();
        if (result != displayed) {
            displayed = result;
            toolpathPreview.setSelected(result != null);
            updatePreviewControls();
            if (result == null) {
                previewLayers = List.of();
                summary.setText("");
            } else {
                var job = result.job();
                previewLayers = job.segments().stream()
                        .filter(GcodeJob.Segment::deposits)
                        .map(s -> Math.rint(s.z1() * 1e6) / 1e6)
                        .distinct()
                        .sorted()
                        .toList();
                summary.setText(job.report().get("printer")
                        + "\n"
                        + translations.text(
                                "print.summary",
                                job.report().get("material"),
                                job.report().get("nozzle_mm"),
                                job.modelLayers().size(),
                                job.estimatedTime().orElse(translations.text("print.unknown")),
                                (job.filamentMm().isPresent()
                                        ? String.format(
                                                Locale.ROOT,
                                                "%.1f mm",
                                                job.filamentMm().getAsDouble())
                                        : translations.text("print.unknown"))));
                for (var entry : roles.entrySet())
                    entry.getValue().setDisable(job.segments().stream().noneMatch(s -> s.role() == entry.getKey()));
            }
            layer.setMax(Math.max(0, previewLayers.size() - 1));
            layer.setValue(layer.getMax());
            layer.setDisable(previewLayers.isEmpty());
        }
        draw();
    }

    void setDark(boolean value) {
        dark = value;
        draw();
    }

    private void draw() {
        selectedObjectTooltip.hide();
        selectedObjectName.setVisible(false);
        placementRaster = null;
        var graphics = canvas.getGraphicsContext2D();
        localGizmoAxes.setVisible(false);
        double w = canvas.getWidth(), h = canvas.getHeight();
        graphics.setFill(dark ? Color.web("#182028") : Color.web("#edf1f4"));
        graphics.fillRect(0, 0, w, h);
        if (rotationDrag == null) rotationRings = List.of();
        if (w < 1 || h < 1) return;
        double extentX = threeDimensional ? Math.hypot(plateWidth, plateDepth) : plateWidth;
        double extentY = threeDimensional ? extentX : plateDepth;
        double scale = Math.max(0.001, Math.min((w - 48) / extentX, (h - 48) / extentY)) * camera().zoom();
        double ox = w / 2 - projectX(plateMinX + plateWidth / 2, plateMinY + plateDepth / 2) * scale + camera().panX();
        double oy =
                h / 2 + projectY(plateMinX + plateWidth / 2, plateMinY + plateDepth / 2, 0) * scale + camera().panY();
        viewScale = scale;
        viewOx = ox;
        viewOy = oy;
        graphics.setStroke(dark ? Color.web("#3b4651") : Color.web("#bac4cc"));
        graphics.setLineWidth(1);
        var hardware = PrintProfile.resolve(setup()).printer();
        graphics.save();
        bedPath(hardware.bed(), ox, oy, scale);
        graphics.clip();
        double grid = Math.max(10, Math.ceil(Math.max(plateWidth, plateDepth) / 100 / 10) * 10);
        for (double n = plateMinX; n <= plateMinX + plateWidth; n += grid)
            line(ox, oy, scale, n, plateMinY, 0, n, plateMinY + plateDepth, 0);
        for (double n = plateMinY; n <= plateMinY + plateDepth; n += grid)
            line(ox, oy, scale, plateMinX, n, 0, plateMinX + plateWidth, n, 0);
        graphics.restore();
        bedPath(hardware.bed(), ox, oy, scale);
        graphics.stroke();
        for (var zone : hardware.excludedZones()) {
            bedPath(zone, ox, oy, scale);
            graphics.setFill(Color.color(0.85, 0.25, 0.15, 0.3));
            graphics.fill();
        }
        graphics.setFill(dark ? Color.LIGHTGRAY : Color.DARKSLATEGRAY);
        graphics.fillText("0,0", ox, oy + 16);
        if (toolpathPreview.isSelected() && displayed != null && !previewLayers.isEmpty()) {
            int index = Math.min(previewLayers.size() - 1, (int) Math.round(layer.getValue()));
            double z = previewLayers.get(index);
            layerLabel.setText(translations.text(
                    "print.layer", index + 1, previewLayers.size(), String.format(Locale.ROOT, "%.3f", z)));
            for (var segment : displayed.job().segments())
                if ((threeDimensional ? segment.z1() <= z + 1e-5 : Math.abs(segment.z1() - z) < 1e-5)
                        && roles.get(segment.role()).isSelected()) {
                    graphics.setStroke(color(segment.role()));
                    graphics.setLineWidth(segment.deposits() ? Math.max(1, segment.width() * scale) : 0.5);
                    line(
                            ox,
                            oy,
                            scale,
                            segment.x0(),
                            segment.y0(),
                            segment.z0(),
                            segment.x1(),
                            segment.y1(),
                            segment.z1());
                }
        } else {
            layerLabel.setText(translations.text("gui.build_placement_slice_to_preview_actual_toolpaths"));
            if (model == null) return;
            renderPlacement(ox, oy, scale, (int) Math.ceil(w), (int) Math.ceil(h));
            for (var instance : model.instances()) {
                if (!model.meshes().containsKey(instance.bodyId())) continue;
                var shown = dragPreview != null && dragPreview.id().equals(instance.id()) ? dragPreview : instance;
                var mesh = model.placedMesh(shown);
                boolean selected = instance.id().equals(instances.getValue());
                if (selected) {
                    selectedObjectName.setText(instanceName(instance.id()));
                    selectedObjectName.setMaxWidth(Math.max(0, w - 16));
                    selectedObjectName.autosize();
                    double left = Double.POSITIVE_INFINITY, right = Double.NEGATIVE_INFINITY;
                    double top = Double.POSITIVE_INFINITY;
                    for (int t = 0; t < mesh.triangleCount(); t++) {
                        for (int c = 0; c < 3; c++) {
                            int v = mesh.vertexIndex(t, c);
                            double px = ox + projectX(mesh.coordinate(v, 0), mesh.coordinate(v, 1)) * scale;
                            double py = oy
                                    - projectY(mesh.coordinate(v, 0), mesh.coordinate(v, 1), mesh.coordinate(v, 2))
                                            * scale;
                            left = Math.min(left, px);
                            right = Math.max(right, px);
                            top = Math.min(top, py);
                        }
                    }
                    if (Double.isFinite(top)) {
                        selectedObjectName.relocate(
                                Math.max(
                                        8,
                                        Math.min(
                                                w - selectedObjectName.getWidth() - 8,
                                                (left + right - selectedObjectName.getWidth()) / 2)),
                                Math.max(
                                        8,
                                        Math.min(
                                                h - selectedObjectName.getHeight() - 8,
                                                top - selectedObjectName.getHeight() - 8)));
                        selectedObjectName.setVisible(true);
                    }
                }
            }
            for (var support : model.setup().supports()) {
                var owner = model.instances().stream()
                        .filter(i -> i.id().equals(support.instanceId()))
                        .findFirst();
                if (owner.isEmpty()) continue;
                var box = MeshGeometry.transform(
                        MeshGeometry.box(support),
                        dragPreview != null
                                        && dragPreview.id().equals(owner.get().id())
                                ? dragPreview.transform()
                                : owner.get().transform());
                graphics.setStroke(
                        support.role() == SupportVolume.Role.BLOCKER
                                ? Color.CRIMSON
                                : support.role() == SupportVolume.Role.ENFORCER ? Color.MEDIUMPURPLE : Color.LIMEGREEN);
                graphics.setLineWidth(2);
                for (int t = 0; t < box.triangleCount(); t++)
                    for (int c = 0; c < 3; c++) {
                        int a = box.vertexIndex(t, c), b = box.vertexIndex(t, (c + 1) % 3);
                        line(
                                ox,
                                oy,
                                scale,
                                box.coordinate(a, 0),
                                box.coordinate(a, 1),
                                box.coordinate(a, 2),
                                box.coordinate(b, 0),
                                box.coordinate(b, 1),
                                box.coordinate(b, 2));
                    }
            }
            drawRotationGizmo();
        }
    }

    private PrintDepthBuffer.Point projected(double x, double y, double z, double ox, double oy, double scale) {
        return new PrintDepthBuffer.Point(
                ox + projectX(x, y) * scale, oy - projectY(x, y, z) * scale, camera().depth(x, y, z));
    }

    private void renderPlacement(double ox, double oy, double scale, int width, int height) {
        var raster = new PrintDepthBuffer(width, height);
        int palette = 0;
        var all = model.instances();
        for (int owner = 0; owner < all.size(); owner++) {
            var instance = all.get(owner);
            if (!model.meshes().containsKey(instance.bodyId())) continue;
            var shown = dragPreview != null && dragPreview.id().equals(instance.id()) ? dragPreview : instance;
            var mesh = model.placedMesh(shown);
            var color = instance.id().equals(instances.getValue())
                    ? Color.web("#4299c7")
                    : Color.hsb(195 + (palette++ * 48) % 160, 0.35, 0.72);
            int rgb = 0xff000000
                    | ((int) Math.round(color.getRed() * 255) << 16)
                    | ((int) Math.round(color.getGreen() * 255) << 8)
                    | (int) Math.round(color.getBlue() * 255);
            var points = new PrintDepthBuffer.Point[mesh.vertexCount()];
            for (int v = 0; v < points.length; v++)
                points[v] =
                        projected(mesh.coordinate(v, 0), mesh.coordinate(v, 1), mesh.coordinate(v, 2), ox, oy, scale);
            for (int t = 0; t < mesh.triangleCount(); t++)
                raster.triangle(
                        points[mesh.vertexIndex(t, 0)],
                        points[mesh.vertexIndex(t, 1)],
                        points[mesh.vertexIndex(t, 2)],
                        rgb,
                        owner);
        }
        // Draw edges only after every surface has contributed to the shared depth buffer.
        for (var instance : all) {
            var body = document.build().bodies().stream()
                    .filter(b -> b.id().equals(instance.bodyId()))
                    .findFirst();
            if (body.isEmpty()) continue;
            var shown = dragPreview != null && dragPreview.id().equals(instance.id()) ? dragPreview : instance;
            for (var edge : body.get().edges().edges()) {
                if (!edge.boundary()) continue;
                var a = shown.transform()
                        .apply(edge.a().x(), edge.a().y(), edge.a().z());
                var b = shown.transform()
                        .apply(edge.b().x(), edge.b().y(), edge.b().z());
                raster.edge(
                        projected(a[0], a[1], a[2], ox, oy, scale),
                        projected(b[0], b[1], b[2], ox, oy, scale),
                        0xff000000);
            }
            if (instance.id().equals(instances.getValue())) {
                var bounds = MeshGeometry.bounds(model.placedMesh(shown));
                var a = projected(bounds.minX(), bounds.minY(), 0, ox, oy, scale);
                var b = projected(bounds.maxX(), bounds.minY(), 0, ox, oy, scale);
                var c = projected(bounds.maxX(), bounds.maxY(), 0, ox, oy, scale);
                var d = projected(bounds.minX(), bounds.maxY(), 0, ox, oy, scale);
                int color = dark ? 0xff9bdcff : 0xff075b91;
                raster.edge(a, b, color);
                raster.edge(b, c, color);
                raster.edge(c, d, color);
                raster.edge(d, a, color);
            }
        }
        var image = new javafx.scene.image.WritableImage(width, height);
        image.getPixelWriter()
                .setPixels(
                        0,
                        0,
                        width,
                        height,
                        javafx.scene.image.PixelFormat.getIntArgbInstance(),
                        raster.pixels(),
                        0,
                        width);
        canvas.getGraphicsContext2D().drawImage(image, 0, 0);
        placementRaster = raster;
    }

    private void drawRotationGizmo() {
        if (!showRotationGizmos.isSelected() || !document.canExport() || jobs.running()) return;
        var instance = model.instances().stream()
                .filter(i -> i.id().equals(instances.getValue()))
                .findFirst();
        if (instance.isEmpty() || !model.meshes().containsKey(instance.get().bodyId())) return;
        if (rotationDrag == null) {
            var shown = dragPreview == null ? instance.get() : dragPreview;
            var bounds = MeshGeometry.bounds(model.placedMesh(shown));
            var center = new javafx.geometry.Point3D(
                    (bounds.minX() + bounds.maxX()) / 2,
                    (bounds.minY() + bounds.maxY()) / 2,
                    (bounds.minZ() + bounds.maxZ()) / 2);
            double radius = Math.max(
                    44 / viewScale,
                    Math.sqrt(Math.pow(bounds.maxX() - bounds.minX(), 2)
                                            + Math.pow(bounds.maxY() - bounds.minY(), 2)
                                            + Math.pow(bounds.maxZ() - bounds.minZ(), 2))
                                    / 2
                            + 12 / viewScale);
            var frame = PrintRotationGizmo.frame(shown.transform(), rotationSpace.getValue());
            rotationRings = Arrays.stream(PrintRotationGizmo.Axis.values())
                    .filter(axis -> threeDimensional || axis == PrintRotationGizmo.topAxis(frame))
                    .map(axis ->
                            PrintRotationGizmo.ring(axis, center, radius, camera(), viewOx, viewOy, viewScale, frame))
                    .toList();
        }
        var graphics = canvas.getGraphicsContext2D();
        graphics.save();
        for (var ring : rotationRings) {
            graphics.setStroke(rotationColor(ring.axis()));
            graphics.setLineWidth(
                    (rotationDrag != null && rotationDrag.axis() == ring.axis()) || hoveredRotation == ring.axis()
                            ? 3.5
                            : 1.8);
            double[] x = new double[73], y = new double[73];
            for (int i = 0; i <= 72; i++) {
                var point = ring.point(i * Math.PI / 36);
                x[i] = point.getX();
                y[i] = point.getY();
            }
            graphics.strokePolyline(x, y, x.length);
            var handle = ring.point(ring.handleAngle());
            graphics.setFill(dark ? Color.web("#25262a") : Color.WHITE);
            graphics.fillOval(handle.getX() - 10, handle.getY() - 10, 20, 20);
            graphics.strokeOval(handle.getX() - 10, handle.getY() - 10, 20, 20);
            graphics.setFill(rotationColor(ring.axis()));
            graphics.setTextAlign(javafx.scene.text.TextAlignment.CENTER);
            graphics.fillText(ring.axis().name(), handle.getX(), handle.getY() + 4);
        }
        if (rotationDrag != null && !rotationRings.isEmpty()) {
            var center = rotationRings.getFirst().center();
            graphics.setFill(dark ? Color.web("#25262a") : Color.WHITE);
            graphics.fillRoundRect(center.getX() - 42, center.getY() - 12, 84, 24, 6, 6);
            graphics.setFill(dark ? Color.WHITE : Color.web("#27282e"));
            graphics.fillText(
                    rotationDrag.axis().name() + " " + String.format(Locale.ROOT, "%.1f°", rotationDegrees),
                    center.getX(),
                    center.getY() + 4);
        }
        graphics.restore();
        if (!rotationRings.isEmpty()) {
            double top = rotationRings.stream()
                    .mapToDouble(ring -> ring.center().getY()
                            - Math.hypot(ring.u().getY(), ring.v().getY()))
                    .min()
                    .orElse(0);
            selectedObjectName.setLayoutY(
                    Math.max(8, Math.min(selectedObjectName.getLayoutY(), top - selectedObjectName.getHeight() - 12)));
            double bottom = rotationRings.stream()
                    .mapToDouble(ring -> ring.center().getY()
                            + Math.hypot(ring.u().getY(), ring.v().getY()))
                    .max()
                    .orElse(0);
            localGizmoAxes.setStyle("-fx-padding: 4 8; -fx-background-radius: 4; -fx-background-color: "
                    + (dark ? "#25262a;" : "white;"));
            localGizmoAxes.autosize();
            localGizmoAxes.relocate(
                    Math.max(
                            0,
                            Math.min(
                                    canvas.getWidth() - localGizmoAxes.getWidth(),
                                    rotationRings.getFirst().center().getX() - localGizmoAxes.getWidth() / 2)),
                    Math.max(0, Math.min(canvas.getHeight() - localGizmoAxes.getHeight(), bottom + 12)));
            localGizmoAxes.setVisible(true);
        }
    }

    private static Color rotationColor(PrintRotationGizmo.Axis axis) {
        return switch (axis) {
            case X -> Color.web("#e46969");
            case Y -> Color.web("#57ad74");
            case Z -> Color.web("#728ef5");
        };
    }

    private PrintRotationGizmo.Hit rotationHit(double x, double y) {
        if (!showRotationGizmos.isSelected() || toolpathPreview.isSelected() || !document.canExport() || jobs.running())
            return null;
        var pointer = new javafx.geometry.Point2D(x, y);
        return rotationRings.stream()
                .map(ring -> ring.hit(pointer))
                .filter(Objects::nonNull)
                .min(Comparator.comparingDouble(PrintRotationGizmo.Hit::distance))
                .orElse(null);
    }

    private void updatePreviewControls() {
        boolean preview = jobs.result() != null && toolpathPreview.isSelected();
        toolpathPreview.setDisable(jobs.result() == null);
        previewControls.setVisible(preview);
        placementHint.setVisible(!preview);
        placementHint.setManaged(!preview);
    }

    private String plateName(String id) {
        for (int i = 0; i < setup().plates().size(); i++)
            if (setup().plates().get(i).id().equals(id)) {
                String name = setup().plates().get(i).name();
                return name.isEmpty() ? translations.text("print.plate", i + 1) : name;
            }
        return id;
    }

    private void rebuildPlateStrip() {
        plateNameEditor = null;
        plateStrip.getChildren().clear();
        var group = new ToggleGroup();
        for (var plate : setup().plates()) {
            var tab = new ToggleButton(
                    plateName(plate.id()) + "  \u00b7  " + plate.instances().size());
            tab.setId("print-plate-" + plate.id());
            tab.setMaxWidth(220);
            tab.setTooltip(UiHelp.tooltip(plateName(plate.id()) + "\n" + translations.text("print.renameHint")));
            tab.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, event -> {
                if (event.getButton() == javafx.scene.input.MouseButton.PRIMARY
                        && event.getClickCount() >= 2
                        && event.getClickCount() % 2 == 0) {
                    renamePlate(plate.id(), tab);
                    event.consume();
                }
            });
            tab.setToggleGroup(group);
            tab.setSelected(plate.id().equals(setup().activePlateId()));
            tab.setOnAction(event -> {
                if (plateNameEditor != null) return;
                if (!plate.id().equals(setup().activePlateId())) {
                    cancelDrag();
                    commit(setup().selectPlate(plate.id()));
                } else tab.setSelected(true);
            });
            var remove = button("\u00d7", "print-remove-plate-" + plate.id(), () -> removePlate(plate.id()));
            remove.setAccessibleText(translations.text("print.removePlate") + " " + plateName(plate.id()));
            remove.setTooltip(UiHelp.tooltip(translations.text("print.removePlate")));
            remove.setDisable(setup().plates().size() == 1);
            tab.setGraphic(remove);
            tab.setContentDisplay(ContentDisplay.RIGHT);
            tab.setOnDragDetected(event -> {
                var drag = tab.startDragAndDrop(javafx.scene.input.TransferMode.MOVE);
                var content = new javafx.scene.input.ClipboardContent();
                content.putString(plate.id());
                drag.setContent(content);
                event.consume();
            });
            tab.setOnDragOver(event -> {
                if (event.getGestureSource() instanceof ToggleButton source
                        && source.getToggleGroup() == group
                        && event.getDragboard().hasString()
                        && !plate.id().equals(event.getDragboard().getString()))
                    event.acceptTransferModes(javafx.scene.input.TransferMode.MOVE);
                event.consume();
            });
            tab.setOnDragDropped(event -> {
                if (event.getGestureSource() instanceof ToggleButton source && source.getToggleGroup() == group) {
                    commit(PrintPlacement.reorder(setup(), event.getDragboard().getString(), plate.id()));
                    event.setDropCompleted(true);
                }
                event.consume();
            });
            plateStrip.getChildren().add(tab);
        }
        targetPlate
                .getItems()
                .setAll(setup().plates().stream()
                        .map(PrintPlate::id)
                        .filter(id -> !id.equals(setup().activePlateId()))
                        .toList());
        targetPlate.getSelectionModel().selectFirst();
        targetPlate.setDisable(targetPlate.getItems().isEmpty());
    }

    private void removePlate(String id) {
        if (setup().plates().size() == 1) return;
        var removing = setup().selectPlate(id);
        if (!removing.instances().isEmpty() || !removing.supports().isEmpty()) {
            var confirm = new Alert(
                    Alert.AlertType.CONFIRMATION,
                    translations.text("print.removePlateConfirm"),
                    ButtonType.OK,
                    ButtonType.CANCEL);
            confirm.setHeaderText(translations.text("print.removePlate"));
            if (Dialogs.show(confirm, getScene().getWindow(), translations).orElse(ButtonType.CANCEL) != ButtonType.OK)
                return;
        }
        var next = removing.removeActivePlate();
        if (!id.equals(setup().activePlateId())) next = next.selectPlate(setup().activePlateId());
        commit(next);
    }

    private static HBox equalRow(Button... buttons) {
        var row = new HBox(6);
        for (var button : buttons) {
            button.setMinWidth(0);
            button.setPrefWidth(0);
            button.setMaxWidth(Double.MAX_VALUE);
            button.setWrapText(true);
            HBox.setHgrow(button, Priority.ALWAYS);
            row.getChildren().add(button);
        }
        return row;
    }

    private void saveProcess() {
        if (updating || backends.getValue() == null || materials.getValue() == null) return;
        try {
            applyProcess();
        } catch (IllegalArgumentException failure) {
            status.setText(failure.getMessage());
        }
    }

    private void autoOrient() {
        autoOrient(false);
    }

    private void autoOrient(boolean all) {
        if (model == null || !document.canExport() || jobs.running() || orienting) return;
        cancelDrag();
        var captured = setup();
        var sources = all ? captured.instances() : List.of(selected());
        var meshes = model.meshes();
        String revision = DocumentCodec.fingerprint(document.snapshot().document());
        orienting = true;
        updateJob();
        CompletableFuture.supplyAsync(
                        () -> {
                            var oriented = new LinkedHashMap<String, BodyInstance>();
                            for (var source : sources) {
                                var placement =
                                        PrintPlacement.autoOrient(meshes.get(source.bodyId()), source.transform());
                                oriented.put(source.id(), new BodyInstance(source.id(), source.bodyId(), placement));
                            }
                            return captured.withInstances(captured.instances().stream()
                                    .map(source -> oriented.getOrDefault(source.id(), source))
                                    .toList());
                        },
                        validator)
                .whenComplete((next, failure) -> Platform.runLater(() -> {
                    orienting = false;
                    if (closed) return;
                    updateJob();
                    if (!revision.equals(
                            DocumentCodec.fingerprint(document.snapshot().document()))) return;
                    if (failure != null) status.setText(failure.getCause().getMessage());
                    else {
                        toolpathPreview.setSelected(false);
                        if (!next.equals(setup())) commit(next);
                        updatePreviewControls();
                        draw();
                    }
                }));
    }

    private void renamePlate(String plateId, ToggleButton tab) {
        int index = plateStrip.getChildren().indexOf(tab);
        if (index < 0 || plateNameEditor != null) return;
        var editor = new TextField(plateName(plateId));
        editor.setId("print-plate-name");
        editor.setPrefColumnCount(14);
        editor.setMaxWidth(220);
        editor.setTooltip(UiHelp.tooltip(translations.text("print.renameHint")));
        plateNameEditor = editor;
        plateStrip.getChildren().set(index, editor);
        editor.setOnAction(event -> {
            try {
                if (editor.getText().strip().equals(plateName(plateId))) {
                    cancelPlateRename();
                    return;
                }
                var next = setup().renamePlate(plateId, editor.getText());
                if (next.equals(setup())) {
                    cancelPlateRename();
                    return;
                }
                plateNameEditor = null;
                commit(next);
            } catch (IllegalArgumentException failure) {
                editor.setStyle("-fx-border-color: #d04a3c;");
                status.setText(translations.text("print.invalidPlateName"));
                editor.setTooltip(UiHelp.tooltip(translations.text("print.invalidPlateName")));
            }
        });
        editor.focusedProperty().addListener((o, a, focused) -> {
            if (!focused && plateNameEditor == editor) cancelPlateRename();
        });
        editor.requestFocus();
        editor.selectAll();
    }

    boolean cancelPlateRename() {
        if (plateNameEditor == null) return false;
        plateNameEditor = null;
        rebuildPlateStrip();
        updateJob();
        return true;
    }

    private void updateOutputDestination() {
        if (outputProject == null) return;
        var plates = setup().plates();
        for (int i = 0; i < plates.size(); i++) {
            if (!plates.get(i).id().equals(setup().activePlateId())) continue;
            Path destination =
                    jobDestination(outputProject, plateName(plates.get(i).id()), i + 1, plates.size());
            if (!destination.equals(outputDestination)) {
                String current = fields.get("print-output").getText();
                if (current.isBlank() || (outputDestination != null && current.equals(outputDestination.toString())))
                    fields.get("print-output").setText(destination.toString());
                outputDestination = destination;
            }
            return;
        }
    }

    boolean cancelDrag() {
        if (dragSource == null) return false;
        dragSource = null;
        dragPreview = null;
        rotationDrag = null;
        hoveredRotation = null;
        canvas.setCursor(javafx.scene.Cursor.OPEN_HAND);
        draw();
        return true;
    }

    private BodyInstance objectAt(double pointerX, double pointerY) {
        if (model == null || placementRaster == null || toolpathPreview.isSelected()) return null;
        int index = placementRaster.owner(pointerX, pointerY);
        return index < 0 || index >= model.instances().size()
                ? null
                : model.instances().get(index);
    }

    private void installDragging() {
        canvas.setCursor(javafx.scene.Cursor.OPEN_HAND);
        canvas.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_MOVED, event -> {
            var hovered = dragSource == null && !navigating ? objectAt(event.getX(), event.getY()) : null;
            String name = hovered == null ? "" : instanceName(hovered.id());
            if (!name.equals(selectedObjectTooltip.getText())) {
                selectedObjectTooltip.hide();
                Tooltip.uninstall(canvas, selectedObjectTooltip);
                selectedObjectTooltip.setText(name);
                if (!name.isBlank()) Tooltip.install(canvas, selectedObjectTooltip);
            }
        });
        canvas.setOnMouseExited(event -> selectedObjectTooltip.hide());
        canvas.focusedProperty().addListener((o, a, focused) -> {
            if (!focused) {
                cancelDrag();
                navigating = false;
            }
        });
        canvas.setOnMouseMoved(event -> {
            if (dragSource != null || navigating) return;
            var hit = rotationHit(event.getX(), event.getY());
            var axis = hit == null ? null : hit.ring().axis();
            canvas.setCursor(axis == null ? javafx.scene.Cursor.OPEN_HAND : javafx.scene.Cursor.CROSSHAIR);
            if (axis != hoveredRotation) {
                hoveredRotation = axis;
                draw();
            }
        });
        canvas.setOnScroll(event -> {
            if (dragSource != null) return;
            camera().scroll(event.getDeltaY());
            draw();
            event.consume();
        });
        canvas.setOnMousePressed(event -> {
            selectedObjectTooltip.hide();
            canvas.requestFocus();
            navigationX = event.getX();
            navigationY = event.getY();
            primaryNavigation = false;
            if (event.getButton() == javafx.scene.input.MouseButton.SECONDARY
                    || event.getButton() == javafx.scene.input.MouseButton.MIDDLE) {
                cancelDrag();
                navigating = true;
                canvas.setCursor(javafx.scene.Cursor.CLOSED_HAND);
                event.consume();
                return;
            }
            if (event.getButton() != javafx.scene.input.MouseButton.PRIMARY) return;
            event.consume();
            navigating = true;
            primaryNavigation = true;
            if (toolpathPreview.isSelected() || model == null || !document.canExport() || jobs.running()) return;
            var rotation = rotationHit(event.getX(), event.getY());
            if (rotation != null) {
                navigating = false;
                primaryNavigation = false;
                dragSource = selected();
                dragPreview = dragSource;
                dragRevision = DocumentCodec.fingerprint(document.snapshot().document());
                rotationDrag =
                        new PrintRotationGizmo.Drag(rotation, new javafx.geometry.Point2D(event.getX(), event.getY()));
                rotationDegrees = 0;
                canvas.setCursor(javafx.scene.Cursor.CROSSHAIR);
                draw();
                return;
            }
            var instance = objectAt(event.getX(), event.getY());
            if (instance != null) {
                navigating = false;
                primaryNavigation = false;
                instances.setValue(instance.id());
                dragSource = instance;
                dragPreview = instance;
                dragRevision = DocumentCodec.fingerprint(document.snapshot().document());
                dragX = event.getX();
                dragY = event.getY();
                canvas.setCursor(javafx.scene.Cursor.CLOSED_HAND);
                draw();
                event.consume();
            }
        });
        canvas.setOnMouseDragged(event -> {
            if (navigating) {
                double dx = event.getX() - navigationX, dy = event.getY() - navigationY;
                navigationX = event.getX();
                navigationY = event.getY();
                if (event.isSecondaryButtonDown() || (event.isMiddleButtonDown() && event.isShiftDown()))
                    camera().pan(dx, dy);
                else if (event.isMiddleButtonDown() || (primaryNavigation && event.isPrimaryButtonDown()))
                    camera().orbit(dx, dy);
                canvas.setCursor(javafx.scene.Cursor.CLOSED_HAND);
                draw();
                event.consume();
                return;
            }
            if (dragSource == null) return;
            if (rotationDrag != null) {
                rotationDegrees = rotationDrag.degrees(
                        new javafx.geometry.Point2D(event.getX(), event.getY()), event.isShiftDown());
                dragPreview = PrintRotationGizmo.rotate(
                        dragSource,
                        model.meshes().get(dragSource.bodyId()),
                        rotationDrag.axis(),
                        rotationDegrees,
                        rotationSpace.getValue());
                draw();
                event.consume();
                return;
            }
            var delta = camera().bedDelta((event.getX() - dragX) / viewScale, -(event.getY() - dragY) / viewScale);
            if (delta == null) return;
            dragPreview = PrintPlacement.move(dragSource, delta.getX(), delta.getY(), false, event.isShiftDown());
            draw();
            event.consume();
        });
        canvas.setOnMouseReleased(event -> {
            if (navigating) {
                if (primaryNavigation && event.isStillSincePress()) {
                    instances.setValue(null);
                    draw();
                }
                navigating = false;
                primaryNavigation = false;
                canvas.setCursor(javafx.scene.Cursor.OPEN_HAND);
                event.consume();
                return;
            }
            if (dragSource == null) return;
            var before = dragSource;
            var after = dragPreview;
            boolean current = dragRevision.equals(
                    DocumentCodec.fingerprint(document.snapshot().document()));
            cancelDrag();
            if (current && after != null && !before.equals(after)) replace(after);
            event.consume();
        });
    }

    static Path jobDestination(Path project, String plateName, int plateNumber, int plateCount) {
        if (plateNumber < 1 || plateNumber > plateCount) throw new IllegalArgumentException("Invalid plate number");
        String name = project.getFileName().toString();
        int extension = name.lastIndexOf('.');
        String number =
                String.format(Locale.ROOT, "%0" + Integer.toString(plateCount).length() + "d", plateNumber);
        StringBuilder escaped = new StringBuilder();
        int trailing = plateName.length();
        while (trailing > 0 && (plateName.charAt(trailing - 1) == '.' || plateName.charAt(trailing - 1) == ' '))
            trailing--;
        for (int i = 0; i < plateName.length(); i++) {
            char character = plateName.charAt(i);
            if (Character.isISOControl(character) || "<>:\"/\\|?*%".indexOf(character) >= 0 || i >= trailing)
                escaped.append(String.format(Locale.ROOT, "%%%02X", (int) character));
            else escaped.append(character);
        }
        return project.resolveSibling(
                (extension > 0 ? name.substring(0, extension) : name) + "-" + number + "-" + escaped + ".gcode");
    }

    private ListCell<String> namedCell(ComboBox<String> choice) {
        return new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(
                        empty || item == null
                                ? choice.getPromptText()
                                : choice.getConverter().toString(item));
                setGraphic(null);
                setTooltip(empty || item == null ? null : UiHelp.tooltip(getText()));
            }
        };
    }

    private String bodyName(String id) {
        int index = 1;
        for (var body : document.build().bodies()) {
            if (body.id().equals(id))
                return document.snapshot().document().bodyName(id, translations.text("feature.body", index));
            index++;
        }
        return id;
    }

    private String instanceName(String id) {
        for (int i = 0; i < setup().instances().size(); i++) {
            var instance = setup().instances().get(i);
            if (instance.id().equals(id)) return bodyName(instance.bodyId()) + " (" + (i + 1) + ")";
        }
        return id;
    }

    private void bedPath(List<PrintProfile.Point> points, double ox, double oy, double scale) {
        var graphics = canvas.getGraphicsContext2D();
        graphics.beginPath();
        for (int i = 0; i < points.size(); i++) {
            var p = points.get(i);
            double x = ox + projectX(p.x(), p.y()) * scale;
            double y = oy - projectY(p.x(), p.y(), 0) * scale;
            if (i == 0) graphics.moveTo(x, y);
            else graphics.lineTo(x, y);
        }
        graphics.closePath();
    }

    void openPrintersFolder() {
        try {
            java.nio.file.Files.createDirectories(printers.directory());
            java.awt.Desktop.getDesktop().open(printers.directory().toFile());
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    void exportDefaultPrinters() {
        var selected = PrinterExportDialog.choose(
                getScene().getWindow(), translations, printers.load().entries());
        if (selected.isEmpty()) return;
        try {
            int count = printers.exportDefaults(selected);
            reloadPrinters();
            var alert = new Alert(
                    Alert.AlertType.INFORMATION,
                    translations.text(
                            "print.exportedPrinters",
                            count,
                            printers.directory().toString()),
                    ButtonType.OK);
            alert.initOwner(getScene().getWindow());
            alert.setTitle(translations.text("print.exportPrinters"));
            alert.setHeaderText(null);
            alert.showAndWait();
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private boolean printerVisible(PrinterCatalog.Entry entry) {
        return !entry.bundled()
                || !hiddenPrinters.contains(entry.definition().printer().id());
    }

    void manageVisiblePrinters() {
        try {
            var entries = printers.load().entries();
            var visible = PrinterExportDialog.manageVisible(
                    getScene().getWindow(), translations, entries, printerVisibility.hidden());
            if (visible.isEmpty()) return;
            printerVisibility.saveVisible(entries, visible.get());
            reloadPrinters();
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    void reloadPrinters() {
        try {
            hiddenPrinters = printerVisibility.hidden();
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
        printerCatalog = printers.load();
        if (!printerCatalog.diagnostics().isEmpty())
            diagnostics.setText(String.join("\n", printerCatalog.diagnostics()));
        updatePrinterDetails();
    }

    private void updatePrinterDetails() {
        if (printerCatalog == null) return;
        var saved = setup().printerSnapshot();
        var p = PrintProfile.resolve(setup()).printer();
        var entry = printerCatalog.entries().stream()
                .filter(e -> e.definition().printer().id().equals(p.id()))
                .findFirst()
                .orElse(null);
        var selected = saved == null || (entry != null && entry.definition().equals(saved))
                ? entry
                : new PrinterCatalog.Entry(saved, entry == null ? null : entry.image(), "project", false);
        printerChoice.setText((selected == null ? p.name() : selected.toString())
                + (selected != null && selected.bundled() ? " (" + translations.text("print.builtInPrinter") + ")" : "")
                + "...");
        printerDetails.setText(p.name()
                + (selected != null && selected.bundled() ? " (" + translations.text("print.builtInPrinter") + ")" : "")
                + (saved != null && saved.modified() ? " (" + translations.text("print.modifiedPrinter") + ")" : "")
                + String.format(
                        Locale.ROOT,
                        "\n%.1f x %.1f x %.1f mm / %.2f mm",
                        plateWidth,
                        plateDepth,
                        p.usableHeight(),
                        p.nozzleMm())
                + (saved == null
                        ? ""
                        : translations.text(
                                "print.printerHardware", Double.toString(saved.filamentDiameter()), saved.toolCount()))
                + "\n" + translations.text("print.genericPrinter"));
        String image = entry == null ? null : entry.image();
        if (image == null) {
            var fallback = printers.fallbackImage();
            if (fallback != null) image = fallback;
        }
        var loadedImage = image == null ? null : PrinterImages.load(image);
        if (loadedImage == null || loadedImage.isError()) {
            var fallback = printers.fallbackImage();
            loadedImage = fallback == null ? null : new javafx.scene.image.Image(fallback, 96, 72, true, true);
        }
        printerImage.setImage(loadedImage);
    }

    private void editPlate() {
        var dialog = new Dialog<ButtonType>();
        dialog.initOwner(getScene().getWindow());
        dialog.setTitle(translations.text("print.editPlate"));
        var width = new TextField(Double.toString(plateWidth));
        var depth = new TextField(Double.toString(plateDepth));
        var height = new TextField(
                Double.toString(PrintProfile.resolve(setup()).printer().usableHeight()));
        width.setId("print-bed-width");
        depth.setId("print-bed-depth");
        height.setId("print-bed-height");
        var error = new Label();
        dialog.getDialogPane()
                .setContent(new VBox(
                        8,
                        new Label(translations.text("print.width")),
                        width,
                        new Label(translations.text("print.depth")),
                        depth,
                        new Label(translations.text("print.height")),
                        height,
                        error));
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.getDialogPane().lookupButton(ButtonType.OK).addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            try {
                String printer = PrintProfile.rectangularPrinterId(
                        Double.parseDouble(width.getText()),
                        Double.parseDouble(depth.getText()),
                        Double.parseDouble(height.getText()));
                if (setup().printerSnapshot() == null) {
                    commit(setup().withProfile(setup().backendId(), printer, setup().materialId(), setup().process()));
                } else {
                    commit(setup().withPrinter(setup().printerSnapshot()
                            .rectangular(
                                    Double.parseDouble(width.getText()),
                                    Double.parseDouble(depth.getText()),
                                    Double.parseDouble(height.getText()))));
                }
            } catch (IllegalArgumentException failure) {
                error.setText(translations.text("print.invalidDimensions"));
                event.consume();
            }
        });
        Dialogs.show(dialog, getScene().getWindow(), translations);
    }

    private PrintCamera camera() {
        return threeDimensional ? spatialCamera : topCamera;
    }

    private double projectX(double x, double y) {
        return camera().x(x, y);
    }

    private double projectY(double x, double y, double z) {
        return camera().y(x, y, z);
    }

    private void line(
            double ox, double oy, double scale, double x0, double y0, double z0, double x1, double y1, double z1) {
        canvas.getGraphicsContext2D()
                .strokeLine(
                        ox + projectX(x0, y0) * scale,
                        oy - projectY(x0, y0, z0) * scale,
                        ox + projectX(x1, y1) * scale,
                        oy - projectY(x1, y1, z1) * scale);
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
        selectedObjectTooltip.hide();
        Tooltip.uninstall(canvas, selectedObjectTooltip);
        closed = true;
        validationSequence++;
        jobs.close();
        validator.shutdownNow();
    }
}
