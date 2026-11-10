package dev.voxelbrew.gui;

import dev.voxelbrew.util.i18n.Translations;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/** Shared, text-and-color availability feedback for provider settings and process controls. */
final class SlicerStatusView extends VBox {
    private final SlicerAvailability availability;
    private final Translations translations;
    private final Label heading = new Label();
    private final Label detail = new Label();

    SlicerStatusView(SlicerAvailability availability, Translations translations, String detailId) {
        super(6);
        this.availability = availability;
        this.translations = translations;
        getStyleClass().add("slicer-availability");
        getStylesheets()
                .add(SlicerStatusView.class.getResource("slicer-status.css").toExternalForm());
        heading.getStyleClass().add("slicer-availability-heading");
        heading.setWrapText(true);
        detail.setWrapText(true);
        detail.setId(detailId);
        setMinWidth(0);
        setMaxWidth(Double.MAX_VALUE);
        getChildren().addAll(heading, detail);
    }

    void showBackend(String id) {
        var status = id == null ? null : availability.statuses.get(id);
        String state =
                id == null ? "unavailable" : status == null ? "checking" : status.working() ? "working" : "unavailable";
        getStyleClass().removeAll("working", "unavailable", "checking");
        getStyleClass().add(state);
        heading.setText(translations.text("slicer.status.heading." + state));
        detail.setText(id == null ? translations.text("gui.choose_an_available_backend") : availability.text(id));
    }
}
