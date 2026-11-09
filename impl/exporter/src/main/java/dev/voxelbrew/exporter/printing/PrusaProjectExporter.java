package dev.voxelbrew.exporter.printing;

import dev.voxelbrew.engine.mesh.*;
import dev.voxelbrew.engine.model.BodyInstance;
import dev.voxelbrew.engine.printing.*;
import dev.voxelbrew.exporter.ManufacturingView;
import dev.voxelbrew.exporter.MeshExportOption;
import dev.voxelbrew.exporter.MeshExporter;
import dev.voxelbrew.exporter.MeshFormat;
import dev.voxelbrew.exporter.ModelView;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import javax.xml.stream.*;

/** PrusaSlicer 2.7.2 project dialect, deliberately distinct from portable 3MF Core. */
public final class PrusaProjectExporter extends MeshExporter {
    public PrusaProjectExporter() {
        super();
    }

    public PrusaProjectExporter(java.util.concurrent.Executor worker, java.nio.file.Path plugins) {
        super(worker, plugins);
    }

    public record Volume(String name, String type, MeshData mesh) {}

    @Override
    public MeshFormat format() {
        return new MeshFormat(
                "prusa-project",
                "PrusaSlicer 2.7.2 project",
                List.of("3mf"),
                true,
                List.of(new MeshExportOption(
                        "include-support-solids",
                        "Include explicit support solids",
                        MeshExportOption.Type.BOOLEAN,
                        "false",
                        List.of())));
    }

    @Override
    public void write(ModelView view, MeshOptions options, Map<String, String> supplied, OutputStream output)
            throws IOException {
        if (!(view instanceof ManufacturingView manufacturing))
            throw new IOException(
                    "Prusa project export requires the complete build-plate setup, with profiles and support instructions");
        var model = manufacturing.manufacturingModel();
        PrintValidation.validate(model).requireValid();
        var setup = model.setup();
        if (!Boolean.parseBoolean(supplied.getOrDefault("include-support-solids", "false")))
            setup = setup.withSupports(setup.supports().stream()
                    .filter(s -> s.role() != SupportVolume.Role.SOLID_SUPPORT)
                    .toList());
        writeProject(
                new ManufacturingModel(model.documentId(), model.revision(), setup, model.profile(), model.meshes()),
                output);
    }

    public static List<Volume> volumes(ManufacturingModel model, BodyInstance instance) {
        var result = new ArrayList<Volume>();
        result.add(new Volume(
                instance.id() + "/" + instance.bodyId(), "ModelPart", MeshValidation.weld(model.placedMesh(instance))));
        for (var support : model.setup().supports())
            if (support.instanceId().equals(instance.id()))
                result.add(new Volume(
                        instance.id() + "/" + support.id(),
                        switch (support.role()) {
                            case BLOCKER -> "SupportBlocker";
                            case ENFORCER -> "SupportEnforcer";
                            case SOLID_SUPPORT -> "ModelPart";
                        },
                        MeshGeometry.transform(MeshGeometry.box(support), instance.transform())));
        return List.copyOf(result);
    }

