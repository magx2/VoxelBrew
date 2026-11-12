package dev.voxelbrew.gui;

/** Click recognition stays cancelled after crossing the drag threshold. */
final class MiddleGesture {
    private double startX, startY, lastX, lastY;
    private boolean pressed, dragged;

    void press(double x, double y) {
        startX = lastX = x;
        startY = lastY = y;
        pressed = true;
        dragged = false;
    }

    double[] drag(double x, double y) {
        if (!pressed) return null;
        if (!dragged && Math.hypot(x - startX, y - startY) <= 5) return null;
        dragged = true;
        var delta = new double[] {x - lastX, y - lastY};
        lastX = x;
        lastY = y;
        return delta;
    }

    boolean release(double x, double y) {
        boolean click = pressed && !dragged && Math.hypot(x - startX, y - startY) <= 5;
        cancel();
        return click;
    }

    void cancel() {
        pressed = false;
        dragged = false;
    }
}
