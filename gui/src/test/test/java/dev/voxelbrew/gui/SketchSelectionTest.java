package dev.voxelbrew.gui;

import static dev.voxelbrew.engine.sketch.SketchGeometry.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.sketch.SketchGeometry;
import java.util.List;
import org.junit.jupiter.api.Test;

class SketchSelectionTest {
    @Test
    void windowRequiresContainmentWhileCrossingIncludesSegmentsWithBothEndsOutside() {
        var inside = new Entity("inside", Kind.LINE, List.of(2., 2., 8., 8.), false);
        var crossing = new Entity("crossing", Kind.LINE, List.of(-5., 5., 15., 5.), false);
        var outside = new Entity("outside", Kind.LINE, List.of(-5., 12., 15., 12.), false);
        var geometry = new SketchGeometry(List.of(inside, crossing, outside), List.of(), List.of());
        var window = SketchSelection.inBox(geometry, new Vec(0, 10), new Vec(10, 0), false);
        assertEquals(java.util.Set.of(inside.ref()), window);
        var crossed = SketchSelection.inBox(geometry, new Vec(10, 0), new Vec(0, 10), true);
        assertEquals(java.util.Set.of(inside.ref(), crossing.ref()), crossed);
    }

    @Test
    void partialWindowSelectsEndpointsWithoutSelectingTheWholeLine() {
        var line = new Entity("line", Kind.LINE, List.of(2., 2., 20., 20.), false);
        var geometry = new SketchGeometry(List.of(line), List.of(), List.of());
        assertEquals(
                java.util.Set.of(line.ref(Part.START)),
                SketchSelection.inBox(geometry, new Vec(0, 0), new Vec(10, 10), false));
    }
}
