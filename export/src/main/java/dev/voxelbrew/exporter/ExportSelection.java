package dev.voxelbrew.exporter;

import dev.voxelbrew.engine.mesh.MeshOptions;
import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.engine.printing.*;
import dev.voxelbrew.exporter.api.ModelView;

public record ExportSelection(Space space, Contents contents, String body, String instance) {
    public enum Contents {
        MODEL,
        SUPPORTS,
        ALL
    }

    public enum Space {
        DESIGN,
        BUILD_PLATE
    }

    public static ExportSelection design(String body) {
        return new ExportSelection(Space.DESIGN, Contents.MODEL, body, null);
    }

    public ModelView view(CadDocument document, Evaluation evaluation, MeshOptions options) {
        if (body != null && instance != null)
            throw new IllegalArgumentException("Select a body or an instance, not both");
        ModelView view;
        if (space == Space.DESIGN) {
            if (contents != Contents.MODEL || instance != null)
                throw new IllegalArgumentException(
                        "Support solids and build instances require BUILD_PLATE coordinate space");
            view = ModelViews.from(evaluation);
        } else {
            view = ModelViews.from(ManufacturingModel.from(document, evaluation, options), contents);
        }
        if (body != null) view = ModelViews.select(view, body);
        if (instance != null) view = ModelViews.selectInstance(view, instance);
        return view;
    }
}
