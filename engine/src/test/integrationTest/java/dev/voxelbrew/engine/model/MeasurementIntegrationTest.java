package dev.voxelbrew.engine.model;

import static dev.voxelbrew.engine.units.Measurements.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.geometry.Solid;
import dev.voxelbrew.engine.mesh.MeshOptions;
import dev.voxelbrew.engine.persistence.proto.DocumentProto;
import dev.voxelbrew.exporter.DocumentWriter;
import dev.voxelbrew.importer.DocumentReader;
import dev.voxelbrew.util.Version;
import java.nio.file.*;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MeasurementIntegrationTest {
    @TempDir Path directory;

    @Test
    void typedKernelInputsAndMeasurementsUseCanonicalMillimeters() {
        var zero = quantity(0, MILLIMETRE);
        try (var plate =
                        Solid.rectanglePrism(
                                zero,
                                zero,
                                zero,
                                quantity(6, CENTIMETRE),
                                quantity(.04, METRE),
                                quantity(.8, CENTIMETRE));
                var part =
                        plate.cutThroughCircle(
                                quantity(1, CENTIMETRE),
                                quantity(10, MILLIMETRE),
                                quantity(.4, CENTIMETRE))) {
            assertEquals(
                    19.099469035085127, value(part.volumeQuantity(), CUBIC_CENTIMETRE), .00001);
            assertEquals(6, value(part.bounds().width(), CENTIMETRE), 1e-4);
            assertEquals(40, value(part.bounds().height(), MILLIMETRE), .001);
            assertEquals(8, value(part.bounds().depth(), MILLIMETRE), .001);
            assertFalse(
                    part.contains(
                            quantity(1, CENTIMETRE),
                            quantity(1, CENTIMETRE),
                            quantity(.004, METRE)));
            assertTrue(
                    part.tessellate(
                                            new MeshOptions(
                                                    quantity(.002, CENTIMETRE),
                                                    quantity(Math.PI / 36, RADIAN)))
                                    .triangleCount()
                            > 12);
        }
        var session = new ModelSession(CadDocument.empty());
        var xy = new Feature.Plane("origin-xy", zero);
        session.add(
                new Feature.Sketch(
                        "plate",
                        "profile",
                        xy,
                        new Feature.Rectangle(
                                zero, zero, quantity(6, CENTIMETRE), quantity(40, MILLIMETRE))));
        session.add(
                new Feature.Extrude(
                        "thickness",
                        new Feature.ProfileRef("plate", "profile"),
                        quantity(.5, CENTIMETRE)));
        session.add(
                new Feature.Sketch(
                        "hole",
                        "profile",
                        xy,
                        new Feature.Circle(
                                quantity(1, CENTIMETRE),
                                quantity(1, CENTIMETRE),
                                quantity(.4, CENTIMETRE))));
        session.add(
                new Feature.ThroughCut(
                        "cut",
                        new Feature.ProfileRef("hole", "profile"),
                        new Feature.BodyRef("thickness", "body")));
        session.editDimension("thickness", "distance", quantity(.8, CENTIMETRE));
        var before = session.snapshot();
        assertThrows(
                IllegalArgumentException.class,
                () -> session.editDimension("thickness", "distance", quantity(8, DEGREE)));
        assertEquals(before, session.snapshot());
        try (var e = session.evaluate()) {
            assertEquals(19099.469035085127, e.bodies().getFirst().solid().volume(), .01);
        }
    }

    @Test
    void preJsr385FixturePreservesMeasurementsWhenSavingWithCurrentWriterVersion()
            throws Exception {
        byte[] original;
        try (var resource =
                getClass().getResourceAsStream("/parameters/schema6-configurations.jcad")) {
            assertNotNull(resource);
            original = resource.readAllBytes();
        }
        var file = directory.resolve("old parameter versions.jcad");
        Files.write(file, original);
        var document = DocumentReader.load(file);
        assertEquals("Large", document.parameters().activeName());
        var session = new ModelSession(document);
        try (var e = session.evaluate()) {
            assertEquals(25600, e.bodies().getFirst().solid().volume(), .02);
        }
        DocumentWriter.save(document, file, true);
        byte[] saved = Files.readAllBytes(file);
        var before =
                DocumentProto.Document.parseFrom(Arrays.copyOfRange(original, 44, original.length));
        var after = DocumentProto.Document.parseFrom(Arrays.copyOfRange(saved, 44, saved.length));
        var migrated =
                before.toBuilder()
                        .setSchemaVersion(
                                dev.voxelbrew.engine.persistence.DocumentCodec.SCHEMA_VERSION)
                        .setApplicationVersion(Version.version());
        for (var feature : migrated.getFeaturesBuilderList())
            if (feature.hasSketch())
                feature.getSketchBuilder()
                        .getPlaneBuilder()
                        .addAllFrame(Arrays.stream(Transform.IDENTITY.matrix()).boxed().toList());
        assertEquals(migrated.build(), after);
        assertEquals(document, DocumentReader.load(file));
        session.selectConfiguration("Default");
        try (var e = session.evaluate()) {
            assertEquals(12000, e.bodies().getFirst().solid().volume(), .02);
        }
    }
}
