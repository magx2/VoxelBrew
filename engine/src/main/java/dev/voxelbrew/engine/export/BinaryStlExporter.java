package dev.voxelbrew.engine.export;

import dev.voxelbrew.engine.mesh.MeshOptions;
import dev.voxelbrew.engine.mesh.MeshValidation;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public final class BinaryStlExporter implements ModelExporter {
    @Override
    public ExporterDescriptor descriptor() {
        return new ExporterDescriptor(
                "stl", "Binary STL (millimeters)", API_VERSION, List.of("stl"), false, List.of());
    }

    @Override
    public void write(
            ModelView model,
            MeshOptions meshOptions,
            Map<String, String> options,
            OutputStream output)
            throws IOException {
        if (model.instances().size() != 1)
            throw new IllegalArgumentException("STL requires exactly one instance");
        var instance = model.instances().getFirst();
        var mesh = model.tessellate(instance.bodyId(), meshOptions);
        MeshValidation.requireClosed(mesh);
        byte[] header = new byte[80];
        byte[] text = "VoxelBrew binary STL; units=mm".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(text, 0, header, 0, text.length);
        output.write(header);
        output.write(
                ByteBuffer.allocate(4)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(mesh.triangleCount())
                        .array());
        var facet = ByteBuffer.allocate(50).order(ByteOrder.LITTLE_ENDIAN);
        for (int t = 0; t < mesh.triangleCount(); t++) {
            double[][] p = new double[3][];
            for (int c = 0; c < 3; c++) {
                int v = mesh.vertexIndex(t, c);
                p[c] =
                        instance.transform()
                                .apply(
                                        mesh.coordinate(v, 0),
                                        mesh.coordinate(v, 1),
                                        mesh.coordinate(v, 2));
            }
            double ux = p[1][0] - p[0][0], uy = p[1][1] - p[0][1], uz = p[1][2] - p[0][2];
            double vx = p[2][0] - p[0][0], vy = p[2][1] - p[0][1], vz = p[2][2] - p[0][2];
            double[] n = {uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx};
            double length = Math.hypot(Math.hypot(n[0], n[1]), n[2]);
            facet.clear();
            for (double value : n) facet.putFloat((float) (value / length));
            for (double[] point : p)
                for (double value : point) {
                    float coordinate = (float) value;
                    if (!Float.isFinite(coordinate))
                        throw new IOException("STL coordinate exceeds float range");
                    facet.putFloat(coordinate);
                }
            facet.putShort((short) 0);
            output.write(facet.array());
        }
    }
}
