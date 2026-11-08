package dev.voxelbrew.gui;

import dev.voxelbrew.engine.feature.FeatureRegistry;
import dev.voxelbrew.engine.mesh.MeshData;
import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.engine.parameters.*;
import dev.voxelbrew.exporter.ExporterRegistry;
import dev.voxelbrew.util.i18n.Translations;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import lombok.Getter;
import lombok.experimental.Accessors;

/** CAD workbench: model state stays in engine; scene graph and tool previews stay here. */
@javax.inject.Singleton
@lombok.extern.slf4j.Slf4j
public final class Workbench extends BorderPane implements AutoCloseable {
    private record Entry(String id, String label, boolean body) {
        @Override
        public String toString() {
            return label;
        }
    }

    private final class BodyTreeCell extends TreeCell<Entry> {
        private final ContextMenu menu = new ContextMenu();
        private final ContextMenu featureMenu = new ContextMenu();
        private TextField editor;
        private Label validation;
        private ModelSession.Snapshot editingSnapshot;
        private Entry editingEntry;
        private boolean renameRequested;

        BodyTreeCell() {
            var rename = new MenuItem(translations.text("body.rename"));
            rename.setId("body-rename");
            UiHelp.menuItem(rename, translations.text("help.body-rename"));
            rename.setOnAction(event -> {
                var item = getTreeItem();
                Platform.runLater(() -> {
                    if (item == getTreeItem()) requestRename();
                });
            });
            var fit = new MenuItem(translations.text("body.fit"));
            fit.setId("body-fit");
            UiHelp.menuItem(fit, translations.text("help.body-fit"));
            fit.setOnAction(event -> {
                var entry = getItem();
                if (entry == null || entry.id() == null) return;
                var mesh = meshes().get(entry.id());
                if (mesh != null) viewport.fit(Map.of(entry.id(), mesh));
            });
            menu.getItems().addAll(rename, fit);
            UiHelp.menu(menu);
            var featureRename = new MenuItem(translations.text("body.rename"));
            featureRename.setId("feature-rename");
            featureRename.setOnAction(event -> {
                var item = getTreeItem();
                Platform.runLater(() -> {
                    if (item == getTreeItem()) requestRename();
                });
            });
            var featureFit = new MenuItem(translations.text("body.fit"));
            featureFit.setId("feature-fit");
            featureFit.setOnAction(event -> {
                var entry = getItem();
                if (entry == null) return;
                var matching = new LinkedHashMap<String, MeshData>();
                controller.build().bodies().stream()
                        .filter(body -> body.output().featureId().equals(entry.id()))
                        .forEach(body -> matching.put(body.id(), body.mesh()));
                viewport.fit(matching.isEmpty() ? meshes() : matching);
            });
            featureMenu.getItems().addAll(featureRename, featureFit);
            UiHelp.menu(featureMenu);
            featureMenu.setOnShowing(event -> {
                tree.getSelectionModel().select(getTreeItem());
                featureRename.setDisable(!canRename());
                featureFit.setDisable(controller.rebuilding()
                        || !controller.build().valid()
                        || controller.build().bodies().isEmpty());
            });
            menu.setOnShowing(event -> {
                tree.getSelectionModel().select(getTreeItem());
                rename.setDisable(!canRename());
                fit.setDisable(tool != Tool.NONE
                        || controller.rebuilding()
                        || !controller.build().valid());
            });
            setOnMouseClicked(event -> {
                if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2 && canRename()) {
                    requestRename();
                    event.consume();
                }
            });
        }

        private boolean canRename() {
            var entry = getItem();
            return entry != null
                    && entry.id() != null
                    && tool == Tool.NONE
                    && (entry.body()
                            ? controller.canExport()
                                    && controller.build().bodies().stream()
                                            .anyMatch(body -> body.id().equals(entry.id()))
                            : !controller.ioBusy()
                                    && !controller.rebuilding()
                                    && !controller.hasPreview()
                                    && controller.snapshot().document().features().stream()
                                            .anyMatch(feature -> feature.id().equals(entry.id())));
        }

        private void requestRename() {
            renameRequested = true;
            try {
                tree.edit(getTreeItem());
            } finally {
                renameRequested = false;
            }
        }

        @Override
        protected void updateItem(Entry entry, boolean empty) {
            super.updateItem(entry, empty);
            if (!isEditing()) {
                setText(empty || entry == null ? null : entry.label());
                setGraphic(null);
            }
            setContextMenu(empty || entry == null || entry.id() == null ? null : entry.body() ? menu : featureMenu);
            if (empty) {
                menu.hide();
                featureMenu.hide();
            }
        }

