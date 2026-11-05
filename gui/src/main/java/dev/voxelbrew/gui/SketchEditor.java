package dev.voxelbrew.gui;

import static dev.voxelbrew.engine.sketch.SketchGeometry.*;

import dev.voxelbrew.engine.model.Feature;
import dev.voxelbrew.engine.sketch.*;
import java.util.*;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;

/**
 * Window-local 2D sketch workspace; Finish publishes one engine transaction, Cancel publishes none.
 */
final class SketchEditor extends BorderPane implements AutoCloseable {
    enum Draw {
        SELECT,
        POINT,
        LINE,
        RECTANGLE,
        CIRCLE,
        ARC,
        SPLINE,
        POLYGON,
        ADVANCED
    }

    private record Hit(Ref ref, Vec position) {}

    private record Badge(Constraint constraint, double x, double y) {}

    private final SketchController controller;
    private final Canvas canvas = new Canvas();
    private final LinkedHashSet<Ref> selection = new LinkedHashSet<>();
    private final List<Vec> drawing = new ArrayList<>();
    private final List<Ref> snaps = new ArrayList<>();
    private final List<Badge> badges = new ArrayList<>();
    private final ListView<Constraint> constraints = new ListView<>();
    private final ComboBox<Ref> references = new ComboBox<>();
    private final ComboBox<Type> dimension = new ComboBox<>();
    private final ComboBox<SketchTools.Tool> createType = new ComboBox<>();
    private SketchTools.Tool advanced;
    private final VBox toolOptions = new VBox(6);
    private final TextField toolRadius = new TextField("5"),
            toolRho = new TextField("0.5"),
            textContent = new TextField("Text"),
            textFont = new TextField("SansSerif"),
            textHeight = new TextField("10"),
            textAngle = new TextField("0"),
            textSpacing = new TextField("0"),
            pointX = new TextField("0"),
            pointY = new TextField("0");
    private final ComboBox<Integer> splineDegree = new ComboBox<>();
    private final ComboBox<String> textStyle = new ComboBox<>();
    private final CheckBox closedCurve = new CheckBox("Closed curve");
    private final TextField dimensionValue = new TextField("10"),
            plane = new TextField("0"),
            sides = new TextField("6");
    private final CheckBox construction = new CheckBox("Construction"),
            auto = new CheckBox("Auto constraints"),
            showBadges = new CheckBox("Show badges");
    private final Label
            selected = new Label("Select a curve or point. Shift-click adds to the selection."),
            status = new Label("Solving…"),
            error = new Label(),
            hint = new Label();
    private final Button finish = button("Finish sketch", "constraint-finish", this::finish),
            undo = button("Undo", "sketch-undo", () -> controllerUndo(false)),
            redo = button("Redo", "sketch-redo", () -> controllerUndo(true));
    private final Consumer<Feature.Sketch> accept;
    private final Feature.Sketch original;
    private Runnable dismiss = () -> {};
    private Draw tool = Draw.SELECT;
    private Type activeConstraint;
    private Ref dragPoint;
    private Vec cursor = new Vec(0, 0);
    private double centerX = 30, centerY = 20, scale = 4, lastX, lastY, pressX, pressY;
    private boolean moving, panning, refreshing, closed;
    private final boolean dark;
    private int nextId = 1;

    static void show(Stage stage, Feature.Sketch sketch, Consumer<Feature.Sketch> accept) {
        var scene = stage.getScene();
        Parent previous = scene.getRoot();
        Node previousFocus = scene.getFocusOwner();
        var previousClose = stage.getOnCloseRequest();
        var editor = new SketchEditor(sketch, previous.getStyleClass().contains("dark"), accept);
        editor.getStylesheets().setAll(previous.getStylesheets());
        editor.getStyleClass().addAll(previous.getStyleClass());
        var host = new StackPane();
        scene.setRoot(host);
        host.getChildren().addAll(previous, editor);
        StackPane.setMargin(editor, new Insets(8));
        boolean disabled = previous.isDisable();
        previous.setDisable(true);
        javafx.event.EventHandler<WindowEvent> hidden = event -> editor.close();
        stage.addEventHandler(WindowEvent.WINDOW_HIDDEN, hidden);
        editor.dismiss =
                () -> {
                    editor.close();
                    stage.removeEventHandler(WindowEvent.WINDOW_HIDDEN, hidden);
                    stage.setOnCloseRequest(previousClose);
                    host.getChildren().clear();
                    scene.setRoot(previous);
                    previous.setDisable(disabled);
                    if (previousFocus != null) previousFocus.requestFocus();
                };
        stage.setOnCloseRequest(
                event -> {
                    event.consume();
                    editor.dismiss.run();
                });
        Platform.runLater(
                () -> {
                    host.applyCss();
                    host.layout();
                    editor.fit();
                    editor.canvas.requestFocus();
                });
    }

