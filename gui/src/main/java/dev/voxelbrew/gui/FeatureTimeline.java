package dev.voxelbrew.gui;

import dev.voxelbrew.engine.model.CadDocument;
import dev.voxelbrew.engine.model.Evaluation;
import dev.voxelbrew.engine.model.Feature;
import dev.voxelbrew.util.i18n.Translations;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import javafx.animation.PauseTransition;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.*;
import javafx.scene.shape.SVGPath;
import javafx.util.Duration;
import lombok.extern.slf4j.Slf4j;

/** Compact feature history. Moving its marker changes rollback, never the undo stack. */
@Slf4j
final class FeatureTimeline extends HBox implements AutoCloseable {
    private static final double STEP = 38, INSET = 16, HEIGHT = 56;
    private final Translations translations;
    private final IntConsumer moveCursor;
    private final Consumer<String> select;
    private final java.util.function.BiConsumer<Integer, Integer> delete;
    private final Function<Feature, String> name;
    private final Pane track = new Pane();
    private final Pane ticks = new Pane();
    private final Pane features = new Pane();
    private final Region rail = new Region();
    private final Button marker = new Button();
    private final Label empty = new Label(), count = new Label();
    private final ScrollPane scroll = new ScrollPane(track);
    private final Button first, previous, play, next, last;
    private final PauseTransition playback = new PauseTransition(Duration.millis(650));
    private List<Feature> definitions = List.of();
    private String documentId;
    private int position, playbackPosition;
    private boolean unavailable, rebuilding, failed, playing, closed, dragging;
    private double dragStartX;
    private int dragStartPosition;