    public static void writeProject(ManufacturingModel model, OutputStream output) throws IOException {
        // One multipart assembly keeps stacked CAD bodies at their declared Z coordinates.
        // Prusa's --merge flag does not merge objects already inside a single project file.
        var volumes = model.instances().stream()
                .flatMap(i -> volumes(model, i).stream())
                .toList();
        try (var zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            text(
                    zip,
                    "[Content_Types].xml",
                    "<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"model\" ContentType=\"application/vnd.ms-package.3dmanufacturing-3dmodel+xml\"/><Default Extension=\"config\" ContentType=\"text/plain\"/></Types>");
            text(
                    zip,
                    "_rels/.rels",
                    "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Target=\"/3D/3dmodel.model\" Id=\"rel0\" Type=\"http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel\"/></Relationships>");
            zip.putNextEntry(new ZipEntry("3D/3dmodel.model"));
            var xml = XMLOutputFactory.newFactory().createXMLStreamWriter(zip, "UTF-8");
            xml.writeStartDocument("UTF-8", "1.0");
            xml.writeStartElement("model");
            xml.writeDefaultNamespace("http://schemas.microsoft.com/3dmanufacturing/core/2015/02");
            xml.writeAttribute("unit", "millimeter");
            xml.writeStartElement("metadata");
            xml.writeAttribute("name", "slic3rpe:Version3mf");
            xml.writeCharacters("1");
            xml.writeEndElement();
            xml.writeStartElement("resources");
            xml.writeStartElement("object");
            xml.writeAttribute("id", "1");
            xml.writeAttribute("type", "model");
            xml.writeAttribute("name", "VoxelBrew build assembly");
            xml.writeStartElement("mesh");
            xml.writeStartElement("vertices");
            for (var volume : volumes)
                for (int v = 0; v < volume.mesh().vertexCount(); v++) {
                    xml.writeEmptyElement("vertex");
                    for (int axis = 0; axis < 3; axis++)
                        xml.writeAttribute(
                                "xyz".substring(axis, axis + 1),
                                Double.toString(volume.mesh().coordinate(v, axis)));
                }
            xml.writeEndElement();
            xml.writeStartElement("triangles");
            int base = 0;
            for (var volume : volumes) {
                for (int t = 0; t < volume.mesh().triangleCount(); t++) {
                    xml.writeEmptyElement("triangle");
                    for (int c = 0; c < 3; c++)
                        xml.writeAttribute(
                                "v" + (c + 1),
                                Integer.toString(base + volume.mesh().vertexIndex(t, c)));
                }
                base += volume.mesh().vertexCount();
            }
            xml.writeEndElement();
            xml.writeEndElement();
            xml.writeEndElement();
            xml.writeEndElement();
            xml.writeStartElement("build");
            xml.writeEmptyElement("item");
            xml.writeAttribute("objectid", "1");
            xml.writeEndElement();
            xml.writeEndElement();
            xml.writeEndDocument();
            xml.flush();
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("Metadata/Slic3r_PE_model.config"));
            xml = XMLOutputFactory.newFactory().createXMLStreamWriter(zip, "UTF-8");
            xml.writeStartDocument("UTF-8", "1.0");
            xml.writeStartElement("config");
            xml.writeStartElement("object");
            xml.writeAttribute("id", "1");
            xml.writeAttribute("instances_count", "1");
            metadata(xml, "object", "name", "VoxelBrew build assembly");
            int first = 0;
            for (var volume : volumes) {
                xml.writeStartElement("volume");
                xml.writeAttribute("firstid", Integer.toString(first));
                first += volume.mesh().triangleCount();
                xml.writeAttribute("lastid", Integer.toString(first - 1));
                metadata(xml, "volume", "name", volume.name());
                metadata(xml, "volume", "volume_type", volume.type());
                xml.writeEndElement();
            }
            xml.writeEndElement();
            xml.writeEndElement();
            xml.writeEndDocument();
            xml.flush();
            zip.closeEntry();
            var config = new StringBuilder("; generated by PrusaSlicer 2.7.2-compatible VoxelBrew exporter\n\n");
            for (var setting : PrusaConfiguration.settings(model).entrySet())
                config.append("; ")
                        .append(setting.getKey())
                        .append(" = ")
                        .append(setting.getValue())
                        .append('\n');
            text(zip, "Metadata/Slic3r_PE.config", config.toString());
        } catch (XMLStreamException failure) {
            throw new IOException("Prusa project write failed", failure);
        }
    }

    private static void metadata(XMLStreamWriter xml, String type, String key, String value) throws XMLStreamException {
        xml.writeEmptyElement("metadata");
        xml.writeAttribute("type", type);
        xml.writeAttribute("key", key);
        xml.writeAttribute("value", value);
    }

    private static void text(ZipOutputStream zip, String path, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
