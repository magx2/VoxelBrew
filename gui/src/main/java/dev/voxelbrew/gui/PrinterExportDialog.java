package dev.voxelbrew.gui;

import dev.voxelbrew.engine.printing.PrinterCatalog;
import dev.voxelbrew.util.i18n.Translations;
import java.util.*;
import javafx.scene.control.*;
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
        dialog.setHeaderText(translations
                .text(visibility ? "print.visiblePrinters" : "print.exportPrinters")
                .replace("...", ""));
        var export = new ButtonType(
                translations.text(visibility ? "print.saveVisiblePrinters" : "print.exportSelectedPrinters"),
                ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(export, ButtonType.CANCEL);
        dialog.getDialogPane().setId(visibility ? "printer-visibility-dialog" : "printer-export-dialog");
        var exportButton = dialog.getDialogPane().lookupButton(export);
        exportButton.setId("printer-export-confirm");
        exportButton.setDisable(!visibility);
        exportButton.getStyleClass().add("printer-dialog-primary");
        dialog.getDialogPane().lookupButton(ButtonType.CANCEL).setId("printer-dialog-cancel");
        Set<String> selected = new LinkedHashSet<>(initial);
        long total = entries.stream().filter(PrinterCatalog.Entry::bundled).count();
        var count = new Label(translations.text("print.printerSelectionCount", selected.size(), total));
        count.getStyleClass().add("printer-selection-count");
        var list = new VBox(2);
        list.getStyleClass().add("printer-checklist");
        list.setId("printer-options");
        for (var entry : entries) {
            if (!entry.bundled()) continue;
            String id = entry.definition().printer().id();
            var choice = new CheckBox(entry.toString());
            choice.setId("printer-export-" + id);
            choice.setSelected(initial.contains(id));
            choice.setMaxWidth(Double.MAX_VALUE);
            choice.getStyleClass().add("printer-choice-row");
            choice.selectedProperty().addListener((o, a, checked) -> {
                if (checked) selected.add(id);
                else selected.remove(id);
                exportButton.setDisable(!visibility && selected.isEmpty());
                count.setText(translations.text("print.printerSelectionCount", selected.size(), total));
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
        scroll.setPrefViewportHeight(
                Math.max(140, Math.min(320, owner.getScene().getHeight() - 330)));
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("printer-checklist-scroll");
        var description =
                new Label(translations.text(visibility ? "print.chooseVisiblePrinters" : "print.chooseExportPrinters"));
        description.setWrapText(true);
        description.getStyleClass().add("printer-dialog-description");
        var content = new VBox(12, description, search);
        content.setId(visibility ? "printer-visibility-dialog" : "printer-export-dialog");
        content.getStyleClass().add("printer-dialog-content");
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
            var toolbar = new javafx.scene.layout.FlowPane(8, 6, selectAll, deselectAll, invert);
            toolbar.getStyleClass().add("printer-selection-toolbar");
            content.getChildren().add(toolbar);
        }
        content.getChildren().addAll(scroll, count);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefWidth(480);
        dialog.setResultConverter(button -> button == export ? Set.copyOf(selected) : null);
        return Dialogs.show(dialog, owner, translations);
    }
}
