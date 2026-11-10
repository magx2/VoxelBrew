package dev.voxelbrew.gui;

import dagger.Module;
import dagger.Provides;
import dev.voxelbrew.engine.model.CadDocument;
import dev.voxelbrew.exporter.api.ExporterCatalog;
import dev.voxelbrew.slicer.api.SlicerCatalog;
import java.util.concurrent.ExecutorService;
import java.util.function.*;
import javafx.application.Platform;
import javax.inject.Named;

@Module
final class GuiModule {
    @Provides
    static dev.voxelbrew.engine.printing.PrinterCatalog printers() {
        return new dev.voxelbrew.engine.printing.PrinterCatalog(
                new dev.voxelbrew.util.config.PrinterResources(dev.voxelbrew.util.config.ApplicationPaths.printers()));
    }

    @Provides
    static dev.voxelbrew.engine.feature.FeatureRegistry.Factory features() {
        return dev.voxelbrew.engine.feature.FeatureRegistry::new;
    }

    @Provides
    @Named("sketchWorkers")
    static Supplier<ExecutorService> sketchWorkers() {
        return () -> WindowResources.worker("sketch-solver");
    }

    @Provides
    static Function<dev.voxelbrew.engine.sketch.SketchGeometry, dev.voxelbrew.engine.sketch.SketchSolver.Result>
            sketchSolver() {
        return dev.voxelbrew.engine.sketch.SketchSolver::solve;
    }

    @Provides
    @Named("updateWorkers")
    static Supplier<ExecutorService> updateWorkers() {
        return () -> WindowResources.worker("update");
    }

    @Provides
    @Named("ui")
    static Consumer<Runnable> dispatch() {
        return Platform::runLater;
    }

    @Provides
    static Supplier<CadDocument> current(DocumentController document) {
        return () -> document.snapshot().document();
    }

    @Provides
    static ExporterCatalog exporters(WindowResources resources) {
        return resources.exporters;
    }

    @Provides
    static SlicerCatalog slicers(WindowResources resources) {
        return resources.slicers;
    }

    @Provides
    @Named("rebuild")
    static ExecutorService rebuild(WindowResources resources) {
        return resources.rebuild;
    }

    @Provides
    @Named("files")
    static ExecutorService files(WindowResources resources) {
        return resources.files;
    }

    @Provides
    @Named("slicing")
    static ExecutorService slicing(WindowResources resources) {
        return resources.slicing;
    }

    @Provides
    @Named("validation")
    static ExecutorService validation(WindowResources resources) {
        return resources.validation;
    }

    @Provides
    @Named("appearance")
    static ExecutorService appearance(WindowResources resources) {
        return resources.appearance;
    }
}
