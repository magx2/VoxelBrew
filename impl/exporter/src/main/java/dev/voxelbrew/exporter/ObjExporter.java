package dev.voxelbrew.exporter;

import dev.voxelbrew.engine.mesh.MeshOptions;
import dev.voxelbrew.engine.mesh.MeshValidation;
import dev.voxelbrew.exporter.api.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** Wavefront OBJ: triangulated objects in millimeters, with instance transforms baked once. */
public final class ObjExporter extends MeshExporter {
    public ObjExporter() {
        super();
    }

    public ObjExporter(java.util.concurrent.Executor worker, java.nio.file.Path plugins) {
        super(worker, plugins);
    }

    @Override
    public MeshFormat format() {
        return new MeshFormat("obj", "Wavefront OBJ (millimeters)", List.of("obj"), true, List.of());
    }

    @Override
    public void write(ModelView model, MeshOptions meshOptions, Map<String, String> options, OutputStream output)
            throws IOException {
        var writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
        writer.write("# VoxelBrew OBJ; units=mm\n");
        long offset = 1;
        int object = 0;
        for (var instance : model.instances()) {
            var mesh = model.tessellate(instance.bodyId(), meshOptions);
            MeshValidation.requireClosed(mesh);
            writer.write("o object_" + ++object + "_" + instance.id().replaceAll("[^a-zA-Z0-9_.-]", "_") + "\n");
            for (int vertex = 0; vertex < mesh.vertexCount(); vertex++) {
                var p = instance.transform()
                        .apply(mesh.coordinate(vertex, 0), mesh.coordinate(vertex, 1), mesh.coordinate(vertex, 2));
                if (!Double.isFinite(p[0]) || !Double.isFinite(p[1]) || !Double.isFinite(p[2]))
                    throw new IllegalArgumentException("Nonfinite transformed vertex");
                writer.write("v " + p[0] + " " + p[1] + " " + p[2] + "\n");
            }
            for (int triangle = 0; triangle < mesh.triangleCount(); triangle++) {
                writer.write(
                        "f " + (offset + mesh.vertexIndex(triangle, 0)) + " " + (offset + mesh.vertexIndex(triangle, 1))
                                + " " + (offset + mesh.vertexIndex(triangle, 2)) + "\n");
            }
            offset += mesh.vertexCount();
        }
        writer.flush();
    }
}