    private SketchEditor(Feature.Sketch sketch, boolean dark, Consumer<Feature.Sketch> accept) {
        this.original = sketch;
        this.dark = dark;
        this.accept = accept;
        var geometry =
                sketch == null ? SketchGeometry.empty() : Sketches.fromProfile(sketch.profile());
        controller = new SketchController(geometry, Platform::runLater);
        setId("sketch-editor");
        getStyleClass().add("sketch-editor");
        plane.setId("constraint-plane");
        plane.setPrefColumnCount(5);
        if (sketch != null) plane.setText(number(sketch.plane().offsetZ()));
        plane.setDisable(
                sketch != null); // Shared planes remain edited through the document inspector.
        var title = new Label(sketch == null ? "Create constrained sketch" : "Edit sketch");
        title.getStyleClass().add("selection-title");
        var space = new Region();
        HBox.setHgrow(space, Priority.ALWAYS);
        finish.getStyleClass().add("primary");
        var header =
                new HBox(
                        10,
                        title,
                        new Label("Plane Z (mm)"),
                        plane,
                        space,
                        finish,
                        button("Cancel", "constraint-cancel", () -> dismiss.run()));
        header.setAlignment(Pos.CENTER_LEFT);
        var tools = new FlowPane(5, 5);
        for (var draw : Draw.values()) {
            if (draw == Draw.ADVANCED) continue;
            var button =
                    button(
                            title(draw.name()),
                            "sketch-tool-" + draw.name().toLowerCase(Locale.ROOT),
                            () -> tool(draw));
            tools.getChildren().add(button);
        }
        createType.setId("sketch-create-type");
        createType.setPromptText("More sketch types…");
        createType.setAccessibleText("More sketch types");
        createType.setButtonCell(
                new ListCell<>() {
                    @Override
                    protected void updateItem(SketchTools.Tool item, boolean empty) {
                        super.updateItem(item, empty);
                        setText(item == null ? "More sketch types…" : item.toString());
                    }
                });
        createType.setPrefWidth(185);
        createType.getItems().setAll(SketchTools.Tool.values());
        createType.setOnAction(
                event -> {
                    if (createType.getValue() == null) return;
                    advanced = createType.getValue();
                    var refs = List.copyOf(selection);
                    tool(Draw.ADVANCED);
                    selection.addAll(refs);
                    selectionChanged();
                });
        tools.getChildren().add(createType);
        splineDegree.getItems().setAll(3, 5);
        splineDegree.setValue(3);
        splineDegree.setId("sketch-spline-degree");
        textStyle.getItems().setAll("Regular", "Bold", "Italic", "Bold italic");
        textStyle.getSelectionModel().select(0);
        textStyle.setId("sketch-text-style");
        closedCurve.setId("sketch-closed-curve");
        toolRadius.setId("sketch-tool-radius");
        toolRho.setId("sketch-tool-rho");
        textContent.setId("sketch-text-content");
        textFont.setId("sketch-text-font");
        textHeight.setId("sketch-text-height");
        textAngle.setId("sketch-text-angle");
        textSpacing.setId("sketch-text-spacing");
        pointX.setId("sketch-point-x");
        pointY.setId("sketch-point-y");
        sides.setId("sketch-polygon-sides");
        sides.setPrefColumnCount(2);
        construction.setId("sketch-construction");
        auto.setId("sketch-auto");
        auto.setSelected(true);
        tools.getChildren()
                .addAll(
                        new Label("Sides"),
                        sides,
                        construction,
                        auto,
                        undo,
                        redo,
                        button("Fit", "sketch-fit", this::fit));
        var menu = new MenuButton("Constraints");
        menu.setId("sketch-constraint-menu");
        var constraintBar = new FlowPane(4, 4, menu);
        for (var type : Type.values()) {
            if (type.dimension()) continue;
            String label = type == Type.FIX ? "Fix / Unfix" : type.toString();
            var item = new MenuItem(label);
            item.setId("constraint-menu-" + type.name().toLowerCase(Locale.ROOT));
            item.setOnAction(e -> request(type));
            menu.getItems().add(item);
            var action =
                    button(
                            symbol(type),
                            "constraint-" + type.name().toLowerCase(Locale.ROOT),
                            () -> request(type));
            action.getStyleClass().add("constraint-icon");
            action.setAccessibleText(label);
            action.setTooltip(new Tooltip(label + " · " + SketchConstraints.selectionHint(type)));
            constraintBar.getChildren().add(action);
        }
        hint.setWrapText(true);
        hint.getStyleClass().add("muted");
        setTop(new VBox(9, header, tools, constraintBar, hint));
        BorderPane.setMargin(getTop(), new Insets(0, 0, 8, 0));
        canvas.setId("sketch-canvas");
        canvas.setFocusTraversable(true);
        var canvasHost = new StackPane(canvas);
        canvasHost.setMinSize(250, 200);
        canvas.widthProperty().bind(canvasHost.widthProperty());
        canvas.heightProperty().bind(canvasHost.heightProperty());
        canvas.widthProperty().addListener(o -> draw());
        canvas.heightProperty().addListener(o -> draw());
        setCenter(canvasHost);
        setRight(inspector());
        status.setId("sketch-solve-status");
        error.setId("sketch-error");
        error.setWrapText(true);
        error.getStyleClass().add("error");
        setBottom(new VBox(5, status, error));
        BorderPane.setMargin(getBottom(), new Insets(8, 0, 0, 0));
        controller.onChanged(this::refresh);
        installGestures();
        addEventFilter(
                KeyEvent.KEY_PRESSED,
                e -> {
                    if (e.getCode() == KeyCode.ESCAPE) {
                        if (dragPoint != null) controller.cancelDrag();
                        dragPoint = null;
                        moving = false;
                        drawing.clear();
                        snaps.clear();
                        tool(Draw.SELECT);
                        selection.clear();
                        refresh();
                        e.consume();
                    } else if (e.isShortcutDown()
                            && (e.getCode() == KeyCode.Z || e.getCode() == KeyCode.Y)) {
                        controllerUndo(e.getCode() == KeyCode.Y || e.isShiftDown());
                        e.consume();
                    } else if (e.getCode() == KeyCode.ENTER
                            && tool == Draw.ADVANCED
                            && !(getScene().getFocusOwner() instanceof TextInputControl)) {
                        finishAdvanced();
                        e.consume();
                    } else if (!(getScene().getFocusOwner() instanceof TextInputControl)) {
                        switch (e.getCode()) {
                            case L -> tool(Draw.LINE);
                            case R -> tool(Draw.RECTANGLE);
                            case C -> tool(Draw.CIRCLE);
                            case A -> tool(Draw.ARC);
                            case S -> tool(Draw.SPLINE);
                            case DELETE -> removeConstraint();
                            default -> {
                                return;
                            }
                        }
                        e.consume();
                    }
                });
        tool(Draw.SELECT);
        refresh();
    }