    FeatureTimeline(
            Translations translations,
            IntConsumer moveCursor,
            Consumer<String> select,
            Function<Feature, String> name,
            java.util.function.BiConsumer<Integer, Integer> delete) {
        this.translations = translations;
        this.moveCursor = moveCursor;
        this.select = select;
        this.name = name;
        this.delete = delete;
        setId("feature-timeline");
        getStyleClass().add("timeline");
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(8);
        first = transport("timeline-first", "M4 3H6V17H4Z M17 3L7 10L17 17Z", () -> seek(0));
        previous = transport("timeline-previous", "M5 3H7V17H5Z M16 4L8 10L16 16Z", () -> seek(position - 1));
        play = transport("timeline-play", "M5 3L17 10L5 17Z", this::togglePlayback);
        next = transport("timeline-next", "M13 3H15V17H13Z M4 4L12 10L4 16Z", () -> seek(position + 1));
        last = transport("timeline-last", "M14 3H16V17H14Z M3 3L13 10L3 17Z", () -> seek(definitions.size()));
        var navigation = new HBox(2, first, previous, play, next, last);
        navigation.setAlignment(Pos.CENTER);
        navigation.getStyleClass().add("timeline-navigation");
        var divider = new Separator(javafx.geometry.Orientation.VERTICAL);
        divider.setPrefHeight(26);
        divider.setMaxHeight(26);
        scroll.setId("timeline-scroll");
        scroll.getStyleClass().add("timeline-scroll");
        scroll.setFitToHeight(true);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setMinWidth(80);
        scroll.setPrefHeight(HEIGHT + 16);
        HBox.setHgrow(scroll, Priority.ALWAYS);
        count.setId("timeline-position");
        count.getStyleClass().add("timeline-count");
        count.setMinWidth(Region.USE_PREF_SIZE);
        UiHelp.describe(count, translations.text("timeline.positionHelp"));
        getChildren().addAll(navigation, divider, scroll, count);
        track.setMinHeight(HEIGHT);
        track.getStyleClass().add("timeline-track");
        ticks.setMouseTransparent(true);
        features.setPickOnBounds(false);
        rail.setMouseTransparent(true);
        rail.getStyleClass().add("timeline-rail");
        empty.setText(translations.text("timeline.empty"));
        empty.getStyleClass().add("timeline-empty");
        empty.setMouseTransparent(true);
        empty.relocate(INSET + 12, 18);
        marker.setId("history-cursor");
        marker.getStyleClass().add("timeline-marker");
        marker.setGraphic(path("M1 0H13V6L8 11V44H6V11L1 6Z", "timeline-marker-shape"));
        marker.setCursor(Cursor.H_RESIZE);
        marker.setAccessibleText(translations.text("timeline.marker"));
        UiHelp.install(marker, translations);
        marker.setOnMousePressed(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            stop();
            dragging = true;
            dragStartX = track.sceneToLocal(e.getSceneX(), e.getSceneY()).getX();
            dragStartPosition = position;
            marker.requestFocus();
            e.consume();
        });
        marker.setOnMouseDragged(e -> {
            if (!dragging || unavailable) return;
            double x = track.sceneToLocal(e.getSceneX(), e.getSceneY()).getX();
            request(dragStartPosition + (int) Math.round((x - dragStartX) / STEP));
            revealMarker();
            e.consume();
        });
        marker.setOnMouseReleased(e -> dragging = false);
        marker.setOnContextMenuRequested(e -> {
            stop();
            var item = new MenuItem(translations.text("timeline.deleteBefore"));
            item.setDisable(unavailable || rebuilding || position == 0);
            item.setOnAction(action -> delete.accept(0, position));
            new ContextMenu(item).show(marker, e.getScreenX(), e.getScreenY());
            e.consume();
        });
        marker.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            int target = switch (e.getCode()) {
                case HOME -> 0;
                case END -> definitions.size();
                case LEFT, DOWN -> position - 1;
                case RIGHT, UP -> position + 1;
                default -> -1;
            };
            if (target >= 0 || e.getCode() == KeyCode.LEFT || e.getCode() == KeyCode.DOWN) {
                seek(target);
                e.consume();
            }
        });
        track.getChildren().addAll(rail, ticks, features, empty, marker);
        track.setOnMousePressed(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getTarget() == track) {
                seek((int) Math.round((e.getX() - INSET) / STEP));
                marker.requestFocus();
            }
        });
        scroll.setOnScroll(e -> {
            double overflow = track.getWidth() - scroll.getViewportBounds().getWidth();
            if (overflow > 0) {
                double delta = e.getDeltaX() == 0 ? e.getDeltaY() : e.getDeltaX();
                scroll.setHvalue(Math.clamp(scroll.getHvalue() - delta / overflow, 0, 1));
                e.consume();
            }
        });
        playback.setOnFinished(e -> advance());
        track.widthProperty().addListener((o, a, b) -> revealMarker());
        scroll.viewportBoundsProperty().addListener((o, a, b) -> revealMarker());
        disabledProperty().addListener((o, a, b) -> {
            if (b) stop();
        });
    }

    void update(
            CadDocument document,
            String selection,
            List<Evaluation.Diagnostic> diagnostics,
            boolean unavailable,
            boolean rebuilding,
            boolean valid) {
        boolean changed = !Objects.equals(documentId, document.id()) || !definitions.equals(document.features());
        this.unavailable = unavailable;
        this.rebuilding = rebuilding;
        this.failed = !valid && !rebuilding;
        if (changed || unavailable || failed || (playing && document.cursor() != playbackPosition)) stop();
        documentId = document.id();
        definitions = document.features();
        position = document.cursor();
        if (changed) {
            rebuildFeatures();
            javafx.application.Platform.runLater(() -> {
                if (!closed) revealMarker();
            });
        }
        count.setText(position + " / " + definitions.size());
        marker.resizeRelocate(INSET + position * STEP - 7, 3, 14, 48);
        marker.setAccessibleHelp(translations.text("timeline.markerPosition", position, definitions.size())
                + " "
                + translations.text("help.history-cursor"));
        marker.setDisable(unavailable || definitions.isEmpty());
        first.setDisable(unavailable || position == 0);
        previous.setDisable(unavailable || position == 0);
        next.setDisable(unavailable || position == definitions.size());
        last.setDisable(unavailable || position == definitions.size());
        play.setDisable(unavailable || definitions.isEmpty() || failed);
        for (int i = 0; i < definitions.size(); i++) {
            var feature = definitions.get(i);
            var button = (Button) features.getChildren().get(i);
            button.getStyleClass()
                    .setAll("button", "timeline-feature", i < position ? "history-active" : "history-future");
            if (feature.id().equals(selection)) button.getStyleClass().add("history-selected");
            var diagnostic = diagnostics.stream()
                    .filter(d -> d.featureId().equals(feature.id())
                            && (d.state() == Evaluation.State.FAILED || d.state() == Evaluation.State.BLOCKED))
                    .findFirst();
            // Diagnostics belong to the evaluated prefix, never to rolled-back future operations.
            boolean broken = i < position && !rebuilding && diagnostic.isPresent();
            if (broken) button.getStyleClass().add("history-error");
            String description = (i + 1)
                    + " · "
                    + name.apply(feature)
                    + "\n"
                    + translations.text(i < position ? "timeline.evaluated" : "timeline.rolledBack")
                    + (broken ? "\n" + diagnostic.orElseThrow().message() : "")
                    + "\n"
                    + translations.text("help.history-feature");
            UiHelp.describe(button, description);
            button.setDisable(unavailable);
        }
    }

    private void rebuildFeatures() {
        features.getChildren().clear();
        ticks.getChildren().clear();
        double width = INSET * 2 + definitions.size() * STEP;
        track.setMinWidth(width);
        track.setPrefWidth(width);
        rail.resizeRelocate(INSET, 46, Math.max(STEP, definitions.size() * STEP), 2);
        for (int i = 0; i < definitions.size(); i++) {
            var feature = definitions.get(i);
            var button = new Button(name.apply(feature), featureIcon(feature));
            button.setId("history-" + i);
            final int index = i;
            button.setOnContextMenuRequested(e -> {
                stop();
                var remove = new MenuItem(translations.text("timeline.deleteFeature"));
                remove.setDisable(unavailable || rebuilding || index >= position);
                remove.setOnAction(action -> delete.accept(index, index + 1));
                var after = new MenuItem(translations.text("timeline.deleteAfter"));
                after.setDisable(unavailable || rebuilding || index + 1 == definitions.size());
                after.setOnAction(action -> delete.accept(index + 1, definitions.size()));
                new ContextMenu(remove, after).show(button, e.getScreenX(), e.getScreenY());
                e.consume();
            });
            button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            button.setAccessibleText((i + 1) + ". " + name.apply(feature));
            button.setOnAction(e -> {
                stop();
                select.accept(feature.id());
            });
            button.resizeRelocate(INSET + i * STEP + 3, 10, STEP - 6, 32);
            button.setMinSize(STEP - 6, 32);
            button.setMaxSize(STEP - 6, 32);
            button.setManaged(false);
            features.getChildren().add(button);
            var tick = new Region();
            tick.getStyleClass().add("timeline-tick");
            tick.setManaged(false);
            tick.resizeRelocate(INSET + (i + 0.5) * STEP - 1, 42, 2, 6);
            ticks.getChildren().add(tick);
        }
        // Pane must not resize the explicitly positioned marker or rail to their preferred size.
        for (var node : track.getChildren()) if (node != empty) node.setManaged(false);
        empty.setVisible(definitions.isEmpty());
        if (definitions.isEmpty()) track.setMinWidth(220);
    }

    private Button transport(String id, String shape, Runnable action) {
        var button = new Button();
        button.setId(id);
        button.getStyleClass().add("timeline-transport");
        button.setGraphic(path(shape, "timeline-transport-icon"));
        button.setAccessibleText(translations.text("help." + id));
        UiHelp.install(button, translations);
        button.setOnAction(e -> action.run());
        return button;
    }

    private void seek(int target) {
        stop();
        request(target);
        revealMarker();
    }

    private void request(int target) {
        if (unavailable || closed || isDisabled()) return;
        int bounded = Math.clamp(target, 0, definitions.size());
        if (bounded == position) return;
        playbackPosition = bounded;
        log.debug("Moving history cursor from {} to {}", position, bounded);
        moveCursor.accept(bounded);
    }

    private void togglePlayback() {
        if (playing) {
            stop();
            return;
        }
        if (unavailable || failed || definitions.isEmpty()) return;
        playing = true;
        playbackPosition = position;
        showPlaybackState();
        log.info("Playing design history ({} operations)", definitions.size());
        if (position == definitions.size()) request(0);
        playback.playFromStart();
    }

    private void advance() {
        if (!playing || closed) return;
        if (unavailable || failed || isDisabled()) {
            stop();
            return;
        }
        if (!rebuilding) {
            if (position == definitions.size()) {
                stop();
                return;
            }
            request(position + 1);
            revealMarker();
        }
        if (playing) playback.playFromStart();
    }

    void stop() {
        playback.stop();
        if (!playing) return;
        playing = false;
        showPlaybackState();
        log.debug("Stopped history playback at {}", position);
    }

    private void showPlaybackState() {
        play.setGraphic(path(playing ? "M4 3H8V17H4Z M12 3H16V17H12Z" : "M5 3L17 10L5 17Z", "timeline-transport-icon"));
        String text = translations.text(playing ? "help.timeline-pause" : "help.timeline-play");
        play.setAccessibleText(text);
        UiHelp.describe(play, text);
        play.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("playing"), playing);
    }

    private void revealMarker() {
        double viewport = scroll.getViewportBounds().getWidth();
        double overflow = track.getWidth() - viewport;
        if (overflow <= 0) return;
        if (position == 0 || position == definitions.size()) {
            scroll.setHvalue(position == 0 ? 0 : 1);
            return;
        }
        double left = scroll.getHvalue() * overflow, x = INSET + position * STEP;
        if (x < left + 12) scroll.setHvalue(Math.max(0, (x - 12) / overflow));
        else if (x > left + viewport - 12) scroll.setHvalue(Math.min(1, (x - viewport + 12) / overflow));
    }

    private static SVGPath path(String content, String style) {
        var path = new SVGPath();
        path.setContent(content);
        path.getStyleClass().add(style);
        path.setMouseTransparent(true);
        return path;
    }

    private static Node featureIcon(Feature feature) {
        if (feature instanceof Feature.Sketch)
            return new Group(
                    path("M2 4H15V17H2Z", "timeline-sketch-icon"),
                    path("M7 18L8 14L17 5L20 8L11 17Z", "timeline-pencil-icon"));
        var cube = new Group(
                path("M2 6L10 2L18 6L10 10Z", "timeline-cube-top"),
                path("M2 6L10 10V20L2 16Z", "timeline-cube-left"),
                path("M10 10L18 6V16L10 20Z", "timeline-cube-right"));
        if (feature instanceof Feature.Extrude)
            cube.getChildren().add(path("M17 5L20 1L23 5H21V14H19V5Z", "timeline-extrude-icon"));
        else if (feature instanceof Feature.ThroughCut)
            cube.getChildren().add(path("M8 5L10 4L13 5L13 8L11 9L8 8Z M10 9V16", "timeline-cut-icon"));
        else if (feature instanceof Feature.SplitBody)
            cube.getChildren().add(path("M6 1L14 21", "timeline-split-icon"));
        else cube.getChildren().add(path("M17 2V10 M13 6H21", "timeline-plugin-icon"));
        return cube;
    }

    @Override
    public void close() {
        closed = true;
        stop();
    }
}
