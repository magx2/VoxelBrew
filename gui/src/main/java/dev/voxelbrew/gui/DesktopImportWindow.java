package dev.voxelbrew.gui;

import dev.voxelbrew.engine.parameters.ParameterSet;
import dev.voxelbrew.importer.api.*;
import dev.voxelbrew.util.i18n.Translations;
import java.util.Optional;
import javafx.stage.Window;

/** Window-local rendering for importer-owned interactions. */
@lombok.RequiredArgsConstructor
public final class DesktopImportWindow implements ImportWindow {
    private final Window owner;
    private final ParameterSet parameters;
    private final Translations translations;
    private final ImportDialogs dialogs = new ImportDialogs().register(FileImportDialog.class, this::chooseSource);

    private Optional<FileImportDialog.Source> chooseSource(FileImportDialog request) {
        return ImportDialog.options(owner, request, parameters, translations);
    }

    @Override
    public <R> Optional<R> show(dev.voxelbrew.importer.api.ImportDialog<R> dialog) {
        return dialogs.show(dialog);
    }

    public <R, D extends dev.voxelbrew.importer.api.ImportDialog<R>> DesktopImportWindow register(
            Class<D> type, java.util.function.BiFunction<Window, ? super D, Optional<R>> handler) {
        java.util.Objects.requireNonNull(handler);
        dialogs.register(type, request -> handler.apply(owner, request));
        return this;
    }
}