    private Node inspector() {
        selected.setId("sketch-selection");
        selected.setWrapText(true);
        references.setId("sketch-reference");
        references.setMaxWidth(Double.MAX_VALUE);
        references.setConverter(
                new javafx.util.StringConverter<>() {
                    public String toString(Ref r) {
                        return r == null ? "" : r.toString();
                    }

                    public Ref fromString(String value) {
                        return Ref.parse(value);
                    }
                });
        var add =
                button(
                        "Add to selection",
                        "sketch-select-ref",
                        () -> {
                            if (references.getValue() != null) {
                                selection.add(references.getValue());
                                selectionChanged();
                            }
                        });
        var clear =
                button(
                        "Clear",
                        "sketch-clear-selection",
                        () -> {
                            selection.clear();
                            activeConstraint = null;
                            selectionChanged();
                        });
        dimension.setId("sketch-dimension-type");
        dimension.getItems().setAll(Arrays.stream(Type.values()).filter(Type::dimension).toList());
        dimension.setValue(Type.LENGTH);
        dimension.setMaxWidth(Double.MAX_VALUE);
        dimensionValue.setId("sketch-dimension-value");
        dimensionValue.setOnAction(e -> dimension());
        var apply = button("Apply dimension", "sketch-apply-dimension", this::dimension);
        constraints.setId("sketch-constraints");
        constraints.setPrefHeight(140);
        constraints.setCellFactory(
                view ->
                        new ListCell<>() {
                            @Override
                            protected void updateItem(Constraint value, boolean empty) {
                                super.updateItem(value, empty);
                                setText(
                                        empty || value == null
                                                ? null
                                                : symbol(value.type())
                                                        + " "
                                                        + value.type()
                                                        + (value.type().dimension()
                                                                ? " = "
                                                                        + number(
                                                                                value.values()
                                                                                        .getFirst())
                                                                : "")
                                                        + " · "
                                                        + value.id());
                                if (!empty && value != null)
                                    setTooltip(new Tooltip(value.refs().toString()));
                            }
                        });
        constraints
                .getSelectionModel()
                .selectedItemProperty()
                .addListener(
                        (o, a, b) -> {
                            if (refreshing || b == null) return;
                            activeConstraint = null;
                            selection.clear();
                            selection.addAll(b.refs());
                            if (b.type().dimension()) {
                                dimension.setValue(b.type());
                                dimensionValue.setText(number(b.values().getFirst()));
                            }
                            selectionChanged();
                        });
        showBadges.setSelected(true);
        showBadges.setId("sketch-show-badges");
        showBadges.setOnAction(e -> draw());
        var content =
                new VBox(
                        8,
                        toolOptions,
                        new Label("SELECTED GEOMETRY"),
                        selected,
                        references,
                        new HBox(5, add, clear),
                        button(
                                "Apply active constraint",
                                "sketch-apply-constraint",
                                () -> {
                                    if (activeConstraint != null) apply(activeConstraint);
                                }),
                        new Separator(),
                        new Label("DRIVING VALUE (mm / degrees / ratio)"),
                        dimension,
                        dimensionValue,
                        apply,
                        new Separator(),
                        new Label("CONSTRAINTS"),
                        constraints,
                        button(
                                "Remove / Unfix",
                                "sketch-remove-constraint",
                                this::removeConstraint),
                        showBadges,
                        button("Edit selected text…", "sketch-edit-text", this::editText),
                        new Separator(),
                        button(
                                "Toggle construction",
                                "sketch-toggle-construction",
                                this::toggleConstruction),
                        button("Use selected as profile", "sketch-use-profile", this::useProfile),
                        button(
                                "Use all nonconstruction curves",
                                "sketch-all-profile",
                                () ->
                                        edit(
                                                controller
                                                        .definition()
                                                        .withBoundary(
                                                                controller
                                                                        .definition()
                                                                        .entities()
                                                                        .stream()
                                                                        .filter(
                                                                                e ->
                                                                                        !e
                                                                                                        .construction()
                                                                                                && e
                                                                                                                .kind()
                                                                                                        != Kind
                                                                                                                .POINT)
                                                                        .map(Entity::id)
                                                                        .toList()))),
                        button(
                                "Delete selected geometry",
                                "sketch-delete-geometry",
                                this::deleteGeometry));
        var scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setPrefWidth(270);
        scroll.setMinWidth(240);
        BorderPane.setMargin(scroll, new Insets(0, 0, 0, 10));
        return scroll;
    }

    private void installGestures() {
        canvas.setOnMousePressed(
                e -> {
                    canvas.requestFocus();
                    lastX = pressX = e.getX();
                    lastY = pressY = e.getY();
                    moving = false;
                    panning = e.getButton() == MouseButton.MIDDLE;
                    if (e.getButton() == MouseButton.PRIMARY
                            && tool == Draw.SELECT
                            && activeConstraint == null) {
                        var hit = hit(e.getX(), e.getY());
                        dragPoint =
                                hit != null
                                                && hit.ref().part() != Part.OBJECT
                                                && !hit.ref().equals(ORIGIN)
                                        ? hit.ref()
                                        : null;
                    }
                    e.consume();
                });
        canvas.setOnMouseDragged(
                e -> {
                    if (panning) {
                        centerX -= (e.getX() - lastX) / scale;
                        centerY += (e.getY() - lastY) / scale;
                        draw();
                    } else if (dragPoint != null
                            && e.isPrimaryButtonDown()
                            && Math.hypot(e.getX() - pressX, e.getY() - pressY) > 3) {
                        moving = true;
                        controller.drag(dragPoint, model(e.getX(), e.getY()), false);
                    }
                    lastX = e.getX();
                    lastY = e.getY();
                    e.consume();
                });
        canvas.setOnMouseReleased(
                e -> {
                    if (moving && dragPoint != null)
                        controller.drag(dragPoint, model(e.getX(), e.getY()), true);
                    dragPoint = null;
                    panning = false;
                    e.consume();
                });
        canvas.setOnMouseClicked(
                e -> {
                    if (e.getButton() != MouseButton.PRIMARY || !e.isStillSincePress() || moving)
                        return;
                    error.setText("");
                    if (tool != Draw.SELECT) {
                        drawingClick(e);
                        return;
                    }
                    var hit = hit(e.getX(), e.getY());
                    if (hit == null) {
                        for (var badge : badges)
                            if (Math.abs(e.getX() - badge.x()) < 11
                                    && Math.abs(e.getY() - badge.y()) < 9) {
                                constraints.getSelectionModel().select(badge.constraint());
                                return;
                            }
                    }
                    if (!e.isShiftDown() && activeConstraint == null) selection.clear();
                    constraints.getSelectionModel().clearSelection();
                    if (hit != null) {
                        if (e.isShiftDown() && selection.contains(hit.ref()))
                            selection.remove(hit.ref());
                        else selection.add(hit.ref());
                    }
                    selectionChanged();
                    e.consume();
                });
        canvas.setOnMouseMoved(
                e -> {
                    cursor = model(e.getX(), e.getY());
                    if (!drawing.isEmpty()) draw();
                });
        canvas.setOnScroll(
                e -> {
                    var anchor = model(e.getX(), e.getY());
                    scale = Math.clamp(scale * Math.exp(e.getDeltaY() * 0.002), 0.15, 100);
                    var moved = model(e.getX(), e.getY());
                    centerX += anchor.x() - moved.x();
                    centerY += anchor.y() - moved.y();
                    draw();
                    e.consume();
                });
    }

