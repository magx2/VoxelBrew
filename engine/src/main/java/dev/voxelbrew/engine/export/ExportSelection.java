package dev.voxelbrew.engine.export;

import dev.voxelbrew.engine.mesh.MeshOptions;
import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.engine.printing.*;

public record ExportSelection(
        Space space, ManufacturingModel.Selection contents, String body, String instance) {
    public enum Space {
        DESIGN,
        BUILD_PLATE
    }

    public static ExportSelection design(String body) {
        return new ExportSelection(Space.DESIGN, ManufacturingModel.Selection.MODEL, body, null);
    }

    public ModelView view(CadDocument document, Evaluation evaluation, MeshOptions options) {
        if (body != null && instance != null)
            throw new IllegalArgumentException("Select a body or an instance, not both");
        ModelView view;
        if (space == Space.DESIGN) {
            if (contents != ManufacturingModel.Selection.MODEL || instance != null)
                throw new IllegalArgumentException(
                        "Support solids and build instances require BUILD_PLATE coordinate space");
            view = ModelViews.from(evaluation);
        } else {
            view = ManufacturingModel.from(document, evaluation, options).selection(contents);
        }
        if (body != null) view = ModelViews.select(view, body);
        if (instance != null) view = ModelViews.selectInstance(view, instance);
        return view;
    }
}
