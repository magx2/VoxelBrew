package dev.voxelbrew.exporter;

import dev.voxelbrew.engine.mesh.MeshOptions;
import dev.voxelbrew.engine.mesh.MeshValidation;
import dev.voxelbrew.engine.model.Transform;
import dev.voxelbrew.exporter.api.ExporterDescriptor;
import dev.voxelbrew.exporter.api.ModelExporter;
import dev.voxelbrew.exporter.api.ModelView;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;

public final class ThreeMfExporter implements ModelExporter {
    @Override
    public ExporterDescriptor descriptor() {
        return new ExporterDescriptor("3mf", "Portable 3MF Core", API_VERSION, List.of("3mf"), true, List.of());
    }

    @Override
    public void write(ModelView model, MeshOptions meshOptions, Map<String, String> options, OutputStream output)
            throws IOException {
        try (var zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            text(
                    zip,
                    "[Content_Types].xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"model\" ContentType=\"application/vnd.ms-package.3dmanufacturing-3dmodel+xml\"/></Types>");
            text(
                    zip,
                    "_rels/.rels",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Target=\"/3D/3dmodel.model\" Id=\"rel0\" Type=\"http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel\"/></Relationships>");
            zip.putNextEntry(new ZipEntry("3D/3dmodel.model"));
            var xml = XMLOutputFactory.newFactory().createXMLStreamWriter(zip, "UTF-8");
            xml.writeStartDocument("UTF-8", "1.0");
            xml.writeStartElement("model");
            xml.writeDefaultNamespace("http://schemas.microsoft.com/3dmanufacturing/core/2015/02");
            xml.writeAttribute("unit", "millimeter");
            xml.writeAttribute("xml", "http://www.w3.org/XML/1998/namespace", "lang", "en-US");
            xml.writeStartElement("resources");
            Map<String, Integer> ids = new LinkedHashMap<>();
            for (var body : model.bodies()) {
                var mesh = MeshValidation.weld(model.tessellate(body.id(), meshOptions));
                int id = ids.size() + 1;
                ids.put(body.id(), id);
                xml.writeStartElement("object");
                xml.writeAttribute("id", Integer.toString(id));
                xml.writeAttribute("type", body.role() == ModelView.Role.SOLID_SUPPORT ? "solidsupport" : "model");
                xml.writeAttribute("name", body.name());
                xml.writeStartElement("mesh");
                xml.writeStartElement("vertices");
                for (int v = 0; v < mesh.vertexCount(); v++) {
                    xml.writeEmptyElement("vertex");
                    xml.writeAttribute("x", Double.toString(mesh.coordinate(v, 0)));
                    xml.writeAttribute("y", Double.toString(mesh.coordinate(v, 1)));
                    xml.writeAttribute("z", Double.toString(mesh.coordinate(v, 2)));
                }
                xml.writeEndElement();
                xml.writeStartElement("triangles");
                for (int t = 0; t < mesh.triangleCount(); t++) {
                    xml.writeEmptyElement("triangle");
                    for (int c = 0; c < 3; c++)
                        xml.writeAttribute("v" + (c + 1), Integer.toString(mesh.vertexIndex(t, c)));
                }
                xml.writeEndElement();
                xml.writeEndElement();
                xml.writeEndElement();
            }
            xml.writeEndElement();
            xml.writeStartElement("build");
            for (var instance : model.instances()) {
                xml.writeEmptyElement("item");
                xml.writeAttribute("objectid", Integer.toString(ids.get(instance.bodyId())));
                xml.writeAttribute("transform", transformValues(instance.transform()));
            }
            xml.writeEndElement();
            xml.writeEndElement();
            xml.writeEndDocument();
            xml.flush();
            zip.closeEntry();
        } catch (XMLStreamException failure) {
            throw new IOException("3MF XML write failed", failure);
        }
    }

    private static String transformValues(Transform transform) {
        var matrix = transform.matrix();
        var values = new StringJoiner(" ");
        for (int index : new int[] {0, 4, 8, 1, 5, 9, 2, 6, 10, 3, 7, 11}) values.add(Double.toString(matrix[index]));
        return values.toString();
    }

    private static void text(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
