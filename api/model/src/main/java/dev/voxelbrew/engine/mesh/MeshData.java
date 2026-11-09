package dev.voxelbrew.engine.mesh;

/** Immutable detached mesh. Coordinates are double-precision design-space millimeters. */
public final class MeshData {
    private final double[] vertices;
    private final int[] triangles;
    private final int[] faces;

    public MeshData(double[] vertices, int[] triangles, int[] faces) {
        this.vertices = vertices.clone();
        this.triangles = triangles.clone();
        this.faces = faces.clone();
        if (vertices.length == 0
                || vertices.length % 3 != 0
                || triangles.length == 0
                || triangles.length % 3 != 0
                || faces.length != triangles.length / 3) {
            throw new IllegalArgumentException("Incomplete mesh arrays");
        }
        for (double value : this.vertices)
            if (!Double.isFinite(value)) throw new IllegalArgumentException("Nonfinite mesh coordinate");
        for (int value : this.triangles)
            if (value < 0 || value >= vertexCount()) throw new IllegalArgumentException("Invalid mesh index");
        for (int value : this.faces) if (value < 0) throw new IllegalArgumentException("Invalid face index");
    }

    public int vertexCount() {
        return vertices.length / 3;
    }

    public int triangleCount() {
        return faces.length;
    }

    public double coordinate(int vertex, int axis) {
        return vertices[3 * vertex + axis];
    }

    public int vertexIndex(int triangle, int corner) {
        return triangles[3 * triangle + corner];
    }

    public int faceIndex(int triangle) {
        return faces[triangle];
    }

    public double[] vertices() {
        return vertices.clone();
    }

    public int[] triangles() {
        return triangles.clone();
    }

    public int[] faceIndices() {
        return faces.clone();
    }
}
