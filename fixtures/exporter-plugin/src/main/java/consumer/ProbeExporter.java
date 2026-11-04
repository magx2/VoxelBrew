package consumer;

import dev.voxelbrew.engine.export.*;
import dev.voxelbrew.engine.mesh.MeshOptions;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** Independent consumer: no GUI, native handle, protobuf, or CLI dependencies. */
public class ProbeExporter implements ModelExporter {
    public ExporterDescriptor descriptor() {
        return new ExporterDescriptor(
                "probe",
                "External mesh report",
                API_VERSION,
                List.of("txt"),
                true,
                List.of(
                        new ExportOption(
                                "label",
                                "Report label",
                                ExportOption.Type.STRING,
                                "default",
                                List.of())));
    }

    public void write(
            ModelView model, MeshOptions mesh, Map<String, String> options, OutputStream output)
            throws IOException {
        int triangles = 0;
        for (var body : model.bodies())
            triangles += model.tessellate(body.id(), mesh).triangleCount();
        output.write(
                (options.get("label")
                                + " bodies="
                                + model.bodies().size()
                                + " triangles="
                                + triangles
                                + "\n")
                        .getBytes(StandardCharsets.UTF_8));
    }

    public static final class Duplicate extends ProbeExporter {}

    public static final class Incompatible extends ProbeExporter {
        @Override
        public ExporterDescriptor descriptor() {
            return new ExporterDescriptor(
                    "future", "Future API", 999, List.of("txt"), true, List.of());
        }
    }

    public static final class Failing extends ProbeExporter {
        @Override
        public ExporterDescriptor descriptor() {
            return new ExporterDescriptor(
                    "failing", "Failed writer", API_VERSION, List.of("txt"), true, List.of());
        }

        @Override
        public void write(
                ModelView model, MeshOptions mesh, Map<String, String> options, OutputStream output)
                throws IOException {
            output.write("partial".getBytes(StandardCharsets.UTF_8));
            throw new IOException("Deliberate provider failure");
        }
    }
}
