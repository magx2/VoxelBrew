package dev.voxelbrew.exporter;

import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.engine.printing.*;

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
}
