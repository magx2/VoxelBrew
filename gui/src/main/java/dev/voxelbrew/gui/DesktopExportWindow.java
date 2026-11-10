package dev.voxelbrew.gui;

import dev.voxelbrew.engine.parameters.ParameterSet;
import dev.voxelbrew.exporter.api.ExportDialog;
import dev.voxelbrew.exporter.api.ExportDialogs;
import dev.voxelbrew.exporter.api.ExportWindow;
import dev.voxelbrew.exporter.api.FileExportDialog;
import dev.voxelbrew.exporter.api.FileExportDialog.Destination;
import dev.voxelbrew.util.config.UserConfiguration;
import dev.voxelbrew.util.i18n.Translations;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.stage.Window;
import lombok.RequiredArgsConstructor;

/** JavaFX rendering adapter for exporter-owned save dialogs. */
@RequiredArgsConstructor
public final class DesktopExportWindow implements ExportWindow {
    private final Window owner;
    private final Path current;
    private final ParameterSet parameters;
    private final Translations translations;
    private final UserConfiguration configuration;

    private Path suggestedDestination;

    public DesktopExportWindow suggestDestination(Path destination) {
        suggestedDestination = destination;
        return this;
    }

    private final ExportDialogs dialogs = new ExportDialogs().register(FileExportDialog.class, this::chooseDestination);

    @Override
    public <R> Optional<R> show(ExportDialog<R> dialog) {
        return dialogs.show(dialog);
    }

    /** Installs a frontend renderer for a provider-defined interaction. */
    public <R, D extends ExportDialog<R>> DesktopExportWindow register(
            Class<D> type, java.util.function.BiFunction<Window, ? super D, Optional<R>> handler) {
        java.util.Objects.requireNonNull(handler, "handler");
        dialogs.register(type, request -> handler.apply(owner, request));
        return this;
    }

    private Optional<Destination> chooseDestination(FileExportDialog request) {
        var chosen = FileDialog.choose(
                owner,
                true,
                suggestedDestination == null
                        ? current
                        : dev.voxelbrew.gui.ExportDialog.destinationForFormat(
                                suggestedDestination, request.extension()),
                parameters,
                translations,
                configuration,
                request.extension(),
                request.title());
        if (chosen.isEmpty()) return Optional.empty();
        Path path = chosen.get();
        boolean overwrite = Files.exists(path);
        if (overwrite) {
            var alert = new Alert(
                    Alert.AlertType.CONFIRMATION,
                    translations.text("file.replace", path),
                    ButtonType.YES,
                    ButtonType.NO);
            if (Dialogs.show(alert, owner, translations).orElse(ButtonType.NO) != ButtonType.YES)
                return Optional.empty();
        }
        return Optional.of(new Destination(path, overwrite));
    }
}
