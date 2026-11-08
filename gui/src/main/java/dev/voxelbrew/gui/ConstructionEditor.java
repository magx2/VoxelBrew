package dev.voxelbrew.gui;

import dev.voxelbrew.engine.construction.ConstructionGeometry.Vector;
import dev.voxelbrew.engine.construction.ConstructionTools.Tool;
import dev.voxelbrew.engine.model.ConstructionFeature;
import dev.voxelbrew.engine.model.ConstructionFeature.Input;
import dev.voxelbrew.engine.model.ConstructionFeature.Selection;
import dev.voxelbrew.util.i18n.Translations;
import java.util.ArrayList;
import java.util.List;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/** Window-local draft: preview never changes the document or its undo stack. */
final class ConstructionEditor extends VBox {
    private final List<ComboBox<Input>> selectors = new ArrayList<>();
    private final List<List<TextField>> coordinates = new ArrayList<>();
    private final DocumentController controller;
    private final Viewport viewport;
    private final Translations translations;
    private final ConstructionFeature original;
    private final String id;
    private final Tool tool;
    private final TextField value = new TextField("0");
    private final CheckBox alternate = new CheckBox();
    private final Label error = new Label();
    private int active;
    private long generation;
    private boolean closed;

    ConstructionEditor(
            Tool tool,
            ConstructionFeature original,
            DocumentController controller,
            Viewport viewport,
            Translations translations,
            Runnable finished) {
        super(8);
        this.tool = tool;
        this.original = original;
        this.controller = controller;
        this.viewport = viewport;
        this.translations = translations;
        id = original == null
                ? "construction-"
                        + controller.snapshot().document().uuidGenerator().next()
                : original.id();
        getChildren().add(new Label(translations.text("construction." + tool.name())));
        var hint = new Label(translations.text("construction.select"));
        hint.setWrapText(true);
        getChildren().add(hint);
        var choices = new ArrayList<Input>();
        var zero = new Vector(0, 0, 0);
        for (int i = 0; i < 3; i++) {
            choices.add(Input.originPlane(i));
            choices.add(new Input(Selection.ORIGIN_AXIS, "", "", i, zero));
        }
        choices.add(new Input(Selection.ORIGIN_POINT, "", "", 0, zero));
        choices.add(new Input(Selection.POSITION, "", "", 0, zero));
        var document = controller.snapshot().document();
        for (var feature : document.features()) {
            if (feature.id().equals(id)) break;
            if (controller.build().construction().containsKey(feature.id())) {
                int parts = controller.build().construction().get(feature.id())
                                instanceof dev.voxelbrew.engine.construction.ConstructionGeometry.CoordinateSystem
                        ? 7
                        : 1;
                for (int part = 0; part < parts; part++)
                    choices.add(new Input(Selection.DATUM, feature.id(), "", part, zero));
            }
        }
        choices.addAll(controller.build().constructionInputs().stream()
                .filter(i -> !i.source().equals(id))
                .toList());
        int count = tool.inputs().size();
        for (int i = 0; i < count; i++) {
            int slot = i;
            var selector = new ComboBox<Input>();
            selector.setId("construction-input-" + i);
            selector.setMaxWidth(Double.MAX_VALUE);
            selector.setMinWidth(0);
            selector.setPrefWidth(220);
            selector.getItems().setAll(choices);
            UiText.choices(selector, this::label);
            selector.setOnMousePressed(event -> active = slot);
            selector.focusedProperty().addListener((o, a, focused) -> {
                if (focused) active = slot;
            });
            var xyz = List.of(new TextField("0"), new TextField("0"), new TextField("0"));
            for (int axis = 0; axis < 3; axis++) {
                xyz.get(axis).setPromptText("XYZ".substring(axis, axis + 1));
                xyz.get(axis).setPrefColumnCount(4);
            }
            var row = new HBox(4);
            row.getChildren().addAll(xyz);
            row.visibleProperty().bind(selector.valueProperty().map(v -> v.selection() == Selection.POSITION));
            row.managedProperty().bind(row.visibleProperty());
            selectors.add(selector);
            coordinates.add(xyz);
            getChildren()
                    .addAll(
                            new Label((i + 1) + ". "
                                    + translations.text(
                                            "construction.role." + tool.inputs().get(i))),
                            selector,
                            row);
            if (original != null) {
                var input = original.inputs().get(i);
                if (!selector.getItems().contains(input)) selector.getItems().add(input);
                selector.setValue(input);
                xyz.get(0).setText(Double.toString(input.position().x()));
                xyz.get(1).setText(Double.toString(input.position().y()));
                xyz.get(2).setText(Double.toString(input.position().z()));
            }
        }
        boolean usesValue = tool == Tool.OFFSET_PLANE
                || tool == Tool.ANGLED_PLANE
                || tool == Tool.TANGENT_PLANE
                || tool == Tool.PLANE_ALONG_PATH
                || tool == Tool.POINT_ALONG_PATH;
        if (usesValue) {
            String key = tool == Tool.OFFSET_PLANE
                    ? "construction.distance"
                    : tool == Tool.ANGLED_PLANE ? "construction.angle" : "construction.fraction";
            getChildren().addAll(new Label(translations.text(key)), value);
        }
        value.setId("construction-value");
        if (original != null) value.setText(Double.toString(original.value()));
        alternate.setText(translations.text("construction.alternate"));
        if (original != null) alternate.setSelected(original.alternate());
        if (tool == Tool.MIDPLANE) getChildren().add(alternate);
        var preview = new Button(translations.text("construction.preview"));
        preview.setId("construction-preview");
        var apply = new Button(translations.text("dialog.apply"));
        apply.setId("construction-apply");
        var cancel = new Button(translations.text("action.cancel"));
        cancel.setId("construction-cancel");
        preview.setOnAction(event -> evaluate(false, finished));
        apply.setOnAction(event -> evaluate(true, finished));
        cancel.setOnAction(event -> {
            close();
            finished.run();
        });
        error.setWrapText(true);
        error.getStyleClass().add("error");
        error.setId("construction-error");
        getChildren().addAll(new HBox(6, preview, apply, cancel), error);
        Runnable invalidate = () -> {
            generation++;
            viewport.previewConstruction(null);
        };
        selectors.forEach(selector -> selector.valueProperty().addListener((o, before, after) -> invalidate.run()));
        coordinates.forEach(
                row -> row.forEach(field -> field.textProperty().addListener((o, before, after) -> invalidate.run())));
        value.textProperty().addListener((o, before, after) -> invalidate.run());
        alternate.selectedProperty().addListener((o, before, after) -> invalidate.run());
    }

