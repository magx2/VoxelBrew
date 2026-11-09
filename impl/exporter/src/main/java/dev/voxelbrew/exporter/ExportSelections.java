package dev.voxelbrew.exporter;

import dev.voxelbrew.engine.mesh.MeshOptions;
import dev.voxelbrew.engine.model.*;

public final class ExportSelections {
    private ExportSelections() {}

    public static ModelView view(
            ExportSelection selection, CadDocument document, Evaluation evaluation, MeshOptions options) {
        if (selection.body() != null && selection.instance() != null)
            throw new IllegalArgumentException("Select a body or an instance, not both");
        ModelView view;
        if (selection.space() == ExportSelection.Space.DESIGN) {
            if (selection.contents() != ExportSelection.Contents.MODEL || selection.instance() != null)
                throw new IllegalArgumentException(
                        "Support solids and build instances require BUILD_PLATE coordinate space");
            view = ModelViews.from(evaluation);
        } else {
            view = ModelViews.from(
                    dev.voxelbrew.engine.printing.ManufacturingModels.from(document, evaluation, options),
                    selection.contents());
        }
        if (selection.body() != null) view = ModelViews.select(view, selection.body());
        if (selection.instance() != null) view = ModelViews.selectInstance(view, selection.instance());
        return view;
    }
}
