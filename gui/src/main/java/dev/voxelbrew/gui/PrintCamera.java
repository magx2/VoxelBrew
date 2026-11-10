package dev.voxelbrew.gui;

import javafx.geometry.Point2D;
import javafx.geometry.Point3D;

/** Transient plate camera, using the Designer's orbit convention and wheel sensitivity. */
final class PrintCamera {
    private final boolean spatial;
    private ViewOrientation orientation;
    private double panX, panY, zoom = 1;
    private double xx, xy, yx, yy, yz;

    PrintCamera(boolean spatial) {
        this.spatial = spatial;
        orientation = spatial ? ViewOrientation.HOME : ViewOrientation.named("Top");
        updateProjection();
    }

    double panX() {
        return panX;
    }

    double panY() {
        return panY;
    }

    double zoom() {
        return zoom;
    }

    ViewOrientation orientation() {
        return orientation;
    }

    void pan(double dx, double dy) {
        panX += dx;
        panY += dy;
    }

    void orbit(double dx, double dy) {
        if (spatial) {
            orientation = orientation.dragged(dx, dy);
            updateProjection();
        } else pan(dx, dy);
    }

    void scroll(double delta) {
        zoom = Math.clamp(zoom * Math.exp(delta * 0.0015), 0.05, 100);
    }

    void fit() {
        panX = 0;
        panY = 0;
        zoom = 1;
    }

    double x(double x, double y) {
        return xx * x + xy * y;
    }

    double y(double x, double y, double z) {
        return yx * x + yy * y + yz * z;
    }

    private void updateProjection() {
        var x = orientation.project(new Point3D(1, 0, 0));
        var y = orientation.project(new Point3D(0, 1, 0));
        xx = x.getX();
        xy = y.getX();
        yx = -x.getY();
        yy = -y.getY();
        yz = -orientation.project(new Point3D(0, 0, 1)).getY();
    }

    /** Invert projected movement on the bed; an edge-on bed has no unique inverse. */
    Point2D bedDelta(double dx, double dy) {
        double a = x(1, 0), b = x(0, 1), c = y(1, 0, 0), d = y(0, 1, 0);
        double determinant = a * d - b * c;
        if (Math.abs(determinant) < 0.02) return null;
        return new Point2D((dx * d - b * dy) / determinant, (a * dy - c * dx) / determinant);
    }
}
