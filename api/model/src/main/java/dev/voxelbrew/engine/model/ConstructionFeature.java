package dev.voxelbrew.engine.model;

import static dev.voxelbrew.engine.construction.ConstructionGeometry.*;

import dev.voxelbrew.engine.construction.ConstructionTools;
import java.util.List;

/** Editable operation and references; evaluated geometry is never serialized. */
public record ConstructionFeature(
        String id, ConstructionTools.Tool tool, List<Input> inputs, double value, boolean alternate)
        implements Feature {
    public enum Selection {
        ORIGIN_PLANE,
        ORIGIN_AXIS,
        ORIGIN_POINT,
        DATUM,
        FACE,
        EDGE,
        VERTEX,
        POSITION
    }

    public record Input(Selection selection, String source, String output, int index, Vector position) {
        public Input {
            java.util.Objects.requireNonNull(selection);
            java.util.Objects.requireNonNull(source);
            java.util.Objects.requireNonNull(output);
            java.util.Objects.requireNonNull(position);
            if (index < 0) throw new IllegalArgumentException("Negative construction reference index");
            if ((selection == Selection.ORIGIN_PLANE || selection == Selection.ORIGIN_AXIS) && index > 2)
                throw new IllegalArgumentException("Unknown origin direction");
            if (selection == Selection.DATUM && index > 6)
                throw new IllegalArgumentException("Unknown coordinate-system component");
            if (selection == Selection.DATUM
                    || selection == Selection.FACE
                    || selection == Selection.EDGE
                    || selection == Selection.VERTEX) CadDocument.identifier(source, "construction input");
        }

        public static Input originPlane(int index) {
            return new Input(Selection.ORIGIN_PLANE, "", "", index, new Vector(0, 0, 0));
        }
    }

    public ConstructionFeature {
        CadDocument.identifier(id, "feature ID");
        java.util.Objects.requireNonNull(tool);
        inputs = List.copyOf(inputs);
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Construction value must be finite");
        int expected = tool.inputs().size();
        if (inputs.size() != expected) throw new IllegalArgumentException(tool + " requires " + expected + " inputs");
    }

    public List<String> dependencies() {
        return inputs.stream()
                .filter(i -> switch (i.selection()) {
                    case DATUM, FACE, EDGE, VERTEX -> true;
                    default -> false;
                })
                .map(Input::source)
                .distinct()
                .toList();
    }
}
