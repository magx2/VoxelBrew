package dev.voxelbrew.gui;

import dev.voxelbrew.engine.feature.*;
import dev.voxelbrew.engine.model.Feature;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import javafx.event.ActionEvent;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/** The installed provider's descriptor defines the entire creation form. */
final class FeatureDialog {
    private FeatureDialog() {}

    static Optional<Feature.Custom> show(
            Window owner, Path directory, FeatureRegistry.Factory features) throws IOException {
        try (var registry = features.open(directory)) {
            var dialog = new Dialog<Feature.Custom>();
            dialog.setTitle("Add feature");
            var type = new ComboBox<FeatureDescriptor>();
            type.setId("custom-type");
            type.getItems().setAll(registry.descriptors());
            type.setMaxWidth(Double.MAX_VALUE);
            var fields = new LinkedHashMap<String, TextField>();
            var form = new VBox(8);
            var errors = new Label();
            errors.setWrapText(true);
            errors.setId("custom-error");
            errors.getStyleClass().add("error");
            var diagnostics = new Label(String.join("\n", registry.diagnostics()));
            diagnostics.setWrapText(true);
            var details = new TitledPane("Plugin diagnostics", diagnostics);
            details.setExpanded(false);
            type.setOnAction(
                    e -> {
                        fields.clear();
                        form.getChildren().clear();
                        errors.setText("");
                        if (type.getValue() != null)
                            for (var p : type.getValue().parameters()) {
                                var input = new TextField(Double.toString(p.defaultValue()));
                                input.setId("custom-" + p.key());
                                fields.put(p.key(), input);
                                form.getChildren()
                                        .addAll(
                                                new Label(p.label() + " (" + p.unit() + ")"),
                                                input);
                            }
                    });
            type.getSelectionModel().selectFirst();
            var scroll = new ScrollPane(form);
            scroll.setFitToWidth(true);
            scroll.setPrefViewportHeight(230);
            var location = new Label("Trusted plugin directory: " + directory);
            location.setWrapText(true);
            var content =
                    new VBox(
                            10, new Label("Feature type"), type, scroll, errors, location, details);
            content.setPrefWidth(440);
            dialog.getDialogPane().setContent(content);
            var add = new ButtonType("Add", ButtonBar.ButtonData.OK_DONE);
            dialog.getDialogPane().getButtonTypes().setAll(add, ButtonType.CANCEL);
            var button = (Button) dialog.getDialogPane().lookupButton(add);
            button.setId("custom-add");
            button.setDisable(type.getItems().isEmpty());
            if (type.getItems().isEmpty())
                errors.setText(
                        "No feature providers installed. Add a trusted JAR to this directory and reopen Features.");
            dialog.getDialogPane().lookupButton(ButtonType.CANCEL).setId("custom-cancel");
            Feature.Custom[] result = new Feature.Custom[1];
            button.addEventFilter(
                    ActionEvent.ACTION,
                    e -> {
                        fields.values().forEach(f -> f.getStyleClass().remove("invalid"));
                        var values = new LinkedHashMap<String, Double>();
                        try {
                            for (var p : type.getValue().parameters()) {
                                var field = fields.get(p.key());
                                try {
                                    double value = Double.parseDouble(field.getText());
                                    p.validate(value);
                                    values.put(p.key(), value);
                                } catch (IllegalArgumentException failure) {
                                    field.getStyleClass().add("invalid");
                                    throw new IllegalArgumentException(
                                            p.label() + ": " + failure.getMessage(), failure);
                                }
                            }
                            var d = type.getValue();
                            result[0] =
                                    registry.create(
                                            "custom-" + UUID.randomUUID(),
                                            d.providerId(),
                                            d.type(),
                                            values);
                        } catch (RuntimeException failure) {
                            errors.setText(failure.getMessage());
                            fields.forEach(
                                    (key, field) -> {
                                        if (failure.getMessage().contains(key))
                                            field.getStyleClass().add("invalid");
                                    });
                            e.consume();
                        }
                    });
            dialog.setResultConverter(choice -> choice == add ? result[0] : null);
            return Dialogs.show(dialog, owner);
        }
    }
}