    private void tool(Draw next) {
        tool = next;
        activeConstraint = null;
        drawing.clear();
        snaps.clear();
        selection.clear();
        hint.setText(
                switch (next) {
                    case SELECT ->
                            "Select curves or points. Shift-click adds a selection. Drag points to reshape; middle-drag pans; wheel zooms.";
                    case POINT -> "Click to place a sketch point.";
                    case LINE ->
                            "Click two endpoints. Nearby endpoints and horizontal/vertical directions snap when Auto constraints is enabled.";
                    case RECTANGLE ->
                            "Click two opposite corners. Horizontal, vertical, and coincident constraints are added.";
                    case CIRCLE -> "Click a center, then a radius point.";
                    case ARC -> "Click center, start, then end. The arc runs counterclockwise.";
                    case SPLINE ->
                            "Click start, first control point, second control point, then end.";
                    case POLYGON ->
                            "Click center, then a vertex. The sides remain equal with a Regular polygon constraint.";
                    case ADVANCED -> advanced.instructions();
                });
        showToolOptions();
        if (next != Draw.ADVANCED) createType.setValue(null);
        draw();
    }

    private void showToolOptions() {
        toolOptions.getChildren().clear();
        boolean visible = tool == Draw.ADVANCED;
        toolOptions.setVisible(visible);
        toolOptions.setManaged(visible);
        if (!visible) return;
        toolOptions.getChildren().add(new Label(advanced.toString()));
        switch (advanced) {
            case CIRCLE_TWO_TANGENT -> option("Radius (mm)", toolRadius);
            case CONIC -> option("Rho (0–1)", toolRho);
            case CONTROL_SPLINE -> {
                option("Degree", splineDegree);
                toolOptions.getChildren().add(closedCurve);
            }
            case FIT_SPLINE -> toolOptions.getChildren().add(closedCurve);
            case TEXT, TEXT_ON_PATH -> {
                option("Text (up to 32 characters)", textContent);
                option("Font family", textFont);
                option("Style", textStyle);
                option("Height (mm)", textHeight);
                option("Angle (degrees)", textAngle);
                option("Extra spacing (em)", textSpacing);
            }
            default -> {}
        }
        option("Next point X (mm)", pointX);
        option("Next point Y (mm)", pointY);
        toolOptions
                .getChildren()
                .add(
                        button(
                                "Add numeric point",
                                "sketch-add-point",
                                () -> {
                                    try {
                                        advancedPoint(
                                                new Vec(value(pointX, "X"), value(pointY, "Y")));
                                    } catch (RuntimeException failure) {
                                        error.setText(failure.getMessage());
                                    }
                                }));
        toolOptions
                .getChildren()
                .add(button("Complete / retry", "sketch-complete-curve", this::finishAdvanced));
        toolOptions.getChildren().add(new Separator());
    }

    private void option(String label, Node input) {
        toolOptions.getChildren().addAll(new Label(label), input);
    }

    private SketchTools.Options options() {
        return new SketchTools.Options(
                Integer.parseInt(sides.getText().strip()),
                splineDegree.getValue(),
                closedCurve.isSelected(),
                construction.isSelected(),
                value(toolRadius, "Radius"),
                value(toolRho, "Rho"),
                textContent.getText(),
                textFont.getText().strip(),
                textStyle.getSelectionModel().getSelectedIndex(),
                value(textHeight, "Text height"),
                Math.toRadians(value(textAngle, "Text angle")),
                value(textSpacing, "Text spacing"));
    }

    private void advancedPoint(Vec point) {
        error.setText("");
        if (advanced.clicks() > 0 && drawing.size() >= advanced.clicks()) drawing.removeLast();
        if (advanced.clicks() == 0
                && drawing.size() >= 3
                && drawing.getFirst().distance(point) * scale < 7) {
            closedCurve.setSelected(true);
            finishAdvanced();
            return;
        }
        drawing.add(point);
        if (advanced.clicks() > 0 && drawing.size() == advanced.clicks()) finishAdvanced();
        else draw();
    }

    private void finishAdvanced() {
        if (tool != Draw.ADVANCED) return;
        try {
            var next =
                    SketchTools.add(
                            controller.definition(),
                            advanced,
                            id(advanced.name().toLowerCase(Locale.ROOT)),
                            List.copyOf(drawing),
                            List.copyOf(selection),
                            options());
            edit(next);
            drawing.clear();
            snaps.clear();
            tool(Draw.SELECT);
        } catch (RuntimeException failure) {
            error.setText(failure.getMessage());
            // Numeric options can be repaired and the same points retried with Enter.
            draw();
        }
    }

