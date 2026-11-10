package dev.voxelbrew.gui;

import dev.voxelbrew.engine.printing.PrinterCatalog;
import dev.voxelbrew.util.i18n.Translations;
import java.util.*;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

final class PrinterExportDialog {
    private PrinterExportDialog() {}

    static Set<String> choose(Window owner, Translations translations, List<PrinterCatalog.Entry> entries) {
        return choose(owner, translations, entries, Set.of(), false).orElse(Set.of());
    }

    static Optional<Set<String>> manageVisible(
            Window owner, Translations translations, List<PrinterCatalog.Entry> entries, Set<String> hidden) {
        var visible = new HashSet<String>();
        for (var entry : entries)
            if (entry.bundled() && !hidden.contains(entry.definition().printer().id()))
                visible.add(entry.definition().printer().id());
        return choose(owner, translations, entries, visible, true);
    }

    private static Optional<Set<String>> choose(
            Window owner,
            Translations translations,
            List<PrinterCatalog.Entry> entries,
            Set<String> initial,
            boolean visibility) {
        var dialog = new Dialog<Set<String>>();
        dialog.initOwner(owner);
        dialog.setTitle(translations.text(visibility ? "print.visiblePrinters" : "print.exportPrinters"));
        dialog.setHeaderText(
                translations.text(visibility ? "print.chooseVisiblePrinters" : "print.chooseExportPrinters"));
        var export = new ButtonType(
                translations.text(visibility ? "print.saveVisiblePrinters" : "print.exportSelectedPrinters"),
                ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(export, ButtonType.CANCEL);
        dialog.getDialogPane().setId(visibility ? "printer-visibility-dialog" : "printer-export-dialog");
        var exportButton = dialog.getDialogPane().lookupButton(export);
        exportButton.setId("printer-export-confirm");
        exportButton.setDisable(!visibility);
        Set<String> selected = new LinkedHashSet<>(initial);
        var list = new VBox(6);
        list.setId("printer-options");
        for (var entry : entries) {
            if (!entry.bundled()) continue;
            String id = entry.definition().printer().id();
            var choice = new CheckBox(entry.toString());
            choice.setId("printer-export-" + id);
            choice.setSelected(initial.contains(id));
            choice.selectedProperty().addListener((o, a, checked) -> {
                if (checked) selected.add(id);
                else selected.remove(id);
                exportButton.setDisable(!visibility && selected.isEmpty());
            });
            list.getChildren().add(choice);
        }
        var search = new TextField();
        search.setId("printer-export-search");
        search.setPromptText(translations.text("print.searchPrinters"));
        search.textProperty().addListener((o, a, query) -> {
            for (var node : list.getChildren()) {
                boolean match =
                        ((CheckBox) node).getText().toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT));
                node.setVisible(match);
                node.setManaged(match);
            }
        });
        var scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(320);
        var content = new VBox(10, search);
        if (visibility) {
            var selectAll = new Button(translations.text("print.selectAllPrinters"));
            selectAll.setId("printer-select-all");
            selectAll.setOnAction(event -> list.getChildren().forEach(node -> ((CheckBox) node).setSelected(true)));
            var deselectAll = new Button(translations.text("print.deselectAllPrinters"));
            deselectAll.setId("printer-deselect-all");
            deselectAll.setOnAction(event -> list.getChildren().forEach(node -> ((CheckBox) node).setSelected(false)));
            var invert = new Button(translations.text("print.invertPrinters"));
            invert.setId("printer-invert-selection");
            invert.setOnAction(event -> list.getChildren().forEach(node -> {
                var choice = (CheckBox) node;
                choice.setSelected(!choice.isSelected());
            }));
            content.getChildren().add(new HBox(8, selectAll, deselectAll, invert));
        }
        content.getChildren().add(scroll);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefWidth(480);
        dialog.setResultConverter(button -> button == export ? Set.copyOf(selected) : null);
        return dialog.showAndWait();
    }
}