    private String label(Input input) {
        if (input.selection() == Selection.ORIGIN_PLANE) return new String[] {"YZ", "XZ", "XY"}[input.index()];
        if (input.selection() == Selection.ORIGIN_AXIS) return "XYZ".substring(input.index(), input.index() + 1);
        if (input.selection() == Selection.DATUM)
            return controller.snapshot().document().featureName(input.source(), input.source())
                    + (controller.build().construction().get(input.source())
                                    instanceof dev.voxelbrew.engine.construction.ConstructionGeometry.CoordinateSystem
                            ? " · " + new String[] {"O", "X", "Y", "Z", "YZ", "XZ", "XY"}[input.index()]
                            : "");
        String name = translations.text("construction.selection." + input.selection());
        return input.source().isEmpty()
                ? name
                : name + " " + (input.index() + 1) + " · " + input.source() + "/" + input.output();
    }

    void pick(String bodyId, int face) {
        controller.build().bodies().stream()
                .filter(b -> b.id().equals(bodyId))
                .findFirst()
                .ifPresent(body -> {
                    var input = new Input(
                            Selection.FACE,
                            body.output().featureId(),
                            body.output().outputName(),
                            face,
                            new Vector(0, 0, 0));
                    selectors.get(active).setValue(input);
                });
    }

    void pickEdge(String bodyId, int edge) {
        controller.build().bodies().stream()
                .filter(b -> b.id().equals(bodyId))
                .findFirst()
                .ifPresent(body -> {
                    if (edge < 1) return;
                    selectors
                            .get(active)
                            .setValue(new Input(
                                    Selection.EDGE,
                                    body.output().featureId(),
                                    body.output().outputName(),
                                    edge - 1,
                                    new Vector(0, 0, 0)));
                });
    }

    void pickDatum(String id) {
        var input = selectors.get(active).getItems().stream()
                .filter(i -> i.selection() == Selection.DATUM && i.source().equals(id))
                .findFirst();
        input.ifPresent(i -> selectors.get(active).setValue(i));
    }

    private ConstructionFeature definition() {
        var inputs = new ArrayList<Input>();
        for (int i = 0; i < selectors.size(); i++) {
            var input = selectors.get(i).getValue();
            if (input == null) throw new IllegalArgumentException(translations.text("construction.select"));
            if (input.selection() == Selection.POSITION) {
                var xyz = coordinates.get(i);
                input = new Input(
                        Selection.POSITION,
                        "",
                        "",
                        0,
                        new Vector(number(xyz.get(0)), number(xyz.get(1)), number(xyz.get(2))));
            }
            inputs.add(input);
        }
        return new ConstructionFeature(id, tool, inputs, number(value), alternate.isSelected());
    }

    private static double number(TextField field) {
        return Double.parseDouble(field.getText().trim().replace(',', '.'));
    }

    private void evaluate(boolean commit, Runnable finished) {
        try {
            var feature = definition();
            var snapshot = controller.snapshot();
            long request = ++generation;
            error.setText("");
            controller
                    .previewConstruction(feature)
                    .whenComplete((datum, failure) -> Platform.runLater(() -> {
                        if (closed || request != generation) return;
                        if (!controller.snapshot().equals(snapshot)) {
                            error.setText(translations.text("construction.changed"));
                            return;
                        }
                        if (failure != null) {
                            error.setText(
                                    failure.getCause() == null
                                            ? failure.getMessage()
                                            : failure.getCause().getMessage());
                            return;
                        }
                        if (commit) {
                            close();
                            if (original == null) controller.add(feature);
                            else controller.replaceFeature(feature);
                            finished.run();
                        } else viewport.previewConstruction(datum);
                    }));
        } catch (RuntimeException failure) {
            error.setText(failure.getMessage());
        }
    }

    void close() {
        closed = true;
        generation++;
        viewport.previewConstruction(null);
    }
}