    private void editText() {
        try {
            var chosen =
                    selection.stream()
                            .map(r -> controller.definition().entity(r.entityId()))
                            .filter(e -> e.kind() == Kind.TEXT)
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "Select a text entity first"));
            var dialog = new Dialog<Entity>();
            dialog.initOwner(getScene().getWindow());
            dialog.setTitle("Edit sketch text");
            var content = new TextField(chosen.text().content());
            content.setId("sketch-edit-text-content");
            var font = new TextField(chosen.text().font());
            font.setId("sketch-edit-text-font");
            var path = new ComboBox<String>();
            path.setId("sketch-edit-text-path");
            path.getItems().add("");
            for (var entity : controller.definition().entities())
                if (entity.kind() != Kind.TEXT && entity.kind() != Kind.POINT)
                    path.getItems().add(entity.id());
            if (!path.getItems().contains(chosen.text().pathId()))
                path.getItems().add(chosen.text().pathId());
            path.setValue(chosen.text().pathId());
            path.setPromptText("No path (baseline position)");
            var message = new Label();
            message.setWrapText(true);
            dialog.getDialogPane()
                    .setContent(
                            new VBox(
                                    7,
                                    new Label("Text"),
                                    content,
                                    new Label("Font family"),
                                    font,
                                    new Label("Path (blank to detach)"),
                                    path,
                                    message));
            dialog.getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
            var result = new Entity[1];
            dialog.getDialogPane()
                    .lookupButton(ButtonType.OK)
                    .addEventFilter(
                            javafx.event.ActionEvent.ACTION,
                            event -> {
                                try {
                                    result[0] =
                                            SketchText.create(
                                                    chosen.id(),
                                                    content.getText(),
                                                    font.getText().strip(),
                                                    chosen.text().style(),
                                                    chosen.value(0),
                                                    chosen.value(1),
                                                    chosen.value(2),
                                                    chosen.value(3),
                                                    chosen.value(4),
                                                    path.getValue(),
                                                    chosen.construction());
                                } catch (RuntimeException failure) {
                                    message.setText(failure.getMessage());
                                    event.consume();
                                }
                            });
            dialog.setResultConverter(button -> button == ButtonType.OK ? result[0] : null);
            Dialogs.show(dialog, getScene().getWindow())
                    .ifPresent(e -> edit(controller.definition().replace(e)));
        } catch (RuntimeException failure) {
            error.setText(failure.getMessage());
        }
    }

    private void drawingClick(MouseEvent event) {
        try {
            var hit = auto.isSelected() ? hitPoint(event.getX(), event.getY(), 8) : null;
            var p = hit == null ? model(event.getX(), event.getY()) : hit.position();
            if (tool == Draw.ADVANCED) {
                advancedPoint(p);
                return;
            }
            if (tool == Draw.LINE
                    && drawing.size() == 1
                    && (auto.isSelected() || event.isShiftDown())) {
                var a = drawing.getFirst();
                var d = p.minus(a);
                if (event.isShiftDown() || Math.min(Math.abs(d.x()), Math.abs(d.y())) < 5 / scale)
                    p =
                            Math.abs(d.x()) >= Math.abs(d.y())
                                    ? new Vec(p.x(), a.y())
                                    : new Vec(a.x(), p.y());
            }
            drawing.add(p);
            snaps.add(hit == null ? null : hit.ref());
            int count =
                    switch (tool) {
                        case POINT -> 1;
                        case ARC -> 3;
                        case SPLINE -> 4;
                        default -> 2;
                    };
            if (drawing.size() < count) {
                draw();
                return;
            }
            String id = id(tool.name().toLowerCase(Locale.ROOT));
            SketchGeometry addition;
            var a = drawing.getFirst();
            var b = drawing.getLast();
            if (tool == Draw.RECTANGLE)
                addition =
                        Sketches.rectangle(
                                id,
                                Math.min(a.x(), b.x()),
                                Math.min(a.y(), b.y()),
                                Math.abs(a.x() - b.x()),
                                Math.abs(a.y() - b.y()),
                                false);
            else if (tool == Draw.POLYGON)
                addition =
                        Sketches.polygon(
                                id,
                                a.x(),
                                a.y(),
                                a.distance(b),
                                Integer.parseInt(sides.getText().strip()));
            else {
                Entity entity =
                        switch (tool) {
                            case POINT -> Sketches.entity(id, Kind.POINT, true, a.x(), a.y());
                            case LINE ->
                                    Sketches.entity(
                                            id,
                                            Kind.LINE,
                                            construction.isSelected(),
                                            a.x(),
                                            a.y(),
                                            b.x(),
                                            b.y());
                            case CIRCLE ->
                                    Sketches.entity(
                                            id,
                                            Kind.CIRCLE,
                                            construction.isSelected(),
                                            a.x(),
                                            a.y(),
                                            a.distance(b));
                            case ARC -> {
                                var start = drawing.get(1).minus(a);
                                var end = b.minus(a);
                                double angle = Math.atan2(start.y(), start.x()),
                                        sweep = Math.atan2(end.y(), end.x()) - angle;
                                if (sweep <= 0) sweep += Math.TAU;
                                yield Sketches.entity(
                                        id,
                                        Kind.ARC,
                                        construction.isSelected(),
                                        a.x(),
                                        a.y(),
                                        start.length(),
                                        angle,
                                        sweep);
                            }
                            case SPLINE ->
                                    Sketches.entity(
                                            id,
                                            Kind.SPLINE,
                                            construction.isSelected(),
                                            a.x(),
                                            a.y(),
                                            drawing.get(1).x(),
                                            drawing.get(1).y(),
                                            drawing.get(2).x(),
                                            drawing.get(2).y(),
                                            b.x(),
                                            b.y());
                            default -> throw new IllegalArgumentException("Choose a drawing tool");
                        };
                addition =
                        new SketchGeometry(
                                List.of(entity),
                                List.of(),
                                entity.construction() ? List.of() : List.of(id));
                if (tool == Draw.LINE && auto.isSelected()) {
                    if (Math.abs(a.y() - b.y()) < 1e-9)
                        addition =
                                addition.add(
                                        SketchConstraints.create(
                                                addition,
                                                id + "-horizontal",
                                                Type.HORIZONTAL,
                                                List.of(entity.ref()),
                                                0));
                    else if (Math.abs(a.x() - b.x()) < 1e-9)
                        addition =
                                addition.add(
                                        SketchConstraints.create(
                                                addition,
                                                id + "-vertical",
                                                Type.VERTICAL,
                                                List.of(entity.ref()),
                                                0));
                }
            }
            if (construction.isSelected())
                addition =
                        addition.withEntities(
                                        addition.entities().stream()
                                                .map(e -> e.withConstruction(true))
                                                .toList())
                                .withBoundary(List.of());
            var g = controller.definition();
            var entities = new ArrayList<>(g.entities());
            entities.addAll(addition.entities());
            var constraints = new ArrayList<>(g.constraints());
            constraints.addAll(addition.constraints());
            var boundary = new ArrayList<>(g.boundary());
            boundary.addAll(addition.boundary());
            var next = new SketchGeometry(entities, constraints, boundary);
            if (auto.isSelected() && addition.entities().size() == 1) {
                var entity = addition.entities().getFirst();
                for (int i = 0; i < snaps.size(); i++) {
                    if (snaps.get(i) == null) continue;
                    Part part =
                            switch (tool) {
                                case POINT -> Part.POINT;
                                case LINE -> i == 0 ? Part.START : Part.END;
                                case CIRCLE -> i == 0 ? Part.CENTER : null;
                                case ARC -> i == 0 ? Part.CENTER : i == 1 ? Part.START : Part.END;
                                case SPLINE ->
                                        new Part[] {
                                                    Part.START,
                                                    Part.CONTROL1,
                                                    Part.CONTROL2,
                                                    Part.END
                                                }
                                                [i];
                                default -> null;
                            };
                    if (part != null)
                        next =
                                next.add(
                                        SketchConstraints.create(
                                                next,
                                                id + "-snap-" + i,
                                                Type.COINCIDENT,
                                                List.of(entity.ref(part), snaps.get(i)),
                                                0));
                }
            }
            SketchConstraints.validateGeometry(next);
            edit(next);
            drawing.clear();
            snaps.clear();
            tool(Draw.SELECT);
        } catch (RuntimeException failure) {
            error.setText(failure.getMessage());
            drawing.clear();
            snaps.clear();
            draw();
        }
    }

    private void request(Type type) {
        tool = Draw.SELECT;
        drawing.clear();
        snaps.clear();
        activeConstraint = type;
        hint.setText(type + " · " + SketchConstraints.selectionHint(type));
        if (enough(type)) apply(type);
    }

    private boolean enough(Type type) {
        int n = selection.size();
        return switch (type) {
            case FIX -> n == 1;
            case HORIZONTAL, VERTICAL ->
                    n == 2 || (n == 1 && selection.getFirst().part() == Part.OBJECT);
            case SYMMETRY -> n == 3;
            case POLYGON -> n >= 3;
            default -> n == 2;
        };
    }

    private void selectionChanged() {
        selected.setText(
                selection.isEmpty()
                        ? "Nothing selected"
                        : selection.stream()
                                .map(Ref::toString)
                                .reduce((a, b) -> a + "\n" + b)
                                .orElse(""));
        if (activeConstraint != null
                && activeConstraint != Type.POLYGON
                && enough(activeConstraint)) apply(activeConstraint);
        draw();
    }

    private void apply(Type type) {
        try {
            var g = controller.definition();
            var refs = List.copyOf(selection);
            if (type == Type.FIX) {
                var existing =
                        g.constraints().stream()
                                .filter(c -> c.type() == Type.FIX && c.refs().equals(refs))
                                .findFirst();
                if (existing.isPresent()) {
                    edit(
                            g.withConstraints(
                                    g.constraints().stream()
                                            .filter(c -> c != existing.get())
                                            .toList()));
                    activeConstraint = null;
                    return;
                }
            }
            var c = SketchConstraints.create(g, id("constraint"), type, refs, 0);
            activeConstraint = null;
            edit(g.add(c));
        } catch (RuntimeException failure) {
            error.setText(failure.getMessage());
        }
    }

    private void dimension() {
        try {
            double value = value(dimensionValue, "Dimension");
            var g = controller.definition();
            var chosen = constraints.getSelectionModel().getSelectedItem();
            if (chosen != null && chosen.type().dimension()) {
                edit(
                        g.withConstraints(
                                g.constraints().stream()
                                        .map(
                                                c ->
                                                        c.id().equals(chosen.id())
                                                                ? c.withValue(value)
                                                                : c)
                                        .toList()));
            } else
                edit(
                        g.add(
                                SketchConstraints.create(
                                        g,
                                        id("dimension"),
                                        dimension.getValue(),
                                        List.copyOf(selection),
                                        value)));
        } catch (RuntimeException failure) {
            error.setText(failure.getMessage());
            dimensionValue.getStyleClass().add("invalid");
        }
    }

    private void removeConstraint() {
        var chosen = constraints.getSelectionModel().getSelectedItem();
        if (chosen == null) {
            error.setText("Select a constraint in the list or click its badge first.");
            return;
        }
        edit(
                controller
                        .definition()
                        .withConstraints(
                                controller.definition().constraints().stream()
                                        .filter(c -> !c.id().equals(chosen.id()))
                                        .toList()));
    }

    private void toggleConstruction() {
        var ids =
                selection.stream().map(Ref::entityId).collect(java.util.stream.Collectors.toSet());
        var g = controller.definition();
        var entities =
                g.entities().stream()
                        .map(e -> ids.contains(e.id()) ? e.withConstruction(!e.construction()) : e)
                        .toList();
        var boundary =
                g.boundary().stream()
                        .filter(
                                id ->
                                        entities.stream()
                                                .anyMatch(
                                                        e ->
                                                                e.id().equals(id)
                                                                        && !e.construction()))
                        .toList();
        edit(new SketchGeometry(entities, g.constraints(), boundary));
    }

    private void useProfile() {
        try {
            var ids =
                    selection.stream()
                            .filter(r -> r.part() == Part.OBJECT)
                            .map(Ref::entityId)
                            .distinct()
                            .toList();
            if (ids.isEmpty())
                throw new IllegalArgumentException(
                        "Select the boundary curves, including any holes.");
            var next = controller.definition().withBoundary(ids);
            SketchProfiles.profile(next);
            edit(next);
        } catch (RuntimeException failure) {
            error.setText(failure.getMessage());
        }
    }

    private void deleteGeometry() {
        var g = controller.definition();
        for (String id : selection.stream().map(Ref::entityId).distinct().toList())
            if (!id.equals("origin")) g = Sketches.removeEntity(g, id);
        selection.clear();
        edit(g);
    }

    private void controllerUndo(boolean forward) {
        tool(Draw.SELECT);
        selection.clear();
        if (forward) controller.redo();
        else controller.undo();
    }

    private void edit(SketchGeometry next) {
        error.setText("");
        dimensionValue.getStyleClass().remove("invalid");
        controller.edit(next);
    }

    private void refresh() {
        if (closed) return;
        refreshing = true;
        try {
            var prior = constraints.getSelectionModel().getSelectedItem();
            constraints.getItems().setAll(controller.definition().constraints());
            if (prior != null)
                constraints.getItems().stream()
                        .filter(c -> c.id().equals(prior.id()))
                        .findFirst()
                        .ifPresent(c -> constraints.getSelectionModel().select(c));
            var refs = new ArrayList<Ref>();
            refs.add(ORIGIN);
            for (var e : controller.definition().entities()) {
                refs.add(e.ref());
                refs.addAll(e.pointRefs());
            }
            var selectedRef = references.getValue();
            references.getItems().setAll(refs);
            references.setValue(refs.contains(selectedRef) ? selectedRef : ORIGIN);
            selection.removeIf(r -> !refs.contains(r));
            undo.setDisable(!controller.canUndo());
            redo.setDisable(!controller.canRedo());
            finish.setDisable(
                    !controller.canFinish() || controller.definition().entities().isEmpty());
            status.setText(
                    controller.busy()
                            ? "Solving constraints…"
                            : controller.result() == null ? "Ready" : controller.result().status());
            if (!controller.busy() && !controller.interactionError().isEmpty())
                error.setText(controller.interactionError());
            else if (!controller.busy()
                    && controller.result() != null
                    && !controller.result().solved())
                error.setText(
                        controller.result().problems().stream()
                                .map(SketchSolver.Problem::message)
                                .reduce((a, b) -> a + "\n" + b)
                                .orElse("Constraint conflict"));
            selected.setText(
                    selection.isEmpty()
                            ? "Nothing selected"
                            : selection.stream()
                                    .map(Ref::toString)
                                    .reduce((a, b) -> a + "\n" + b)
                                    .orElse(""));
            draw();
        } finally {
            refreshing = false;
        }
    }

    private void finish() {
        if (!controller.canFinish()) return;
        try {
            double offset = value(plane, "Plane Z");
            var result =
                    new Feature.Sketch(
                            original == null ? "sketch-" + UUID.randomUUID() : original.id(),
                            original == null
                                    ? "profile-" + UUID.randomUUID()
                                    : original.profileId(),
                            original == null
                                    ? new Feature.Plane(
                                            offset == 0
                                                    ? "origin-xy"
                                                    : "plane-" + UUID.randomUUID(),
                                            offset)
                                    : original.plane(),
                            new Feature.Constrained(controller.definition()));
            accept.accept(result);
            dismiss.run();
        } catch (RuntimeException failure) {
            error.setText(failure.getMessage());
        }
    }

    private String id(String prefix) {
        String id;
        do {
            id = prefix + "-" + nextId++;
        } while (hasId(id));
        return id;
    }

    private boolean hasId(String id) {
        return controller.definition().entities().stream().anyMatch(e -> e.id().equals(id))
                || controller.definition().constraints().stream().anyMatch(c -> c.id().equals(id));
    }

    private void fit() {
        var points =
                controller.displayed().entities().stream()
                        .flatMap(e -> displayCurves(controller.displayed(), e).stream())
                        .flatMap(e -> e.sample().stream())
                        .toList();
        if (points.isEmpty()) {
            centerX = 30;
            centerY = 20;
            scale = Math.max(0.15, Math.min(canvas.getWidth() / 100, canvas.getHeight() / 70));
            draw();
            return;
        }
        double minX = points.stream().mapToDouble(Vec::x).min().orElse(0),
                maxX = points.stream().mapToDouble(Vec::x).max().orElse(60);
        double minY = points.stream().mapToDouble(Vec::y).min().orElse(0),
                maxY = points.stream().mapToDouble(Vec::y).max().orElse(40);
        centerX = minX / 2 + maxX / 2;
        centerY = minY / 2 + maxY / 2;
        scale =
                Math.clamp(
                        Math.min(
                                canvas.getWidth() / (maxX - minX + 20),
                                canvas.getHeight() / (maxY - minY + 20)),
                        0.15,
                        40);
        draw();
    }

    Point2D modelToScreen(Vec point) {
        return canvas.localToScreen(px(point.x()), py(point.y()));
    }

    SketchController controller() {
        return controller;
    }

    private Vec model(double x, double y) {
        return new Vec(
                centerX + (x - canvas.getWidth() / 2) / scale,
                centerY - (y - canvas.getHeight() / 2) / scale);
    }

    private double px(double x) {
        return canvas.getWidth() / 2 + (x - centerX) * scale;
    }

    private double py(double y) {
        return canvas.getHeight() / 2 - (y - centerY) * scale;
    }

    private Hit hitPoint(double x, double y, double radius) {
        Hit result = null;
        double distance = radius;
        for (var e : controller.displayed().entities())
            for (var ref : e.pointRefs()) {
                var p = e.point(ref);
                double d = Math.hypot(px(p.x()) - x, py(p.y()) - y);
                if (d < distance) {
                    distance = d;
                    result = new Hit(ref, p);
                }
            }
        if (Math.hypot(px(0) - x, py(0) - y) < distance) return new Hit(ORIGIN, new Vec(0, 0));
        return result;
    }

    private Hit hit(double x, double y) {
        var point = hitPoint(x, y, 7);
        if (point != null) return point;
        Hit result = null;
        double best = 6 / scale;
        var p = model(x, y);
        for (var e : controller.displayed().entities()) {
            for (var curve : displayCurves(controller.displayed(), e)) {
                var samples = curve.sample();
                for (int i = 1; i < samples.size(); i++) {
                    var a = samples.get(i - 1);
                    var d = samples.get(i).minus(a);
                    double t = Math.clamp(p.minus(a).dot(d) / Math.max(1e-20, d.dot(d)), 0, 1);
                    var near = a.plus(d.times(t));
                    double distance = p.distance(near);
                    if (distance < best) {
                        best = distance;
                        result = new Hit(e.ref(), near);
                    }
                }
            }
        }
        return result;
    }

    private static List<Entity> displayCurves(SketchGeometry geometry, Entity entity) {
        try {
            return geometry.curves(entity);
        } catch (IllegalArgumentException invalid) {
            return List.of(entity);
        }
    }

    private void draw() {
        var graphics = canvas.getGraphicsContext2D();
        double w = canvas.getWidth(), h = canvas.getHeight();
        if (w <= 0 || h <= 0 || !Double.isFinite(scale) || scale <= 0) return;
        graphics.setFill(Color.web(dark ? "#161c23" : "#f8fafc"));
        graphics.fillRect(0, 0, w, h);
        graphics.setLineWidth(1);
        graphics.setLineDashes();
        graphics.setStroke(Color.web(dark ? "#2a3541" : "#e1e7ed"));
        double spacing = Math.pow(10, Math.floor(Math.log10(60 / Math.max(scale, 0.15))));
        var lo = model(0, h);
        var hi = model(w, 0);
        double firstX = Math.ceil(lo.x() / spacing) * spacing;
        double firstY = Math.ceil(lo.y() / spacing) * spacing;
        // Bound work by screen pixels, including the first layout pulse and extreme coordinates.
        for (int i = 0; i <= Math.min(500, Math.ceil(w / (scale * spacing))); i++) {
            double x = firstX + i * spacing;
            graphics.strokeLine(px(x), 0, px(x), h);
        }
        for (int i = 0; i <= Math.min(500, Math.ceil(h / (scale * spacing))); i++) {
            double y = firstY + i * spacing;
            graphics.strokeLine(0, py(y), w, py(y));
        }
        graphics.setStroke(Color.web("#ce6663"));
        graphics.strokeLine(0, py(0), w, py(0));
        graphics.setStroke(Color.web("#52a287"));
        graphics.strokeLine(px(0), 0, px(0), h);
        graphics.setFill(Color.web("#a6b3bf"));
        graphics.fillOval(px(0) - 3, py(0) - 3, 6, 6);
        if (controller == null) return;
        var g = controller.displayed();
        boolean invalid = controller.result() != null && !controller.result().solved();
        for (var entity : g.entities()) {
            boolean chosen = selection.stream().anyMatch(r -> r.entityId().equals(entity.id()));
            Color color =
                    Color.web(
                            chosen
                                    ? "#e6a239"
                                    : invalid ? "#db6860" : dark ? "#7fbcdd" : "#286d98");
            graphics.setStroke(color);
            graphics.setLineWidth(chosen ? 2.5 : 1.5);
            graphics.setLineDashes(entity.construction() ? new double[] {7, 5} : new double[] {});
            for (var curve : displayCurves(g, entity)) {
                var points = curve.sample();
                graphics.beginPath();
                graphics.moveTo(px(points.getFirst().x()), py(points.getFirst().y()));
                for (var p : points) graphics.lineTo(px(p.x()), py(p.y()));
                graphics.stroke();
            }
            graphics.setLineDashes();
            if (entity.kind() == Kind.SPLINE) {
                graphics.setLineWidth(1);
                graphics.setStroke(Color.web(dark ? "#647587" : "#abb7c2"));
                graphics.setLineDashes(3, 4);
                var p0 = entity.at(0);
                var p1 = entity.point(Part.CONTROL1);
                var p2 = entity.point(Part.CONTROL2);
                var p3 = entity.at(1);
                graphics.strokeLine(px(p0.x()), py(p0.y()), px(p1.x()), py(p1.y()));
                graphics.strokeLine(px(p2.x()), py(p2.y()), px(p3.x()), py(p3.y()));
                graphics.setLineDashes();
            }
            if (entity.kind() == Kind.CONTROL_SPLINE || entity.kind() == Kind.CONIC) {
                graphics.setStroke(Color.web(dark ? "#647587" : "#abb7c2"));
                graphics.setLineDashes(3, 4);
                var controls =
                        entity.kind() == Kind.CONTROL_SPLINE
                                ? entity.pointRefs().stream()
                                        .filter(r -> r.part() == Part.CONTROL)
                                        .map(entity::point)
                                        .toList()
                                : List.of(entity.at(0), entity.point(Part.CONTROL1), entity.at(1));
                for (int i = 1; i < controls.size(); i++)
                    graphics.strokeLine(
                            px(controls.get(i - 1).x()),
                            py(controls.get(i - 1).y()),
                            px(controls.get(i).x()),
                            py(controls.get(i).y()));
                graphics.setLineDashes();
            }
            for (var ref : entity.pointRefs()) {
                var p = entity.point(ref);
                graphics.setFill(selection.contains(ref) ? Color.web("#e6a239") : color);
                graphics.fillOval(px(p.x()) - 3.5, py(p.y()) - 3.5, 7, 7);
            }
        }
        badges.clear();
        if (showBadges.isSelected()) {
            graphics.setFont(Font.font(11));
            int index = 0;
            for (var c : g.constraints()) {
                if (c.refs().isEmpty()) continue;
                try {
                    var ref = c.refs().getFirst();
                    var p =
                            ref.part() == Part.OBJECT
                                    ? g.entity(ref.entityId()).at(0.5)
                                    : g.point(ref);
                    double x = px(p.x()) + 14 + (index % 3) * 18,
                            y = py(p.y()) + 14 + (index / 3 % 2) * 18;
                    index++;
                    badges.add(new Badge(c, x, y));
                    graphics.setFill(Color.web(dark ? "#28323e" : "#ffffff"));
                    graphics.fillRoundRect(x - 9, y - 8, 18, 16, 4, 4);
                    graphics.setFill(
                            Color.web(
                                    c.equals(constraints.getSelectionModel().getSelectedItem())
                                            ? "#e6a239"
                                            : "#d15d55"));
                    graphics.fillText(symbol(c.type()), x - 6, y + 4, 14);
                } catch (IllegalArgumentException broken) {
                    /* Broken references remain removable in the constraint list. */
                }
            }
        }
        if (!drawing.isEmpty()) {
            graphics.setStroke(Color.web("#e6a239"));
            graphics.setLineWidth(1.5);
            graphics.setLineDashes(5, 4);
            var a = drawing.getFirst();
            if (tool == Draw.RECTANGLE)
                graphics.strokeRect(
                        Math.min(px(a.x()), px(cursor.x())),
                        Math.min(py(a.y()), py(cursor.y())),
                        Math.abs(a.x() - cursor.x()) * scale,
                        Math.abs(a.y() - cursor.y()) * scale);
            else if (tool == Draw.CIRCLE || tool == Draw.POLYGON) {
                double r = a.distance(cursor) * scale;
                graphics.strokeOval(px(a.x()) - r, py(a.y()) - r, 2 * r, 2 * r);
            } else {
                graphics.beginPath();
                graphics.moveTo(px(a.x()), py(a.y()));
                for (var p : drawing) graphics.lineTo(px(p.x()), py(p.y()));
                graphics.lineTo(px(cursor.x()), py(cursor.y()));
                graphics.stroke();
            }
            graphics.setLineDashes();
        }
    }

    private static String symbol(Type type) {
        return switch (type) {
            case HORIZONTAL -> "—";
            case VERTICAL -> "│";
            case COINCIDENT -> "●";
            case TANGENT -> "○/";
            case EQUAL -> "=";
            case PARALLEL -> "∥";
            case PERPENDICULAR -> "⊥";
            case FIX -> "▣";
            case MIDPOINT -> "△";
            case CONCENTRIC -> "◎";
            case COLLINEAR -> "↗";
            case SYMMETRY -> "││";
            case CURVATURE -> "∿";
            case POLYGON -> "⬡";
            default -> "d";
        };
    }

    private static String title(String name) {
        return name.substring(0, 1) + name.substring(1).toLowerCase(Locale.ROOT);
    }

    private static String number(double value) {
        return String.format(Locale.ROOT, "%.6g", value);
    }

    private static double value(TextField field, String label) {
        try {
            double v = Double.parseDouble(field.getText().strip());
            if (!Double.isFinite(v)) throw new NumberFormatException();
            field.getStyleClass().remove("invalid");
            return v;
        } catch (NumberFormatException failure) {
            field.getStyleClass().add("invalid");
            throw new IllegalArgumentException(label + " must be a finite number");
        }
    }

    private static Button button(String title, String id, Runnable action) {
        var button = new Button(title);
        button.setId(id);
        button.setOnAction(e -> action.run());
        return button;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            controller.close();
        }
    }
}
