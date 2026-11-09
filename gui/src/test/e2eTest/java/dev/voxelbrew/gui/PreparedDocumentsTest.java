package dev.voxelbrew.gui;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.model.Evaluator;
import dev.voxelbrew.engine.model.Feature;
import dev.voxelbrew.importer.DocumentReader;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreparedDocumentsTest {
    @TempDir
    Path directory;

    @Test
    void savedInputsReopenWithEditableProfilesAndExactPlateGeometry() throws Exception {
        for (int offset : new int[] {0, 12}) {
            var document = DocumentReader.load(PreparedDocuments.copy("rectangle-" + offset + ".jcad", directory));
            assertEquals(1, document.cursor());
            var sketch =
                    assertInstanceOf(Feature.Sketch.class, document.features().getFirst());
            assertEquals(offset, sketch.plane().offsetZ());
            assertEquals(
                    4,
                    assertInstanceOf(Feature.Constrained.class, sketch.profile())
                            .geometry()
                            .entities()
                            .size());
        }
        var plate = DocumentReader.load(PreparedDocuments.copy("plate-5mm.jcad", directory));
        assertEquals(2, plate.cursor());
        try (var evaluation = Evaluator.evaluate(plate)) {
            assertEquals(1, evaluation.bodies().size());
            var solid = evaluation.bodies().getFirst().solid();
            assertEquals(12000, solid.volume(), .01);
            assertEquals(60, solid.bounds().maxX(), .001);
            assertEquals(40, solid.bounds().maxY(), .001);
            assertEquals(5, solid.bounds().maxZ(), .001);
        }
    }
}
