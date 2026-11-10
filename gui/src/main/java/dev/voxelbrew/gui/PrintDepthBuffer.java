package dev.voxelbrew.gui;

import java.util.Arrays;

/** Orthographic placement raster with shared surface visibility for faces, edges and picking. */
final class PrintDepthBuffer {
    record Point(double x, double y, double depth) {}

    private final int width, height;
    private final int[] pixels, owners, surfaces, edgeColors;
    private final double[] depths, tolerances, edgeDepths;
    private final double[] edgeCoverage;

    PrintDepthBuffer(int width, int height) {
        this.width = width;
        this.height = height;
        pixels = new int[Math.multiplyExact(width, height)];
        owners = new int[pixels.length];
        surfaces = new int[pixels.length];
        edgeColors = new int[pixels.length];
        depths = new double[pixels.length];
        tolerances = new double[pixels.length];
        edgeDepths = new double[pixels.length];
        edgeCoverage = new double[pixels.length];
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
                surfaces[index] = color;
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
                // One-pixel antialiased stroke, rather than a solid ~1.5-pixel band.
                double coverage = Math.max(0, 1 - Math.hypot(ex, ey));
                if (coverage == 0) continue;
                int index = y * width + x;
                double depth = a.depth + t * (b.depth - a.depth);
                if (depth <= depths[index] + tolerances[index]
                        && (depth < edgeDepths[index] - 1e-6
                                || Math.abs(depth - edgeDepths[index]) <= 1e-6
                                        && (color < edgeColors[index]
                                                || color == edgeColors[index] && coverage > edgeCoverage[index]))) {
                    edgeDepths[index] = depth;
                    edgeColors[index] = color;
                    edgeCoverage[index] = coverage;
                    pixels[index] = blend(color, surfaces[index], coverage);
                }
            }
        }
    }

    private static int blend(int foreground, int background, double coverage) {
        double foregroundAlpha = (foreground >>> 24) / 255.0 * coverage;
        double backgroundAlpha = (background >>> 24) / 255.0 * (1 - foregroundAlpha);
        double alpha = foregroundAlpha + backgroundAlpha;
        if (alpha == 0) return 0;
        int result = (int) Math.round(alpha * 255) << 24;
        for (int shift : new int[] {16, 8, 0}) {
            int channel = (int) Math.round((((foreground >>> shift) & 255) * foregroundAlpha
                            + ((background >>> shift) & 255) * backgroundAlpha)
                    / alpha);
            result |= channel << shift;
        }
        return result;
    }

    private static double cross(Point a, Point b, double x, double y) {
        return (b.x - a.x) * (y - a.y) - (b.y - a.y) * (x - a.x);
    }
}