        @Override
        public void startEdit() {
            if (isEditing()) return;
            // TreeView's default single-click editing can resize/scroll a selected row
            // between the two clicks, moving the second click onto a different item.
            if (!renameRequested || !canRename()) {
                if (tree.getEditingItem() == getTreeItem()) tree.edit(null);
                return;
            }
            super.startEdit();
            if (!isEditing()) return;
            editingEntry = getItem();
            editingSnapshot = controller.snapshot();
            editor = new TextField(
                    editingEntry.body()
                            ? editingEntry.label()
                            : controller
                                    .snapshot()
                                    .document()
                                    .featureName(editingEntry.id(), featureBaseName(editingEntry.id())));
            editor.setId(editingEntry.body() ? "body-rename-field" : "feature-rename-field");
            editor.setMinWidth(0);
            editor.getStyleClass().add("body-rename-field");
            editor.setAccessibleText(translations.text("body.name"));
            UiHelp.install(editor, translations);
            validation = new Label();
            validation.setId("body-rename-error");
            validation.setWrapText(true);
            validation.getStyleClass().add("error");
            validation.managedProperty().bind(validation.textProperty().isNotEmpty());
            validation.visibleProperty().bind(validation.managedProperty());
            var content = new VBox(3, editor, validation);
            content.prefWidthProperty()
                    .bind(javafx.beans.binding.Bindings.max(50, widthProperty().subtract(44)));
            setText(null);
            setGraphic(content);
            editor.setOnAction(event -> {
                applyName();
                event.consume();
            });
            editor.textProperty().addListener((observable, before, after) -> {
                validation.setText("");
                editor.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("invalid"), false);
            });
            var input = editor;
            input.focusedProperty().addListener((observable, before, focused) -> {
                if (!focused)
                    Platform.runLater(() -> {
                        if (isEditing() && editor == input && !input.isFocused()) tree.edit(null);
                    });
            });
            focusEditor(true);
        }

        private void focusEditor(boolean selectAll) {
            Platform.runLater(() -> {
                if (isEditing()) {
                    tree.applyCss();
                    tree.layout();
                    tree.scrollTo(getIndex());
                    editor.requestFocus();
                    if (selectAll) editor.selectAll();
                }
            });
        }

        private void applyName() {
            if (!isEditing()) return;
            String name = editor.getText();
            try {
                CadDocument.requireBodyName(name);
                name = name.strip();
            } catch (IllegalArgumentException invalid) {
                validation.setText(translations.text("body.rename.invalid"));
                editor.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("invalid"), true);
                focusEditor(false);
                return;
            }
            var current = controller.snapshot();
            boolean currentBody = canRename()
                    && current.revision() == editingSnapshot.revision()
                    && current.document().id().equals(editingSnapshot.document().id());
            var original = editingEntry;
            String previousName = original.body()
                    ? original.label()
                    : editingSnapshot.document().featureName(original.id(), featureBaseName(original.id()));
            tree.edit(null);
            if (currentBody && !name.equals(previousName)) {
                String accepted = name;
                attempt(() -> {
                    if (original.body()) controller.renameBody(original.id(), accepted);
                    else controller.renameFeature(original.id(), accepted);
                });
            }
        }

        @Override
        public void cancelEdit() {
            super.cancelEdit();
            editingEntry = null;
            editingSnapshot = null;
            setText(getItem() == null ? null : getItem().label());
            setGraphic(null);
        }
    }

    private enum Tool {
        NONE,
        PLANE,
        EXTRUDE,
        MODIFY
    }

    private final Stage stage;
    private final WindowFrame window;
    private final javafx.beans.value.ChangeListener<Boolean> windowFocus;

    @Getter
    @Accessors(fluent = true)
    private final DocumentController controller;

    private final Path pluginDirectory;
    private final ExporterRegistry registry;
    private final dev.voxelbrew.importer.ImporterRegistry.Factory importers;

    @Getter
    @Accessors(fluent = true)
    private final Viewport viewport;

    private final PrintingPane printing;
    private final TreeView<Entry> tree = new TreeView<>();
    private final VBox inspector = new VBox(12), form = new VBox(9);
    private final VBox featureDiagnostics = new VBox(6);
    private final HBox toolActions = new HBox(8);
    private final Label status, error, selectionLabel;
    private static final List<String> STANDARD_VIEWS =
            List.of("Isometric", "Top", "Front", "Right", "Back", "Left", "Bottom");
    private final FeatureTimeline timeline;
    private final Button undo, redo;
    private final MenuItem export;
    private final javafx.beans.property.BooleanProperty exportUnavailable =
            new javafx.beans.property.SimpleBooleanProperty(true);
    private final javafx.beans.property.BooleanProperty fileBusy = new javafx.beans.property.SimpleBooleanProperty();
    private final Label documentTitle = new Label();
    private final Button customFeature;
    private final Button parameters;
    private final ComboBox<String> configuration = new ComboBox<>();
    private dev.voxelbrew.engine.parameters.ParameterSet lastParameters =
            dev.voxelbrew.engine.parameters.ParameterSet.empty();
    private final Button constrainedSketch;
    private final Button extrude;
    private final Button cut;
    private final Button splitBody;
    private final Button fillet;
    private final Button chamfer;
    private final Button shell;
    private final ProgressIndicator progress = new ProgressIndicator();
    private final Appearance appearance;
    private final ExecutorService preferencesWorker;
    private final Translations translations;
    private final dev.voxelbrew.util.config.UserConfiguration configurationStore;
    private final WindowResources resources;
    private final FeatureRegistry.Factory featureProviders;
    private final SketchController.Factory sketches;
    private Appearance.Choice appearanceChoice;
    private long appearanceRequest;
    private boolean closed, refreshing;
    private Tool tool = Tool.NONE;
    private String modification;
    private String modificationBody;
    private final java.util.Set<Integer> modificationFaces = new java.util.TreeSet<>();
    private final java.util.Set<Integer> modificationEdges = new java.util.TreeSet<>();
    private Label modificationSelection;
    private String selectedFeature, selectedBody;
    private final Map<String, TextField> fields = new LinkedHashMap<>();
    private List<Feature> lastFeatures = List.of();
    private Map<String, BodyStyle> lastBodyStyles = Map.of();
    private Map<String, String> lastBodyNames = Map.of();
    private Map<String, String> lastFeatureNames = Map.of();
    private boolean lastRebuilding;
    private boolean lastIoBusy;
    private DocumentController.Build lastBuild;
    private String lastDocument = "";
    private boolean fitNext = true;
    private Feature.Sketch extrusionSketch;
    private ModelSession.Snapshot planeSnapshot;
    private Feature.Sketch planeRepair;
    private Button finish;
    private final UpdateDialog.Factory updates;
    private final javax.inject.Provider<AboutDialog> about;

    public static Workbench create(Stage stage, Path plugins) throws IOException {
        return create(
                stage,
                plugins,
                dev.voxelbrew.util.di.ApplicationModule.system(
                        dev.voxelbrew.util.update.ApplicationImage.Component.GUI),
                new dev.voxelbrew.exporter.di.FileIoModule());
    }

    static Workbench create(Stage stage, Path plugins, dev.voxelbrew.util.update.UpdateServiceFactory updates)
            throws IOException {
        return create(
                stage,
                plugins,
                new dev.voxelbrew.util.di.ApplicationModule(
                        dev.voxelbrew.util.config.UserConfiguration.user(), updates),
                new dev.voxelbrew.exporter.di.FileIoModule());
    }

    static Workbench create(
            Stage stage,
            Path plugins,
            dev.voxelbrew.util.di.ApplicationModule application,
            dev.voxelbrew.exporter.di.FileIoModule files)
            throws IOException {
        var graph = DaggerGuiComponent.factory().create(stage, plugins, application, files);
        WindowResources resources;
        try {
            resources = graph.resources();
        } catch (java.io.UncheckedIOException failure) {
            throw failure.getCause();
        }
        try {
            return graph.workbench();
        } catch (RuntimeException | Error failure) {
            resources.close();
            throw failure;
        }
    }

    @javax.inject.Inject
    Workbench(
            Stage stage,
            @javax.inject.Named("plugins") Path pluginDirectory,
            DocumentController controller,
            ExporterRegistry registry,
            dev.voxelbrew.importer.ImporterRegistry.Factory importers,
            PrintingPane printing,
            Appearance appearance,
            Rendering rendering,
            @javax.inject.Named("appearance") ExecutorService preferencesWorker,
            Translations translations,
            dev.voxelbrew.util.config.UserConfiguration configurationStore,
            UpdateDialog.Factory updates,
            javax.inject.Provider<AboutDialog> about,
            WindowResources resources,
            FeatureRegistry.Factory featureProviders,
            SketchController.Factory sketches) {
        this.stage = stage;
        ApplicationIcon.install(stage);
        this.pluginDirectory = pluginDirectory;
        this.controller = controller;
        this.registry = registry;
        this.importers = importers;
        this.printing = printing;
        this.appearance = appearance;
        this.preferencesWorker = preferencesWorker;
        this.translations = translations;
        this.configurationStore = configurationStore;
        this.viewport = new Viewport(translations);
        this.viewport.setRendering(rendering.load());
        this.updates = updates;
        this.about = about;
        this.resources = resources;
        this.featureProviders = featureProviders;
        this.sketches = sketches;
        this.status = new Label(translations.text("status.ready"));
        this.error = new Label();
        this.selectionLabel = new Label(translations.text("status.noSelection"));
        this.undo = button(translations.text("action.undo"), "undo", () -> {
            cancelTool();
            controller().undo();
        });
        this.redo = button(translations.text("action.redo"), "redo", () -> {
            cancelTool();
            controller().redo();
        });
        this.export = menuItem(
                translations.text("action.export"),
                "export",
                true,
                () -> ExportDialog.show(stage(), controller(), registry(), translations));
        this.customFeature = button(translations.text("action.features"), "custom-feature", this::customFeature);
        this.parameters = button(translations.text("action.parameters"), "parameters", this::parameters);
        this.constrainedSketch =
                button(translations.text("action.sketch"), "constrained-sketch", () -> beginPlaneSelection(null));
        this.extrude = button(translations.text("action.extrude"), "extrude", this::beginExtrusion);
        this.cut = button(translations.text("action.cut"), "cut", this::beginCut);
        this.splitBody = button(translations.text("action.split"), "split-body", this::beginSplit);
        this.fillet = button(translations.text("action.fillet"), "fillet", () -> beginFinish(true));
        this.chamfer = button(translations.text("action.chamfer"), "chamfer", () -> beginFinish(false));
        this.shell = button(translations.text("action.shell"), "shell", this::beginShell);
        appearanceChoice = appearance.load();
        setId("workbench");
        featureDiagnostics.setId("feature-diagnostics");
        getStylesheets()
                .add(Objects.requireNonNull(getClass().getResource("workbench.css"))
                        .toExternalForm());
        UiText.choices(
                configuration, name -> name.equals("Default") ? translations.text("configuration.default") : name);
        var fileMenu = menu("menu-file");
        var newItem = menuItem(translations.text("action.new"), "new", true, this::newDocument);
        var openItem = menuItem(translations.text("action.open"), "open", true, () -> requestChange(this::open));
        var saveItem = menuItem(translations.text("action.save"), "save", true, () -> save(false));
        var importItem =
                menuItem(translations.text("action.import"), "import", true, () -> requestChange(this::importFile));
        var saveAsItem = menuItem(translations.text("action.saveAs"), "save-as", true, () -> save(true));
        for (var item : List.of(newItem, openItem, importItem, saveItem, saveAsItem))
            item.disableProperty().bind(disabledProperty().or(fileBusy));
        export.disableProperty().bind(disabledProperty().or(exportUnavailable));
        fileMenu.getItems()
                .addAll(
                        newItem,
                        openItem,
                        importItem,
                        new SeparatorMenuItem(),
                        saveItem,
                        saveAsItem,
                        new SeparatorMenuItem(),
                        export);
        var editMenu = menu("menu-edit");
        editMenu.getItems()
                .addAll(mirror(undo, true), mirror(redo, true), new SeparatorMenuItem(), mirror(parameters, true));
        var viewMenu = menu("menu-view");
        viewMenu.getItems().add(menuItem(translations.text("action.fit"), "menu-fit", false, () -> {
            viewport.fit(meshes());
            Platform.runLater(viewport::requestFocus);
        }));
        for (var name : STANDARD_VIEWS) {
            var item = menuItem(
                    UiText.view(translations, name),
                    "view-" + name.toLowerCase(Locale.ROOT),
                    false,
                    () -> viewport.standardView(name));
            viewMenu.getItems().add(item);
        }
        var appearanceMenu = new Menu(translations.text("appearance.label"));
        appearanceMenu.setId("menu-appearance");
        UiHelp.menu(appearanceMenu, translations);
        var themeGroup = new ToggleGroup();
        for (var choice : Appearance.Choice.values()) {
            String name = choice.name().toLowerCase(Locale.ROOT);
            var item = new RadioMenuItem(translations.text("appearance." + name));
            item.setId("appearance-" + name);
            item.setToggleGroup(themeGroup);
            item.setSelected(choice == appearanceChoice);
            item.disableProperty().bind(disabledProperty());
            UiHelp.menuItem(item, translations.text("help.appearance"));
            item.setOnAction(event -> {
                appearanceChoice = choice;
                appearance.save(choice);
                applyAppearance();
            });
            appearanceMenu.getItems().add(item);
        }
        viewMenu.getItems().addAll(new SeparatorMenuItem(), appearanceMenu);
        var renderingMenu = new Menu(translations.text("rendering.label"));
        renderingMenu.setId("menu-rendering");
        UiHelp.menu(renderingMenu, translations);
        var renderingGroup = new ToggleGroup();
        for (var style : Rendering.Style.values()) {
            String name = style.name().toLowerCase(Locale.ROOT);
            var item = new RadioMenuItem(translations.text("rendering." + name));
            item.setId("rendering-" + name);
            item.setToggleGroup(renderingGroup);
            item.setSelected(style == viewport.rendering());
            item.disableProperty().bind(disabledProperty());
            UiHelp.menuItem(item, translations.text("help.rendering-" + name));
            item.setOnAction(event -> {
                viewport.setRendering(style);
                rendering.save(style);
            });
            renderingMenu.getItems().add(item);
        }
        viewMenu.getItems().add(renderingMenu);
        var settingsMenu = menu("menu-settings");
        settingsMenu
                .getItems()
                .addAll(
                        menuItem(
                                translations.text("language.label"),
                                "language",
                                false,
                                () -> LanguageDialog.show(stage, translations, configurationStore, preferencesWorker)),
                        menuItem(translations.text("saveFolder.title"), "save-folder", false, this::saveFolder),
                        menuItem(translations.text("action.updates"), "updates", false, this::updates),
                        menuItem(translations.text("about.title"), "about", false, () -> {
                            if (about.get().show(stage)) updates();
                        }),
                        new SeparatorMenuItem(),
                        menuItem(
                                translations.text("shortcuts.title"),
                                "keyboard-shortcuts",
                                true,
                                () -> KeyboardShortcuts.show(stage, translations)));
        var menuBar = new MenuBar(fileMenu, editMenu, viewMenu, settingsMenu);
        menuBar.setId("main-menu");
        menuBar.disableProperty().bind(disabledProperty());
        menuBar.setMinWidth(Region.USE_PREF_SIZE);
        UiHelp.menuBar(menuBar);
        var brand = new Label("VoxelBrew");
        brand.getStyleClass().add("brand");
        documentTitle.setId("document-title");
        documentTitle.getStyleClass().add("document-title");
        var documentIcon = new javafx.scene.shape.SVGPath();
        documentIcon.setContent("M 3 1 L 11 1 L 15 5 L 15 17 L 3 17 Z M 11 1 L 11 5 L 15 5");
        documentIcon.getStyleClass().add("document-icon");
        documentTitle.setGraphic(documentIcon);
        documentTitle.setGraphicTextGap(10);
        documentTitle.setTextOverrun(OverrunStyle.CENTER_ELLIPSIS);
        documentTitle.setMinWidth(100);
        documentTitle.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(documentTitle, Priority.ALWAYS);
        var title = new HBox(12, brand, documentTitle, menuBar);
        title.getStyleClass().add("title-strip");
        window = new WindowFrame(stage, title, this, translations);
        var sketchCommands = new HBox(2, constrainedSketch);
        var solidCommands = new MenuButton(translations.text("action.operations"));
        solidCommands.setId("operations-menu");
        var operationsIcon = new javafx.scene.shape.SVGPath();
        operationsIcon.setContent("M2 6L10 2L18 6V15L10 19L2 15Z M2 6L10 10L18 6 M10 10V19");
        operationsIcon.setFill(javafx.scene.paint.Color.TRANSPARENT);
        operationsIcon.setStroke(javafx.scene.paint.Color.web("#658dcc"));
        operationsIcon.setStrokeWidth(1.5);
        operationsIcon.setMouseTransparent(true);
        solidCommands.setGraphic(operationsIcon);
        UiHelp.menu(solidCommands, translations);
        for (var command : List.of(extrude, cut, fillet, chamfer, shell, splitBody)) {
            var item = menuItem(command.getText(), command.getId(), true, command::fire);
            item.disableProperty().bind(disabledProperty().or(command.disabledProperty()));
            solidCommands.getItems().add(item);
        }
        sketchCommands.getStyleClass().add("command-group");
        var toolbar = new FlowPane(10, 6, sketchCommands, solidCommands);
        toolbar.setId("modeling-toolbar");
        toolbar.getStyleClass().add("command-strip");
        constrainedSketch.getStyleClass().add("primary");
        tree.setId("model-tree");
        tree.setShowRoot(false);
        tree.setEditable(true);
        tree.setCellFactory(view -> new BodyTreeCell());
        tree.getSelectionModel().selectedItemProperty().addListener((o, old, item) -> {
            if (refreshing || item == null || item.getValue().id() == null || tool != Tool.NONE) return;
            var entry = item.getValue();
            if (entry.body()) selectBody(entry.id(), -1);
            else selectFeature(entry.id());
        });
        configuration.setId("active-configuration");
        UiHelp.install(configuration, translations);
        configuration.setMaxWidth(Double.MAX_VALUE);
        configuration.setOnAction(e -> {
            if (!refreshing && configuration.getValue() != null)
                attempt(() -> controller.configuration(configuration.getValue()));
        });
        var left = new VBox(
                10,
                heading(translations.text("gui.model")),
                tree,
                new Separator(),
                heading(translations.text("workbench.configuration")),
                configuration,
                parameters,
                customFeature);
        left.getStyleClass().add("side-panel");
        left.setPrefWidth(220);
        left.setMinWidth(185);
        VBox.setVgrow(tree, Priority.ALWAYS);
        inspector.getStyleClass().add("side-panel");
        inspector.setPrefWidth(270);
        inspector.setMinWidth(240);
        error.setWrapText(true);
        error.setId("tool-error");
        error.getStyleClass().add("error");
        selectionLabel.getStyleClass().add("selection-title");
        var scroll = new ScrollPane(form);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        error.managedProperty().bind(error.textProperty().isNotEmpty());
        error.visibleProperty().bind(error.managedProperty());
        inspector
                .getChildren()
                .addAll(heading(translations.text("gui.properties")), selectionLabel, scroll, error, toolActions);
        var center = new BorderPane(viewport);
        center.getStyleClass().add("viewport-panel");
        var split = new SplitPane(left, center, inspector);
        split.setDividerPositions(0.18, 0.78);
        var design = new BorderPane(split);
        design.setId("design-workspace");
        design.setTop(toolbar);
        var designTab = new Tab(translations.text("tab.Designer"), design);
        designTab.setId("workspace-design");
        designTab.setClosable(false);
        var printTab = new Tab(translations.text("tab.Slicer"), printing);
        printTab.setId("workspace-print");
        printTab.setClosable(false);
        var workspaces = new TabPane(designTab, printTab);
        designTab.setTooltip(UiHelp.tooltip(UiHelp.description(translations, "workspace-design")));
        printTab.setTooltip(UiHelp.tooltip(UiHelp.description(translations, "workspace-print")));
        workspaces.setId("workspaces");
        workspaces.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            cancelTool();
            printing.documentChanged();
        });
        setCenter(workspaces);
        timeline = new FeatureTimeline(
                translations,
                position -> attempt(() -> controller.cursor(position)),
                this::selectFeature,
                this::shortName,
                (from, to) -> attempt(() -> controller.deleteFeatures(from, to)));
        progress.setPrefSize(16, 16);
        progress.setMaxSize(16, 16);
        status.setId("status");
        status.setWrapText(true);
        var statusSpacer = new Region();
        HBox.setHgrow(statusSpacer, Priority.ALWAYS);
        var statusbar = new HBox(
                10, progress, status, statusSpacer, new Label(translations.text("gui.mm_occt_7_9_grid_10_mm")));
        statusbar.getStyleClass().add("status-strip");
        design.setBottom(new VBox(timeline, statusbar));
        viewport.onSelection((id, face) -> {
            if (tool == Tool.PLANE) {
                chooseBodyPlane(id, face);
                return;
            }
            if (tool == Tool.MODIFY) {
                selectModificationFace(id, face);
                return;
            }
            if (tool != Tool.NONE || controller.rebuilding()) return;
            if (controller.snapshot().document().features().stream().anyMatch(f -> f.id().equals(id)))
                selectFeature(id);
            else selectBody(id, face);
        });
        viewport.onEdgeSelection(this::selectModificationEdge);
        controller.onChanged(this::refresh);
        windowFocus = (o, old, focused) -> {
            if (!closed && focused && appearanceChoice == Appearance.Choice.SYSTEM) applyAppearance();
        };
        stage.focusedProperty().addListener(windowFocus);
        window.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (isDisabled() || menuBar.getMenus().stream().anyMatch(Menu::isShowing)) return;
            var focus = getScene().getFocusOwner();
            if (KeyboardShortcuts.textUndo(e, focus)) return;
            if (e.getCode() == KeyCode.ESCAPE) {
                if (tree.getEditingItem() != null) {
                    tree.edit(null);
                    e.consume();
                    return;
                }
                cancelTool();
                e.consume();
                return;
            }
            String action = KeyboardShortcuts.match(KeyboardShortcuts.Scope.DOCUMENT, e, focus);
            if (action != null) {
                // Consume before opening a modal nested event loop, and use the same
                // enabled-state gates as the visible controls.
                e.consume();
                switch (action) {
                    case "new" -> fire(newItem);
                    case "open" -> fire(openItem);
                    case "import" -> fire(importItem);
                    case "save" -> fire(saveItem);
                    case "save-as" -> fire(saveAsItem);
                    case "export" -> fire(export);
                    case "undo" -> undo.fire();
                    case "redo" -> redo.fire();
                    case "parameters" -> parameters.fire();
                    case "workspace-design" -> workspaces.getSelectionModel().select(designTab);
                    case "workspace-print" -> workspaces.getSelectionModel().select(printTab);
                    case "keyboard-shortcuts" -> KeyboardShortcuts.show(stage, translations);
                    default -> throw new IllegalStateException(action);
                }
                return;
            }
            if (!designTab.isSelected()) return;
            action = KeyboardShortcuts.match(KeyboardShortcuts.Scope.DESIGN, e, focus);
            if (action == null) return;
            KeyboardShortcuts.consume(e, getScene());
            String viewAction = action;
            switch (action) {
                case "constrained-sketch" -> constrainedSketch.fire();
                case "extrude" -> extrude.fire();
                case "cut" -> cut.fire();
                case "split-body" -> splitBody.fire();
                case "fit" -> viewport.fit(meshes());
                default ->
                    STANDARD_VIEWS.stream()
                            .filter(name -> ("view-" + name.toLowerCase(Locale.ROOT)).equals(viewAction))
                            .findFirst()
                            .ifPresent(viewport::standardView);
            }
        });
        stage.setOnCloseRequest(e -> {
            e.consume();
            requestChange(() -> {
                close();
                stage.hide();
            });
        });
        applyAppearance();
        refresh();
        if (!registry.diagnostics().isEmpty()) status.setText(String.join(" | ", registry.diagnostics()));
    }

    private Stage stage() {
        return stage;
    }

    javafx.scene.Scene createScene(double width, double height) {
        return new javafx.scene.Scene(window, width, height);
    }

    private ExporterRegistry registry() {
        return registry;
    }

    private static Label heading(String text) {
        var label = new Label(text);
        label.getStyleClass().add("eyebrow");
        return label;
    }

    private Button button(String text, String id, Runnable action) {
        var button = new Button(text);
        button.setId(id);
        UiHelp.install(button, translations);
        button.setOnAction(e -> attempt(action));
        return button;
    }

    private Menu menu(String id) {
        var menu = new Menu(translations.text(id));
        menu.setId(id);
        UiHelp.menu(menu, translations);
        return menu;
    }

    private MenuItem menuItem(String text, String id, boolean shortcut, Runnable action) {
        var item = new MenuItem(text);
        item.setId(id);
        item.disableProperty().bind(disabledProperty());
        if (shortcut) KeyboardShortcuts.menuAccelerator(id).ifPresent(item::setAccelerator);
        UiHelp.menuItem(item, UiHelp.description(translations, id));
        item.setOnAction(e -> attempt(action));
        return item;
    }

    private static void fire(MenuItem item) {
        if (!item.isDisable()) item.fire();
    }

    private MenuItem mirror(Button button, boolean shortcut) {
        var item = menuItem(button.getText(), "menu-" + button.getId(), shortcut, button::fire);
        item.disableProperty().bind(disabledProperty().or(button.disabledProperty()));
        return item;
    }

    private void newDocument() {
        requestChange(() -> {
            cancelTool();
            selectedFeature = null;
            selectedBody = null;
            fitNext = true;
            controller.newDocument();
        });
    }

    private void importFile() {
        try (var providers = importers.open(pluginDirectory)) {
            ImportDialog.choose(
                            stage, providers, controller.snapshot().document().parameters(), translations)
                    .ifPresent(request -> {
                        cancelTool();
                        controller
                                .importFile(importers, request.format(), request.source(), request.options())
                                .exceptionally(failure -> null);
                    });
        } catch (IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    private void attempt(Runnable action) {
        try {
            error.setText("");
            action.run();
        } catch (RuntimeException failure) {
            error.setText(Objects.toString(failure.getMessage(), failure.toString()));
        }
    }

    private Map<String, MeshData> meshes() {
        var result = new LinkedHashMap<String, MeshData>();
        controller.build().bodies().forEach(b -> result.put(b.id(), b.mesh()));
        return result;
    }

    private void refresh() {
        if (closed) return;
        refreshing = true;
        try {
            var snapshot = controller.snapshot();
            if (tool == Tool.PLANE && !planeSelectionCurrent()) {
                tool = Tool.NONE;
                planeSnapshot = null;
                planeRepair = null;
                viewport.stopPlaneSelection();
                inspect();
            }
            var document = snapshot.document();
            var effective = controller.effectiveFeatures();
            boolean buildStateChanged = lastBuild != controller.build()
                    || lastRebuilding != controller.rebuilding()
                    || lastIoBusy != controller.ioBusy();
            boolean changed = !lastFeatures.equals(effective)
                    || !lastBodyStyles.equals(document.bodyStyles())
                    || !lastBodyNames.equals(document.bodyNames())
                    || !lastFeatureNames.equals(document.featureNames())
                    || !lastParameters.equals(document.parameters())
                    || !lastDocument.equals(document.id());
            if (!lastDocument.equals(document.id())) {
                selectedFeature = null;
                selectedBody = null;
                viewport.select(null);
                fitNext = true;
            }
            if (changed || lastBuild != controller.build()) rebuildTree(document);
            if (lastBuild != controller.build()) {
                viewport.showBodies(controller.build().bodies());
                if (fitNext && !controller.build().bodies().isEmpty()) {
                    viewport.fit(meshes());
                    fitNext = false;
                }
                lastBuild = controller.build();
                if (tool == Tool.NONE) {
                    updateFeatureDiagnostics();
                    var feature = selected();
                    if (feature != null) selectionLabel.setText(shortName(feature));
                }
            }
            var styles = new LinkedHashMap<String, BodyStyle>();
            controller.build().bodies().forEach(b -> styles.put(b.id(), document.bodyStyle(b.id())));
            viewport.setBodyStyles(styles);
            synchronizeTreeSelection();
            viewport.showSketches(effective.subList(0, document.cursor()), selectedFeature);
            if (tool == Tool.NONE && (changed || selectedBody != null && buildStateChanged)) inspect();
            lastRebuilding = controller.rebuilding();
            lastIoBusy = controller.ioBusy();
            lastFeatures = effective;
            lastBodyStyles = document.bodyStyles();
            lastBodyNames = document.bodyNames();
            lastFeatureNames = document.featureNames();
            lastParameters = document.parameters();
            lastDocument = document.id();
            timeline.update(
                    document,
                    selectedFeature,
                    controller.build().diagnostics(),
                    controller.ioBusy() || tool != Tool.NONE || controller.hasPreview(),
                    controller.rebuilding(),
                    controller.build().valid());
            undo.setDisable(!snapshot.canUndo() || controller.ioBusy());
            redo.setDisable(!snapshot.canRedo() || controller.ioBusy());
            parameters.setDisable(controller.ioBusy() || tool != Tool.NONE || controller.rebuilding());
            var names = new ArrayList<String>();
            names.add("Default");
            document.parameters().configurations().forEach(c -> names.add(c.name()));
            if (!configuration.getItems().equals(names))
                configuration.getItems().setAll(names);
            configuration.setValue(document.parameters().activeName());
            configuration.setDisable(controller.ioBusy() || tool != Tool.NONE);
            exportUnavailable.set(!controller.canExport());
            fileBusy.set(controller.ioBusy());
            splitBody.setDisable(tool != Tool.NONE
                    || !controller.canExport()
                    || controller.build().bodies().isEmpty());
            fillet.setDisable(splitBody.isDisabled());
            chamfer.setDisable(splitBody.isDisabled());
            shell.setDisable(splitBody.isDisabled());
            printing.documentChanged();
            boolean atEnd = document.cursor() == document.features().size() && !controller.ioBusy();
            customFeature.setDisable(!atEnd || tool != Tool.NONE || controller.rebuilding());
            constrainedSketch.setDisable(!atEnd || tool != Tool.NONE || controller.rebuilding());
            extrude.setDisable(!atEnd
                    || tool != Tool.NONE
                    || !(selected() instanceof Feature.Sketch s
                            && (!(s.profile() instanceof Feature.Constrained c)
                                    || dev.voxelbrew.engine.sketch.SketchProfiles.hasProfile(c.geometry()))));
            cut.setDisable(!atEnd
                    || tool != Tool.NONE
                    || !(selected() instanceof Feature.Sketch s
                            && (s.profile() instanceof Feature.Circle
                                    || s.profile() instanceof Feature.Constrained c
                                            && dev.voxelbrew.engine.sketch.SketchProfiles.hasProfile(c.geometry())))
                    || controller.rebuilding()
                    || controller.build().bodies().isEmpty());
            progress.setVisible(controller.rebuilding() || controller.ioBusy());
            String diagnostics = controller.build().diagnostics().stream()
                    .filter(d -> d.state() == Evaluation.State.FAILED || d.state() == Evaluation.State.BLOCKED)
                    .map(d -> d.featureId() + ": " + d.message())
                    .reduce((a, b) -> a + " | " + b)
                    .orElse("");
            status.setText(
                    !controller.error().isBlank()
                            ? controller.error()
                            : controller.ioBusy()
                                    ? translations.text("gui.working_with_file")
                                    : controller.rebuilding()
                                            ? translations.text("gui.rebuilding")
                                            : !diagnostics.isBlank()
                                                    ? diagnostics
                                                    : controller.hasPreview()
                                                            ? translations.text(
                                                                    "gui.preview_release_or_enter_to_commit_escape_to_cancel")
                                                            : translations.text(
                                                                    "status.bodies",
                                                                    controller
                                                                            .build()
                                                                            .bodies()
                                                                            .size(),
                                                                    String.format(
                                                                            Locale.ROOT,
                                                                            "%.3f mm³",
                                                                            controller.build().bodies().stream()
                                                                                    .mapToDouble(
                                                                                            DocumentController.Body
                                                                                                    ::volume)
                                                                                    .sum())));
            documentTitle.setText((controller.dirty() ? "● " : "")
                    + (controller.path() == null
                            ? translations.text("gui.untitled")
                            : controller.path().getFileName().toString()));
            UiHelp.describe(
                    documentTitle,
                    controller.path() == null
                            ? translations.text("help.unsaved-document")
                            : controller.path().toString());
            stage.setTitle((controller.dirty() ? "● " : "")
                    + (controller.path() == null
                            ? translations.text("gui.untitled")
                            : controller.path().getFileName())
                    + " — VoxelBrew CAD");
        } finally {
            refreshing = false;
        }
    }

    private String shortName(Feature feature) {
        String customName = controller.snapshot().document().featureNames().get(feature.id());
        if (customName != null) return customName;
        return defaultFeatureName(feature);
    }

    private String featureBaseName(String id) {
        return controller.snapshot().document().features().stream()
                .filter(feature -> feature.id().equals(id))
                .findFirst()
                .map(this::defaultFeatureName)
                .orElse(id);
    }

    private String defaultFeatureName(Feature feature) {
        if (feature instanceof Feature.Custom custom) return translations.text("feature.custom", custom.type());
        if (feature instanceof Feature.EdgeFinish finish)
            return translations.text(
                    finish.kind() == Feature.EdgeFinish.Kind.FILLET ? "action.fillet" : "action.chamfer");
        if (feature instanceof Feature.Shell) return translations.text("action.shell");
        return feature instanceof Feature.Sketch s
                ? (s.profile() instanceof Feature.Circle
                        ? translations.text("gui.circle")
                        : s.profile() instanceof Feature.Constrained
                                ? translations.text("gui.sketch")
                                : translations.text("gui.rectangle"))
                : feature instanceof Feature.Extrude
                        ? translations.text("gui.extrude")
                        : feature instanceof Feature.SplitBody
                                ? translations.text("gui.split")
                                : translations.text("action.cut");
    }

    private void rebuildTree(CadDocument document) {
        tree.edit(null);
        var root = new TreeItem<>(new Entry(null, translations.text("gui.document"), false));
        var features = new TreeItem<>(new Entry(null, translations.text("gui.features"), false));
        features.setExpanded(true);
        for (int i = 0; i < document.features().size(); i++) {
            var feature = document.features().get(i);
            String diagnostic = controller.build().diagnostics().stream()
                    .filter(d -> d.featureId().equals(feature.id()) && d.state() != Evaluation.State.OK)
                    .map(d -> "  [" + d.state() + "]")
                    .findFirst()
                    .orElse("");
            var item = new TreeItem<>(new Entry(feature.id(), (i + 1) + "  " + shortName(feature) + diagnostic, false));
            features.getChildren().add(item);
        }
        var bodies = new TreeItem<>(new Entry(null, translations.text("gui.bodies"), true));
        bodies.setExpanded(true);
        int index = 1;
        for (var body : controller.build().bodies())
            bodies.getChildren()
                    .add(new TreeItem<>(new Entry(
                            body.id(),
                            document.bodyName(body.id(), translations.text("feature.body", index++)),
                            true)));
        root.getChildren().add(features);
        root.getChildren().add(bodies);
        tree.setRoot(root);
        for (var item : features.getChildren())
            if (item.getValue().id().equals(selectedFeature))
                tree.getSelectionModel().select(item);
    }

    private void synchronizeTreeSelection() {
        if (tree.getRoot() == null) return;
        String id = selectedBody == null ? selectedFeature : selectedBody;
        for (var group : tree.getRoot().getChildren())
            for (var item : group.getChildren())
                if (item.getValue().id().equals(id)) {
                    tree.getSelectionModel().select(item);
                    return;
                }
        tree.getSelectionModel().clearSelection();
    }

    private String bodyLabel(String id) {
        int index = 1;
        for (var body : controller.build().bodies()) {
            if (body.id().equals(id))
                return controller.snapshot().document().bodyName(id, translations.text("feature.body", index));
            index++;
        }
        return id;
    }

    private Feature selected() {
        return controller.effectiveFeatures().stream()
                .filter(f -> f.id().equals(selectedFeature))
                .findFirst()
                .orElse(null);
    }

    private void selectFeature(String id) {
        if (tool != Tool.NONE) return;
        timeline.stop();
        selectedFeature = id;
        selectedBody = null;
        viewport.select(null);
        viewport.showSketches(
                controller
                        .effectiveFeatures()
                        .subList(0, controller.snapshot().document().cursor()),
                id);
        inspect();
        refresh();
    }

    private void selectBody(String id, int face) {
        timeline.stop();
        selectedBody = id;
        controller.build().bodies().stream()
                .filter(b -> b.id().equals(id))
                .findFirst()
                .ifPresent(b -> selectedFeature = b.output().featureId());
        viewport.select(id);
        printing.selectedFace(id, face);
        inspect();
        if (face >= 0) selectionLabel.setText(translations.text("feature.face", face) + " · " + bodyLabel(id));
        refresh();
    }

    private void inspect() {
        if (tool != Tool.NONE) return;
        form.getChildren().clear();
        toolActions.getChildren().clear();
        fields.clear();
        error.setText("");
        var feature = selected();
        if (feature == null) {
            selectionLabel.setText(translations.text("gui.start_a_design"));
            var hint = new Label(
                    translations.text(
                            "gui.draw_a_rectangle_or_circle_on_xy_then_extrude_its_closed_profile_middle_drag_to_orbit_shift_mid"));
            hint.setWrapText(true);
            form.getChildren().add(hint);
            return;
        }
        selectionLabel.setText(selectedBody == null ? shortName(feature) : bodyLabel(selectedBody));
        var identity = new Label(feature.id());
        identity.setWrapText(true);
        identity.getStyleClass().add("muted");
        form.getChildren().add(identity);
        if (selectedBody != null) bodyStyleEditor(selectedBody);
        if (feature instanceof Feature.Sketch s) {
            if (s.profile() instanceof Feature.Rectangle r) {
                dimension(feature, "x", r.x());
                dimension(feature, "y", r.y());
                dimension(feature, "width", r.width());
                dimension(feature, "height", r.height());
            } else if (s.profile() instanceof Feature.Circle c) {
                dimension(feature, "centerX", c.centerX());
                dimension(feature, "centerY", c.centerY());
                dimension(feature, "diameter", c.diameter());
            } else if (s.profile() instanceof Feature.Constrained c) {
                var info = new Label(translations.text(
                        "feature.sketchSummary",
                        c.geometry().entities().size(),
                        c.geometry().constraints().size()));
                info.setWrapText(true);
                form.getChildren().add(info);
                var hint = new Label(translations.text(
                        "gui.edit_sketch_to_change_driving_dimensions_constraints_or_the_profile_boundary"));
                hint.setWrapText(true);
                form.getChildren().add(hint);
            }
            form.getChildren()
                    .add(button(
                            translations.text("gui.edit_sketch_constraints"),
                            "edit-sketch-constraints",
                            () -> editSketch(s)));
            dimension(feature, "offsetZ", s.plane().offsetZ());
            form.getChildren()
                    .add(new Label(translations.text("feature.plane", s.plane().id())));
            form.getChildren()
                    .add(button(
                            translations.text("plane.change"), "change-sketch-plane", () -> beginPlaneSelection(s)));
        } else if (feature instanceof Feature.Extrude e) {
            dimension(feature, "distance", e.distance());
            referenceEditor(feature, e.profile(), null);
        } else if (feature instanceof Feature.EdgeFinish f) {
            dimension(feature, "size", f.size());
            referenceEditor(feature, null, f.body());
        } else if (feature instanceof Feature.Shell s) {
            dimension(feature, "thickness", s.thickness());
            referenceEditor(feature, null, s.body());
        } else if (feature instanceof Feature.ThroughCut c) referenceEditor(feature, c.profile(), c.body());
        else if (feature instanceof Feature.SplitBody s) {
            dimension(feature, "offset", s.plane().offset());
            referenceEditor(feature, null, s.body());
        } else if (feature instanceof Feature.Custom custom) {
            var metadata =
                    new Label(custom.providerId() + "/" + custom.type() + " · payload v" + custom.payloadVersion());
            metadata.setWrapText(true);
            form.getChildren().add(metadata);
            try (var features = featureProviders.open(pluginDirectory)) {
                var values = features.parameters(custom);
                for (var parameter : features.descriptor(custom).parameters()) {
                    var input = field(
                            "dimension-" + parameter.key(),
                            parameter.label() + " (" + parameter.unit() + ")",
                            values.get(parameter.key()),
                            FormulaField.unit(parameter.unit()));
                    var apply = button(
                            translations.text("gui.apply"),
                            "apply-" + parameter.key(),
                            () -> controller.edit(custom.id(), parameter.key(), input.declaredValue()));
                    input.setOnAction(e -> apply.fire());
                    form.getChildren().add(apply);
                }
            } catch (IOException | RuntimeException failure) {
                var message = new Label(failure.getMessage());
                message.setWrapText(true);
                message.getStyleClass().add("error");
                form.getChildren().add(message);
            }
            form.getChildren().add(button(translations.text("gui.reload_plugin_and_rebuild"), "custom-rebuild", () -> {
                inspect();
                controller.rebuild();
            }));
        }
        form.getChildren().add(featureDiagnostics);
        updateFeatureDiagnostics();
    }

    private void bodyStyleEditor(String bodyId) {
        var document = controller.snapshot().document();
        var style = document.bodyStyle(bodyId);
        var material = new ComboBox<String>();
        material.setId("body-material");
        material.getItems().setAll("PLA", "PETG", "ABS", "ASA", "TPU");
        material.setEditable(true);
        material.getEditor().setId("body-material-name");
        material.setValue(style.material());
        material.setPromptText(translations.text("body.unassigned"));
        material.getEditor().setPromptText(translations.text("body.unassigned"));
        material.setMaxWidth(Double.MAX_VALUE);
        UiHelp.install(material, translations);
        UiHelp.describe(material.getEditor(), translations.text("help.body-material"));
        var color = new ColorPicker(javafx.scene.paint.Color.web(style.color()));
        color.setId("body-color");
        color.setMaxWidth(Double.MAX_VALUE);
        UiHelp.install(color, translations);
        var apply = button(translations.text("gui.apply"), "body-style-apply", () -> {
            var c = color.getValue();
            String rgb = String.format(
                    Locale.ROOT,
                    "#%02X%02X%02X",
                    Math.round(c.getRed() * 255),
                    Math.round(c.getGreen() * 255),
                    Math.round(c.getBlue() * 255));
            controller.bodyStyle(bodyId, new BodyStyle(material.getEditor().getText(), rgb));
        });
        var reset =
                button(translations.text("body.reset"), "body-style-reset", () -> controller.bodyStyle(bodyId, null));
        reset.setDisable(!document.bodyStyles().containsKey(bodyId));
        material.getEditor().setOnAction(e -> apply.fire());
        var materialLabel = new Label(translations.text("body.material"));
        materialLabel.setLabelFor(material);
        var colorLabel = new Label(translations.text("body.color"));
        colorLabel.setLabelFor(color);
        var hint = new Label(translations.text("body.cadHint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("muted");
        var content = new VBox(6, materialLabel, material, colorLabel, color, new HBox(6, apply, reset), hint);
        // Properties remain readable while rebuilding; editing requires a current body.
        content.setDisable(
                !controller.canExport() || controller.build().bodies().stream().noneMatch(b -> b.id().equals(bodyId)));
        var section = new TitledPane(translations.text("body.appearance"), content);
        section.setCollapsible(false);
        form.getChildren().add(section);
    }

    private void updateFeatureDiagnostics() {
        featureDiagnostics.getChildren().clear();
        controller.build().diagnostics().stream()
                .filter(d -> d.featureId().equals(selectedFeature) && d.state() != Evaluation.State.OK)
                .forEach(d -> {
                    var label = new Label(d.state() + ": " + d.message());
                    label.setWrapText(true);
                    label.getStyleClass().add("error");
                    featureDiagnostics.getChildren().add(label);
                });
    }

    private void dimension(Feature feature, String name, double value) {
        var field = field("dimension-" + name, translations.textOrDefault("dimension." + name, name) + " (mm)", value);
        String expression = controller
                .snapshot()
                .document()
                .parameters()
                .binding(new dev.voxelbrew.engine.parameters.ParameterSet.Target(feature.id(), name));
        if (!expression.isEmpty()) field.setText(expression);
        var apply = button(translations.text("gui.apply"), "apply-" + name, () -> {
            String text = field.getText().strip();
            if (text.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?"))
                controller.edit(feature.id(), name, number(field, name));
            else controller.expression(feature.id(), name, text);
        });
        field.setOnAction(e -> apply.fire());
        form.getChildren().add(apply);
    }

    private void customFeature() {
        cancelTool();
        try {
            FeatureDialog.show(stage, pluginDirectory, featureProviders, formulaParameters(), translations)
                    .ifPresent(feature -> {
                        controller.add(feature);
                        selectedFeature = feature.id();
                        selectedBody = feature.id() + ".body";
                        fitNext = true;
                        inspect();
                    });
        } catch (IOException failure) {
            error.setText(failure.getMessage());
        }
    }

    private void parameters() {
        cancelTool();
        ParameterDialog.show(
                        stage,
                        controller.effectiveFeatures(),
                        controller.snapshot().document().parameters(),
                        translations)
                .ifPresent(controller::parameters);
    }

    private void referenceEditor(Feature feature, Feature.ProfileRef profile, Feature.BodyRef body) {
        var earlier = controller.snapshot().document().features().stream()
                .takeWhile(f -> !f.id().equals(feature.id()))
                .toList();
        var sketches = new ComboBox<String>();
        sketches.setId("repair-sketch");
        earlier.stream()
                .filter(f -> f instanceof Feature.Sketch)
                .forEach(f -> sketches.getItems().add(f.id()));
        if (profile != null) {
            sketches.setValue(profile.sketchId());
            form.getChildren().addAll(new Label(translations.text("gui.input_sketch")), sketches);
        }
        var bodies = new ComboBox<String>();
        bodies.setId("repair-body");
        var output = new TextField(body == null ? "body" : body.outputName());
        output.setId("repair-output");
        if (body != null) {
            earlier.stream()
                    .filter(f -> !(f instanceof Feature.Sketch))
                    .forEach(f -> bodies.getItems().add(f.id()));
            bodies.setValue(body.featureId());
            form.getChildren()
                    .addAll(
                            new Label(translations.text("gui.input_body_feature")),
                            bodies,
                            new Label(translations.text("gui.named_output_body_negative_or_positive")),
                            output);
        }
        form.getChildren().add(button(translations.text("gui.repair_inputs"), "repair", () -> {
            var repairedBody = body == null
                    ? null
                    : new Feature.BodyRef(
                            Objects.requireNonNull(bodies.getValue(), translations.text("gui.choose_an_earlier_body")),
                            output.getText());
            if (feature instanceof Feature.EdgeFinish finish) {
                controller.replaceFeature(new Feature.EdgeFinish(
                        finish.id(), repairedBody, finish.kind(), finish.size(), finish.edges()));
                return;
            }
            if (feature instanceof Feature.Shell shell) {
                controller.replaceFeature(
                        new Feature.Shell(shell.id(), repairedBody, shell.thickness(), shell.removedFace()));
                return;
            }
            if (feature instanceof Feature.SplitBody split) {
                controller.replaceFeature(new Feature.SplitBody(split.id(), repairedBody, split.plane()));
                return;
            }
            var s = (Feature.Sketch) earlier.stream()
                    .filter(f -> f instanceof Feature.Sketch && f.id().equals(sketches.getValue()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(translations.text("gui.choose_an_earlier_sketch")));
            var ref = new Feature.ProfileRef(s.id(), s.profileId());
            controller.replaceFeature(
                    feature instanceof Feature.Extrude e
                            ? new Feature.Extrude(e.id(), ref, e.distance())
                            : new Feature.ThroughCut(
                                    feature.id(),
                                    ref,
                                    new Feature.BodyRef(
                                            Objects.requireNonNull(
                                                    bodies.getValue(), translations.text("gui.choose_an_earlier_body")),
                                            output.getText())));
        }));
    }

    private ParameterSet formulaParameters() {
        return controller.snapshot().document().parameters();
    }

    private FormulaField field(String id, String label, double initial) {
        return field(id, label, initial, ParameterUnit.MM);
    }

    private FormulaField field(String id, String label, double initial, ParameterUnit unit) {
        var text = FormulaField.number(Double.toString(initial), this::formulaParameters, unit)
                .localized(translations);
        text.setId(id);
        fields.put(id, text);
        form.getChildren().addAll(new Label(label), text);
        return text;
    }

    private static double number(TextField field, String name) {
        try {
            double value = field instanceof FormulaField formula
                    ? formula.value()
                    : Double.parseDouble(field.getText().trim());
            if (!Double.isFinite(value)) throw new NumberFormatException();
            field.getStyleClass().remove("invalid");
            return value;
        } catch (IllegalArgumentException failure) {
            if (!field.getStyleClass().contains("invalid"))
                field.getStyleClass().add("invalid");
            throw new IllegalArgumentException(name + ": " + failure.getMessage(), failure);
        }
    }

    private double value(String key) {
        return number(fields.get(key), key);
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private void editSketch(Feature.Sketch sketch) {
        openSketch(sketch, sketch.plane());
    }

    private void beginPlaneSelection(Feature.Sketch repair) {
        cancelTool();
        if (controller.ioBusy() || controller.rebuilding()) return;
        planeSnapshot = controller.snapshot();
        planeRepair = repair;
        tool = Tool.PLANE;
        selectionLabel.setText(translations.text("plane.choose"));
        form.getChildren().clear();
        fields.clear();
        var hint = new Label(translations.text("plane.hint"));
        hint.setWrapText(true);
        hint.setId("sketch-plane-hint");
        form.getChildren().add(hint);
        var origins = new HBox(6);
        for (var name : List.of("XY", "XZ", "YZ"))
            origins.getChildren()
                    .add(button(
                            name,
                            "choose-plane-" + name.toLowerCase(Locale.ROOT),
                            () -> choosePlane(Feature.Plane.origin(name))));
        form.getChildren().add(origins);
        toolActions.getChildren().setAll(button(translations.text("action.cancel"), "cancel-tool", this::cancelTool));
        viewport.pickSketchPlane(this::choosePlane, (id, face) -> {
            boolean flat = controller.build().bodies().stream()
                    .filter(b -> b.id().equals(id))
                    .flatMap(b -> b.planarFaces().stream())
                    .anyMatch(p -> p.faceIndex() == face);
            viewport.highlightFace(id, flat ? face : -1);
        });
        log.info("Selecting a sketch plane{}", repair == null ? "" : " for " + repair.id());
        refresh();
    }

    private boolean planeSelectionCurrent() {
        var current = controller.snapshot();
        return planeSnapshot != null
                && !controller.rebuilding()
                && !controller.ioBusy()
                && current.revision() == planeSnapshot.revision()
                && current.document().id().equals(planeSnapshot.document().id());
    }

    private void chooseBodyPlane(String id, int face) {
        if (!planeSelectionCurrent()) {
            cancelTool();
            return;
        }
        var body = controller.build().bodies().stream()
                .filter(b -> b.id().equals(id))
                .findFirst();
        if (body.isEmpty() || body.get().planarFaces().stream().noneMatch(p -> p.faceIndex() == face)) {
            error.setText(translations.text("plane.flat_only"));
            return;
        }
        if (planeRepair != null) {
            var features = planeSnapshot.document().features();
            int sketchIndex = java.util.stream.IntStream.range(0, features.size())
                    .filter(i -> features.get(i).id().equals(planeRepair.id()))
                    .findFirst()
                    .orElse(-1);
            int bodyIndex = java.util.stream.IntStream.range(0, features.size())
                    .filter(i -> features.get(i).id().equals(body.get().output().featureId()))
                    .findFirst()
                    .orElse(Integer.MAX_VALUE);
            if (bodyIndex >= sketchIndex) {
                error.setText(translations.text("plane.upstream_only"));
                return;
            }
        }
        choosePlane(FaceSupport.attach(
                body.get().output(),
                body.get().planarFaces(),
                face,
                controller.snapshot().document().uuidGenerator()));
    }

    private void choosePlane(Feature.Plane plane) {
        if (tool != Tool.PLANE || !planeSelectionCurrent()) {
            cancelTool();
            return;
        }
        var repair = planeRepair;
        log.info(
                "Selected sketch plane {}{}",
                plane.id(),
                plane.support() == null ? "" : " on " + plane.support().body());
        cancelTool();
        if (repair == null) {
            openSketch(null, plane);
        } else {
            // Keep identities, geometry and parameter bindings; reattachment is one undoable edit.
            var placement = plane.withOffset(repair.plane().offsetZ());
            boolean boundOffset = controller.snapshot().document().parameters().bindings().stream()
                    .anyMatch(b -> b.target().featureId().equals(repair.id())
                            && b.target().field().equals("offsetZ"));
            if (placement.isOrigin() && (placement.offsetZ() != 0 || boundOffset))
                placement = new Feature.Plane(
                        "plane-"
                                + controller
                                        .snapshot()
                                        .document()
                                        .uuidGenerator()
                                        .next(),
                        placement.offsetZ(),
                        placement.frame(),
                        null);
            controller.sketch(
                    new Feature.Sketch(repair.id(), repair.profileId(), placement, repair.profile()),
                    controller.snapshot().document().parameters(),
                    false);
            selectedFeature = repair.id();
            selectedBody = null;
            refresh();
        }
    }

    private void openSketch(Feature.Sketch sketch, Feature.Plane plane) {
        cancelTool();
        var before = controller.snapshot();
        if (controller.ioBusy() || controller.rebuilding())
            throw new IllegalArgumentException(translations.text("gui.wait_for_the_current_rebuild_or_file_operation"));
        SketchEditor.show(
                stage,
                sketches,
                sketch,
                plane,
                controller.effectiveFeatures(),
                before.document().parameters(),
                translations,
                (result, parameters) -> {
                    var current = controller.snapshot();
                    if (!before.document().id().equals(current.document().id())
                            || before.revision() != current.revision())
                        throw new IllegalStateException(translations.text(
                                "gui.the_document_changed_while_editing_cancel_and_reopen_the_sketch"));
                    controller.sketch(result, parameters, sketch == null);
                    selectedFeature = result.id();
                    selectedBody = null;
                    inspect();
                    refresh();
                });
    }

    private void beginExtrusion() {
        var feature = selected();
        if (!(feature instanceof Feature.Sketch sketch))
            throw new IllegalArgumentException(translations.text("gui.select_a_closed_sketch_profile_first"));
        cancelTool();
        extrusionSketch = sketch;
        tool = Tool.EXTRUDE;
        selectionLabel.setText(translations.text("gui.extrude_profile"));
        form.getChildren().clear();
        fields.clear();
        var distance = field("extrude-distance", translations.text("gui.signed_distance_mm"), 5);
        finish = button(translations.text("gui.commit"), "commit-extrusion", this::commitExtrusion);
        finish.getStyleClass().add("primary");
        toolActions
                .getChildren()
                .setAll(finish, button(translations.text("action.cancel"), "cancel-tool", this::cancelTool));
        var hint = new Label(
                translations.text("gui.drag_the_gold_handle_or_type_a_distance_enter_commits_escape_cancels"));
        hint.setWrapText(true);
        form.getChildren().add(hint);
        distance.textProperty().addListener((o, a, b) -> {
            try {
                double value = controller
                        .snapshot()
                        .document()
                        .parameters()
                        .value(distance.getText(), dev.voxelbrew.engine.parameters.ParameterUnit.MM);
                controller.previewExtrusion(sketch, distance.getText().strip());
                viewport.updateExtrusionDistance(value);
                finish.setDisable(false);
                error.setText("");
            } catch (RuntimeException failure) {
                finish.setDisable(true);
                error.setText(failure.getMessage());
            }
        });
        distance.setOnAction(e -> {
            if (!finish.isDisabled()) finish.fire();
        });
        UiText.choices(
                configuration, name -> name.equals("Default") ? translations.text("configuration.default") : name);
        viewport.standardView("Isometric");
        controller.previewExtrusion(sketch, 5);
        viewport.extrusionHandle(sketch, 5, v -> distance.setText(format(v)), () -> {
            if (!finish.isDisabled()) commitExtrusion();
        });
        distance.requestFocus();
        distance.selectAll();
        refresh();
    }

    private void commitExtrusion() {
        controller.commitPreview();
        tool = Tool.NONE;
        planeSnapshot = null;
        planeRepair = null;
        viewport.stopPlaneSelection();
        viewport.clearHandle();
        var features = controller.snapshot().document().features();
        selectedFeature = features.getLast().id();
        inspect();
        refresh();
    }

    private void beginCut() {
        if (!(selected() instanceof Feature.Sketch sketch)
                || !(sketch.profile() instanceof Feature.Circle || sketch.profile() instanceof Feature.Constrained))
            throw new IllegalArgumentException(translations.text("gui.select_a_closed_sketch_profile"));
        var choices = controller.build().bodies();
        if (choices.isEmpty()) throw new IllegalArgumentException(translations.text("gui.a_target_body_is_required"));
        var dialog = new ChoiceDialog<>(
                choices.getFirst().id(),
                choices.stream().map(DocumentController.Body::id).toList());
        dialog.setTitle(translations.text("gui.through_all_cut"));
        dialog.setHeaderText(translations.text("gui.choose_the_target_body"));
        dialog.getDialogPane().lookupButton(ButtonType.OK).setId("cut-confirm");
        Dialogs.show(dialog, stage, translations).ifPresent(id -> {
            var target =
                    choices.stream().filter(b -> b.id().equals(id)).findFirst().orElseThrow();
            controller.add(new Feature.ThroughCut(
                    "cut-" + controller.snapshot().document().uuidGenerator().next(),
                    new Feature.ProfileRef(sketch.id(), sketch.profileId()),
                    target.output()));
            tool = Tool.NONE;
            selectedFeature =
                    controller.snapshot().document().features().getLast().id();
            inspect();
            refresh();
        });
    }

    private void beginFinish(boolean rounded) {
        beginSolidModification(rounded ? "action.fillet" : "action.chamfer");
    }

    private void beginShell() {
        beginSolidModification("action.shell");
    }

    private void beginSolidModification(String title) {
        cancelTool();
        var bodies = controller.build().bodies();
        if (bodies.isEmpty()) return;
        tool = Tool.MODIFY;
        modification = title;
        modificationBody = selectedBody != null && bodies.stream().anyMatch(b -> b.id().equals(selectedBody))
                ? selectedBody
                : bodies.getFirst().id();
        modificationFaces.clear();
        modificationEdges.clear();
        viewport.pickEdges(!title.equals("action.shell"));
        form.getChildren().clear();
        fields.clear();
        error.setText("");
        selectionLabel.setText(translations.text(title));
        modificationSelection = new Label(translations.text("gui.choose_faces_in_viewport"));
        modificationSelection.setWrapText(true);
        form.getChildren().add(modificationSelection);
        var size = field(
                "modify-size", translations.text(title.equals("action.shell") ? "gui.thickness_mm" : "gui.size_mm"), 1);
        finish = button(translations.text("gui.commit"), "commit-modification", () -> {
            try {
                if (modificationFaces.isEmpty() && modificationEdges.isEmpty())
                    throw new IllegalArgumentException(translations.text("gui.choose_faces_in_viewport"));
                var body = controller.build().bodies().stream()
                        .filter(b -> b.id().equals(modificationBody))
                        .findFirst()
                        .orElseThrow();
                double amount = number(size, "size");
                String id = "modify-"
                        + controller.snapshot().document().uuidGenerator().next();
                Feature feature;
                if (modification.equals("action.shell")) {
                    if (modificationFaces.size() != 1)
                        throw new IllegalArgumentException(translations.text("gui.choose_one_face"));
                    feature = new Feature.Shell(
                            id,
                            body.output(),
                            amount,
                            modificationFaces.iterator().next() + 1);
                } else {
                    var edges = new java.util.TreeSet<Integer>();
                    edges.addAll(modificationEdges);
                    modificationFaces.forEach(face -> edges.addAll(body.edgesOnFace(face)));
                    if (edges.isEmpty())
                        throw new IllegalArgumentException(translations.text("gui.choose_faces_in_viewport"));
                    feature = new Feature.EdgeFinish(
                            id,
                            body.output(),
                            modification.equals("action.fillet")
                                    ? Feature.EdgeFinish.Kind.FILLET
                                    : Feature.EdgeFinish.Kind.CHAMFER,
                            amount,
                            java.util.List.copyOf(edges));
                }
                controller.add(feature);
                selectedFeature = id;
                tool = Tool.NONE;
                modificationFaces.clear();
                modificationEdges.clear();
                viewport.pickEdges(false);
                viewport.highlightSelectedEdges(null, java.util.Set.of());
                inspect();
                refresh();
            } catch (RuntimeException failure) {
                error.setText(failure.getMessage());
            }
        });
        finish.getStyleClass().add("primary");
        toolActions
                .getChildren()
                .setAll(finish, button(translations.text("action.cancel"), "cancel-tool", this::cancelTool));
        size.setOnAction(e -> finish.fire());
        viewport.select(modificationBody);
        refresh();
    }

    private void selectModificationFace(String id, int face) {
        if (face < 0) return;
        if (!id.equals(modificationBody)) {
            modificationBody = id;
            modificationFaces.clear();
            modificationEdges.clear();
        }
        if (!modificationFaces.add(face)) modificationFaces.remove(face);
        viewport.select(id);
        viewport.highlightFace(id, face);
        updateModificationSelection();
    }

    private void selectModificationEdge(String id, int edge) {
        if (tool != Tool.MODIFY) return;
        if (!id.equals(modificationBody)) {
            modificationBody = id;
            modificationFaces.clear();
            modificationEdges.clear();
        }
        if (!modificationEdges.add(edge)) modificationEdges.remove(edge);
        viewport.select(id);
        updateModificationSelection();
    }

    private void updateModificationSelection() {
        var body = controller.build().bodies().stream()
                .filter(b -> b.id().equals(modificationBody))
                .findFirst()
                .orElse(null);
        var edges = new java.util.TreeSet<Integer>(modificationEdges);
        if (body != null) modificationFaces.forEach(face -> edges.addAll(body.edgesOnFace(face)));
        viewport.highlightSelectedEdges(modificationBody, edges);
        modificationSelection.setText(
                translations.text("gui.selected_geometry", modificationEdges.size(), modificationFaces.size()));
    }

    private void beginSplit() {
        cancelTool();
        var dialog = new Dialog<Void>();
        dialog.setTitle(translations.text("action.split"));
        var body = new ComboBox<DocumentController.Body>();
        body.setId("split-input");
        body.getItems().setAll(controller.build().bodies());
        body.setConverter(new javafx.util.StringConverter<>() {
            public String toString(DocumentController.Body value) {
                return value == null ? "" : value.id();
            }

            public DocumentController.Body fromString(String value) {
                throw new UnsupportedOperationException();
            }
        });
        body.setValue(body.getItems().stream()
                .filter(b -> b.id().equals(selectedBody))
                .findFirst()
                .orElse(body.getItems().getFirst()));
        var axis = new ComboBox<String>();
        axis.setId("split-axis");
        axis.getItems().setAll("X", "Y", "Z");
        axis.setValue("X");
        var offset = FormulaField.number("30", this::formulaParameters, ParameterUnit.MM)
                .localized(translations);
        offset.setId("split-offset");
        var error = new Label();
        error.setWrapText(true);
        error.setId("split-error");
        dialog.getDialogPane()
                .setContent(new VBox(
                        8,
                        new Label(translations.text("gui.input_body")),
                        body,
                        new Label(translations.text("gui.plane_normal")),
                        axis,
                        new Label(translations.text("gui.offset_mm")),
                        offset,
                        error));
        var commit = new ButtonType(translations.text("gui.split"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(commit, ButtonType.CANCEL);
        var button = dialog.getDialogPane().lookupButton(commit);
        button.setId("split-confirm");
        button.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            try {
                String id = "split-"
                        + controller.snapshot().document().uuidGenerator().next();
                var feature = new Feature.SplitBody(
                        id,
                        Objects.requireNonNull(body.getValue(), translations.text("gui.choose_a_body"))
                                .output(),
                        new Feature.SplitPlane(
                                axis.getValue().equals("X") ? 1 : 0,
                                axis.getValue().equals("Y") ? 1 : 0,
                                axis.getValue().equals("Z") ? 1 : 0,
                                number(offset, "offset")));
                var parameters = formulaParameters();
                if (!Expressions.isNumber(offset.getText()))
                    parameters = parameters.bind(new ParameterSet.Target(id, "offset"), offset.getText());
                controller.add(feature, parameters);
                selectedFeature = id;
            } catch (RuntimeException failure) {
                error.setText(failure.getMessage());
                event.consume();
            }
        });
        Dialogs.show(dialog, stage, translations);
    }

    private void cancelTool() {
        if (timeline != null) timeline.stop();
        tool = Tool.NONE;
        modificationFaces.clear();
        modificationEdges.clear();
        viewport.pickEdges(false);
        viewport.highlightSelectedEdges(null, java.util.Set.of());
        modification = null;
        modificationBody = null;
        planeSnapshot = null;
        planeRepair = null;
        viewport.stopPlaneSelection();
        viewport.stopDrawing();
        viewport.clearHandle();
        controller.cancelPreview();
        extrusionSketch = null;
        inspect();
        refresh();
    }

    private void open() {
        FileDialog.choose(stage, false, controller.path(), formulaParameters(), translations, configurationStore)
                .ifPresent(path -> {
                    cancelTool();
                    controller.open(path).exceptionally(failure -> null);
                });
    }

    private CompletableFuture<Void> save(boolean saveAs) {
        if (controller.ioBusy())
            return CompletableFuture.failedFuture(
                    new IllegalStateException(translations.text("gui.file_operation_in_progress")));
        Optional<Path> chosen = !saveAs && controller.path() != null
                ? Optional.of(controller.path())
                : FileDialog.choose(
                        stage, true, controller.path(), formulaParameters(), translations, configurationStore);
        if (chosen.isEmpty()) return CompletableFuture.failedFuture(new java.util.concurrent.CancellationException());
        Path path = chosen.get();
        boolean existing = Files.exists(path);
        if (existing
                && (saveAs || controller.path() == null || !path.equals(controller.path()))
                && !confirmOverwrite(path))
            return CompletableFuture.failedFuture(new java.util.concurrent.CancellationException());
        return controller.save(path, existing);
    }

    private boolean confirmOverwrite(Path path) {
        var alert = new Alert(
                Alert.AlertType.CONFIRMATION, translations.text("file.replace", path), ButtonType.YES, ButtonType.NO);
        return Dialogs.show(alert, stage, translations).orElse(ButtonType.NO) == ButtonType.YES;
    }

    private void saveFolder() {
        try {
            var dialog = new Dialog<String>();
            var folder = new TextField(configurationStore.get("save.folder", ""));
            folder.setId("save-folder-path");
            folder.setPromptText("~/VoxelBrew");
            var browse = new Button(translations.text("gui.browse"));
            browse.setId("save-folder-browse");
            UiHelp.install(browse, translations);
            browse.setOnAction(event -> {
                var chooser = new javafx.stage.DirectoryChooser();
                chooser.setTitle(translations.text("saveFolder.title"));
                try {
                    String value = folder.getText().strip();
                    Path home = Path.of(System.getProperty("user.home"));
                    Path initial = value.isEmpty()
                            ? home.resolve("VoxelBrew")
                            : value.equals("~")
                                    ? home
                                    : value.startsWith("~/") || value.startsWith("~\\")
                                            ? home.resolve(value.substring(2))
                                            : Path.of(value);
                    initial = initial.toAbsolutePath().normalize();
                    while (initial != null && !Files.isDirectory(initial)) initial = initial.getParent();
                    if (initial != null) chooser.setInitialDirectory(initial.toFile());
                } catch (IllegalArgumentException ignored) {
                    // Invalid typed paths can still be replaced with a folder from the picker.
                }
                var selected = chooser.showDialog(stage);
                if (selected != null) folder.setText(selected.toString());
            });
            dialog.getDialogPane().setContent(Dialogs.pathRow(folder, browse));
            dialog.getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
            dialog.setResultConverter(button -> button == ButtonType.OK ? folder.getText() : null);
            dialog.setTitle(translations.text("saveFolder.title"));
            dialog.setHeaderText(translations.text("saveFolder.hint"));
            Dialogs.show(dialog, stage, translations).ifPresent(value -> {
                preferencesWorker.execute(() -> {
                    try {
                        if (value.isBlank()) configurationStore.remove("save.folder");
                        else {
                            Path.of(value.strip());
                            configurationStore.set("save.folder", value.strip());
                        }
                    } catch (java.io.IOException | IllegalArgumentException failure) {
                        Platform.runLater(() -> Dialogs.show(
                                new Alert(Alert.AlertType.ERROR, failure.getMessage(), ButtonType.OK),
                                stage,
                                translations));
                    }
                });
            });
        } catch (java.io.IOException failure) {
            Dialogs.show(new Alert(Alert.AlertType.ERROR, failure.getMessage(), ButtonType.OK), stage, translations);
        }
    }

    private void updates() {
        if (UpdateDialog.show(stage, updates, this::requestChange)) {
            close();
            stage.hide();
            Platform.exit();
        }
    }

    private void requestChange(Runnable action) {
        if (controller.ioBusy()) {
            error.setText(translations.text("gui.wait_for_the_current_file_operation"));
            return;
        }
        if (!controller.dirty()) {
            action.run();
            return;
        }
        var save = new ButtonType(translations.text("action.save"), ButtonBar.ButtonData.YES);
        var discard = new ButtonType(translations.text("gui.discard"), ButtonBar.ButtonData.NO);
        var alert = new Alert(
                Alert.AlertType.CONFIRMATION,
                translations.text("gui.save_changes_to_this_design"),
                save,
                discard,
                ButtonType.CANCEL);
        alert.setHeaderText(translations.text("gui.unsaved_changes"));
        alert.getDialogPane().lookupButton(save).setId("unsaved-save");
        alert.getDialogPane().lookupButton(discard).setId("unsaved-discard");
        alert.getDialogPane().lookupButton(ButtonType.CANCEL).setId("unsaved-cancel");
        var choice = Dialogs.show(alert, stage, translations).orElse(ButtonType.CANCEL);
        if (choice == discard) action.run();
        else if (choice == save)
            save(false).thenRun(() -> requestChange(action)).exceptionally(failure -> null);
    }

    private void applyAppearance() {
        long request = ++appearanceRequest;
        var choice = appearanceChoice;
        if (choice != Appearance.Choice.SYSTEM) {
            setDark(choice == Appearance.Choice.DARK);
            return;
        }
        CompletableFuture.supplyAsync(Appearance::systemDark, preferencesWorker)
                .thenAccept(dark -> Platform.runLater(() -> {
                    if (!closed && request == appearanceRequest) setDark(dark);
                }));
    }

    private void setDark(boolean dark) {
        getStyleClass().removeAll("light", "dark");
        getStyleClass().add(dark ? "dark" : "light");
        window.getStyleClass().removeAll("light", "dark");
        window.getStyleClass().add(dark ? "dark" : "light");
        viewport.setDark(dark);
        printing.setDark(dark);
    }

    /**
     * Completes after all window workers drain and plugin JAR handles are released. Wait off the
     * JavaFX thread before replacing/removing plugin files.
     */
    public java.util.concurrent.CompletionStage<Void> whenClosed() {
        return resources.whenClosed();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        timeline.close();
        window.close();
        stage.focusedProperty().removeListener(windowFocus);
        printing.close();
        controller.close();
        preferencesWorker.shutdown();
        resources.close();
    }
}
