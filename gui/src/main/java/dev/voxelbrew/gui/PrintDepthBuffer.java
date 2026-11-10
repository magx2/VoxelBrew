package dev.voxelbrew.gui;

import java.util.Arrays;

/** Orthographic placement raster with shared surface visibility for faces, edges and picking. */
final class PrintDepthBuffer {
    record Point(double x, double y, double depth) {}

    private final int width, height;
    private final int[] pixels, owners;
    private final double[] depths, tolerances, edgeDepths;

    PrintDepthBuffer(int width, int height) {
        this.width = width;
        this.height = height;
        pixels = new int[Math.multiplyExact(width, height)];
        owners = new int[pixels.length];
        depths = new double[pixels.length];
        tolerances = new double[pixels.length];
        edgeDepths = new double[pixels.length];
        Arrays.fill(owners, -1);
        Arrays.fill(depths, Double.POSITIVE_INFINITY);
        Arrays.fill(edgeDepths, Double.POSITIVE_INFINITY);
    }

    int[] pixels() {
        return pixels.clone();
    }

    int owner(double x, double y) {
        return x < 0 || y < 0 || x >= width || y >= height ? -1 : owners[(int) y * width + (int) x];
    }

    void triangle(Point a, Point b, Point c, int color, int owner) {
        double area = cross(a, b, c.x, c.y);
        if (Math.abs(area) < 1e-9) return;
        int minX = (int) Math.max(0, Math.floor(Math.min(a.x, Math.min(b.x, c.x))));
        int maxX = (int) Math.min(width - 1, Math.ceil(Math.max(a.x, Math.max(b.x, c.x))));
        int minY = (int) Math.max(0, Math.floor(Math.min(a.y, Math.min(b.y, c.y))));
        int maxY = (int) Math.min(height - 1, Math.ceil(Math.max(a.y, Math.max(b.y, c.y))));
        double dx = ((b.depth - a.depth) * (c.y - a.y) - (c.depth - a.depth) * (b.y - a.y)) / area;
        double dy = ((b.x - a.x) * (c.depth - a.depth) - (c.x - a.x) * (b.depth - a.depth)) / area;
        // Lines sample pixel centers too: allow half a pixel of the covering face's depth slope.
        double tolerance = (Math.abs(dx) + Math.abs(dy)) * 0.6 + 1e-6;
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                double u = cross(b, c, x + 0.5, y + 0.5) / area;
                double v = cross(c, a, x + 0.5, y + 0.5) / area;
                if (u < -1e-9 || v < -1e-9 || u + v > 1 + 1e-9) continue;
                double depth = a.depth + dx * (x + 0.5 - a.x) + dy * (y + 0.5 - a.y);
                int index = y * width + x;
                if (depth >= depths[index]) continue;
                depths[index] = depth;
                tolerances[index] = tolerance;
                pixels[index] = color;
                owners[index] = owner;
            }
        }
    }

    void edge(Point a, Point b, int color) {
        double dx = b.x - a.x, dy = b.y - a.y;
        double lengthSquared = dx * dx + dy * dy;
        if (lengthSquared < 1e-12) return;
        int minX = (int) Math.max(0, Math.floor(Math.min(a.x, b.x) - 1));
        int maxX = (int) Math.min(width - 1, Math.ceil(Math.max(a.x, b.x) + 1));
        int minY = (int) Math.max(0, Math.floor(Math.min(a.y, b.y) - 1));
        int maxY = (int) Math.min(height - 1, Math.ceil(Math.max(a.y, b.y) + 1));
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                double t = Math.clamp(((x + 0.5 - a.x) * dx + (y + 0.5 - a.y) * dy) / lengthSquared, 0, 1);
                double ex = x + 0.5 - a.x - t * dx, ey = y + 0.5 - a.y - t * dy;
                if (ex * ex + ey * ey > 0.55) continue;
                int index = y * width + x;
                double depth = a.depth + t * (b.depth - a.depth);
                if (depth <= depths[index] + tolerances[index]
                        && (depth < edgeDepths[index] - 1e-6
                                || Math.abs(depth - edgeDepths[index]) <= 1e-6 && color < pixels[index])) {
                    edgeDepths[index] = depth;
                    pixels[index] = color;
                }
            }
        }
    }

    private static double cross(Point a, Point b, double x, double y) {
        return (b.x - a.x) * (y - a.y) - (b.y - a.y) * (x - a.x);
    }
}
