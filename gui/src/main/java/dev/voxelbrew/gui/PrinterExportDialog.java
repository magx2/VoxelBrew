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
        var dialog = new Dialog<Set<String>>();
        dialog.initOwner(owner);
        dialog.setTitle(translations.text("print.exportPrinters"));
        dialog.setHeaderText(translations.text("print.chooseExportPrinters"));
        var export = new ButtonType(translations.text("print.exportSelectedPrinters"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(export, ButtonType.CANCEL);
        dialog.getDialogPane().setId("printer-export-dialog");
        var exportButton = dialog.getDialogPane().lookupButton(export);
        exportButton.setId("printer-export-confirm");
        exportButton.setDisable(true);
        Set<String> selected = new LinkedHashSet<>();
        var list = new VBox(6);
        for (var entry : entries) {
            if (!entry.bundled()) continue;
            String id = entry.definition().printer().id();
            var choice = new CheckBox(entry.toString());
            choice.setId("printer-export-" + id);
            choice.selectedProperty().addListener((o, a, checked) -> {
                if (checked) selected.add(id);
                else selected.remove(id);
                exportButton.setDisable(selected.isEmpty());
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
        dialog.getDialogPane().setContent(new VBox(10, search, scroll));
        dialog.getDialogPane().setPrefWidth(480);
        dialog.setResultConverter(button -> button == export ? Set.copyOf(selected) : Set.of());
        return dialog.showAndWait().orElse(Set.of());
    }
}
