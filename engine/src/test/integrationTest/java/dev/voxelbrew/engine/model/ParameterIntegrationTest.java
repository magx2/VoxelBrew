package dev.voxelbrew.engine.model;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.parameters.*;
import dev.voxelbrew.engine.persistence.DocumentCodec;
import dev.voxelbrew.engine.sketch.*;
import dev.voxelbrew.exporter.DocumentWriter;
import dev.voxelbrew.importer.DocumentReader;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ParameterIntegrationTest {
    @TempDir
    Path directory;

    @Test
    void sharedPlanesSignedExtrusionsAndSplitOffsetsFollowConfigurations() {
        var s = new ModelSession(CadDocument.empty());
        var plane = new Feature.Plane("raised", 10);
        s.add(new Feature.Sketch("plate", "profile", plane, new Feature.Rectangle(0, 0, 60, 40)));
        s.add(new Feature.Sketch("circle", "profile", plane, new Feature.Circle(10, 10, 4)));
        s.add(new Feature.Extrude("solid", new Feature.ProfileRef("plate", "profile"), -5));
        s.add(new Feature.SplitBody(
                "split", new Feature.BodyRef("solid", "body"), new Feature.SplitPlane(1, 0, 0, 30)));
        s.setParameters(ParameterSet.empty()
                .put("elevation", ParameterUnit.MM, "10", "")
                .put("thickness", ParameterUnit.MM, "5", "")
                .put("division", ParameterUnit.MM, "30", "")
                .addConfiguration("Raised", false)
                .override("elevation", "Raised", "20")
                .override("thickness", "Raised", "8")
                .override("division", "Raised", "20"));
        s.bindExpression("circle", "offsetZ", "elevation");
        s.bindExpression("solid", "distance", "-thickness");
        s.bindExpression("split", "offset", "division");
        s.selectConfiguration("Raised");
        try (var e = s.evaluate()) {
            e.requireCompleteDocument();
            assertEquals(
                    19200,
                    e.bodies().stream().mapToDouble(b -> b.solid().volume()).sum(),
                    .01);
            var bounds = e.bodies().getFirst().solid().bounds();
            assertEquals(12, bounds.minZ(), .001);
            assertEquals(20, bounds.maxZ(), .001);
            assertEquals(20, bounds.maxX(), .001);
            for (var f : e.resolvedFeatures())
                if (f instanceof Feature.Sketch sketch)
                    assertEquals(20, sketch.plane().offsetZ());
        }
        s.editDimension("plate", "offsetZ", 30);
        assertTrue(s.document().parameters().bindings().stream()
                .noneMatch(b -> b.target().field().equals("offsetZ")));
        try (var e = s.evaluate()) {
            assertEquals(30, e.bodies().getFirst().solid().bounds().maxZ(), .001);
        }
    }

    @Test
    void legacyPositionBindingsSurviveSketchConversionAndNumericEditsDetachAliases() {
        for (var profile :
                List.<Feature.Profile>of(new Feature.Rectangle(10, 20, 60, 40), new Feature.Circle(10, 20, 4))) {
            boolean rectangle = profile instanceof Feature.Rectangle;
            var s = new ModelSession(CadDocument.empty());
            var original = new Feature.Sketch("sketch", "profile", new Feature.Plane("origin-xy", 0), profile);
            s.add(original);
            s.add(new Feature.Extrude("solid", new Feature.ProfileRef("sketch", "profile"), 5));
            s.setParameters(ParameterSet.empty()
                    .put("position", ParameterUnit.MM, "10", "")
                    .put("size", ParameterUnit.MM, rectangle ? "60" : "4", "")
                    .addConfiguration("Moved", false)
                    .override("position", "Moved", "-15"));
            s.bindExpression("sketch", rectangle ? "x" : "centerX", "position");
            s.bindExpression("sketch", rectangle ? "y" : "centerY", "position * 2");
            String dimension = rectangle ? "width" : "diameter";
            s.bindExpression("sketch", dimension, "size");
            var converted = FeatureDimensions.convert(original, s.document().parameters());
            s.replace(
                    new Feature.Sketch(
                            original.id(),
                            original.profileId(),
                            original.plane(),
                            new Feature.Constrained(converted.geometry())),
                    converted.parameters());
            s.selectConfiguration("Moved");
            try (var e = s.evaluate()) {
                var bounds = e.requireCompleteDocument().getFirst().solid().bounds();
                assertEquals(rectangle ? -15 : -17, bounds.minX(), .001);
                assertEquals(rectangle ? -30 : -32, bounds.minY(), .001);
            }
            s.editDimension("sketch", dimension, rectangle ? 70 : 6);
            assertEquals(
                    "",
                    s.document().parameters().binding(new ParameterSet.Target("sketch", "constraint:" + dimension)));
            try (var e = s.evaluate()) {
                assertEquals(
                        rectangle ? 14000 : Math.PI * 9 * 5,
                        e.requireCompleteDocument().getFirst().solid().volume(),
                        .01);
            }
        }
    }

    @Test
    void configurationRebuildsReferencePartAndPersistsStableIds() throws Exception {
        var s = new ModelSession(CadDocument.empty());
        s.add(new Feature.Sketch(
                "plate", "profile", new Feature.Plane("origin-xy", 0), new Feature.Rectangle(0, 0, 60, 40)));
        s.add(new Feature.Extrude("thickness", new Feature.ProfileRef("plate", "profile"), 5));
        s.add(new Feature.Sketch("hole", "profile", new Feature.Plane("origin-xy", 0), new Feature.Circle(10, 10, 4)));
        s.add(new Feature.ThroughCut(
                "cut", new Feature.ProfileRef("hole", "profile"), new Feature.BodyRef("thickness", "body")));
        s.setParameters(ParameterSet.empty()
                .put("width", ParameterUnit.MM, "60", "")
                .put("height", ParameterUnit.MM, "40", "")
                .put("thickness", ParameterUnit.MM, "5", "")
                .put("holeDiameter", ParameterUnit.MM, "4", "")
                .addConfiguration("Thick", false)
                .override("thickness", "Thick", "8"));
        for (var binding : Map.of(
                        "plate/width",
                        "width",
                        "plate/height",
                        "height",
                        "thickness/distance",
                        "thickness",
                        "hole/centerX",
                        "width/6",
                        "hole/centerY",
                        "height/4",
                        "hole/diameter",
                        "holeDiameter")
                .entrySet()) {
            var target = binding.getKey().split("/");
            s.bindExpression(target[0], target[1], binding.getValue());
        }
        try (var e = s.evaluate()) {
            assertEquals((2400 - Math.PI * 4) * 5, e.bodies().getFirst().solid().volume(), 1e-6);
        }
        var ids = s.document().features().stream().map(Feature::id).toList();
        String before = DocumentCodec.fingerprint(s.document());
        s.selectConfiguration("Thick");
        assertNotEquals(before, DocumentCodec.fingerprint(s.document()));
        var file = directory.resolve("parameter versions.vxb");
        DocumentWriter.save(s.document(), file, false);
        assertEquals(s.document(), DocumentReader.load(file));
        try (var e = new ModelSession(DocumentReader.load(file)).evaluate()) {
            e.requireCompleteDocument();
            var body = e.bodies().getFirst();
            assertEquals("thickness.body", body.id());
            assertEquals(19099.469035085127, body.solid().volume(), .01);
            assertFalse(body.solid().contains(10, 10, 4));
            assertEquals(8, body.solid().bounds().maxZ(), .001);
            assertEquals(ids, e.resolvedFeatures().stream().map(Feature::id).toList());
        }
        s.setCursor(3);
        try (var e = s.evaluate()) {
            assertEquals(19200, e.bodies().getFirst().solid().volume(), .01);
        }
        assertTrue(s.undo());
        assertEquals(3, s.document().cursor());
        try (var e = s.evaluate()) {
            assertEquals(12000, e.bodies().getFirst().solid().volume(), .01);
        }
    }

    @Test
    void relatedSketchDimensionsResolveSimultaneouslyAndFailedVariantsStayRepairable() {
        var g = Sketches.rectangle("r", 0, 0, 40, 40, true);
        g = g.add(SketchConstraints.create(
                g,
                "square",
                SketchGeometry.Type.EQUAL,
                List.of(g.entity("r-bottom").ref(), g.entity("r-right").ref()),
                0));
        var s = new ModelSession(CadDocument.empty());
        s.add(new Feature.Sketch("square", "profile", new Feature.Plane("origin-xy", 0), new Feature.Constrained(g)));
        s.add(new Feature.Extrude("solid", new Feature.ProfileRef("square", "profile"), 2));
        s.setParameters(ParameterSet.empty()
                .put("size", ParameterUnit.MM, "40", "")
                .addConfiguration("Large", false)
                .override("size", "Large", "80")
                .addConfiguration("Invalid", false)
                .override("size", "Invalid", "0"));
        s.bindExpression("square", "constraint:width", "size");
        s.bindExpression("square", "constraint:height", "size");
        s.selectConfiguration("Large");
        try (var e = s.evaluate()) {
            assertEquals(12800, e.bodies().getFirst().solid().volume(), .01);
        }
        s.selectConfiguration("Invalid");
        try (var e = s.evaluate()) {
            assertFalse(e.valid());
            assertThrows(IllegalStateException.class, e::bodies);
            assertEquals(Evaluation.State.BLOCKED, e.diagnostics().getLast().state());
        }
        s.undo();
        try (var e = s.evaluate()) {
            assertEquals(12800, e.bodies().getFirst().solid().volume(), .01);
        }
    }
}
