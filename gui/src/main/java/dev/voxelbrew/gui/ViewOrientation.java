package dev.voxelbrew.gui;

import javafx.geometry.Point3D;

/** Camera rotation in the viewport's Z-up CAD basis, independent of document state. */
record ViewOrientation(double tilt, double orbit) {
    static final ViewOrientation HOME = new ViewOrientation(28, -35);

    ViewOrientation {
        if (!Double.isFinite(tilt) || !Double.isFinite(orbit) || Math.abs(tilt) > 90)
            throw new IllegalArgumentException(
                    "Camera angles must be finite, with tilt in [-90, 90]");
        orbit = ((orbit % 360) + 540) % 360 - 180;
    }

    static ViewOrientation lookingFrom(int x, int y, int z) {
        var direction = new Point3D(x, y, z);
        if (direction.magnitude() == 0)
            throw new IllegalArgumentException("Missing view direction");
        return new ViewOrientation(
                Math.toDegrees(Math.asin(z / direction.magnitude())),
                x == 0 && y == 0 ? 0 : Math.toDegrees(Math.atan2(x, -y)));
    }

    static ViewOrientation named(String name) {
        return switch (name) {
            case "Top" -> lookingFrom(0, 0, 1);
            case "Bottom" -> lookingFrom(0, 0, -1);
            case "Front" -> lookingFrom(0, -1, 0);
            case "Back" -> lookingFrom(0, 1, 0);
            case "Right" -> lookingFrom(1, 0, 0);
            case "Left" -> lookingFrom(-1, 0, 0);
            default -> HOME;
        };
    }

    String name() {
        for (String name :
                new String[] {"Isometric", "Top", "Front", "Right", "Back", "Left", "Bottom"})
            if (equals(named(name))) return name;
        return "Custom";
    }

    ViewOrientation dragged(double dx, double dy) {
        return new ViewOrientation(Math.clamp(tilt - dy * 0.5, -89, 89), orbit + dx * 0.5);
    }

    /** Same Ry then Rx transform as the main viewport, including CAD (x,y,z) to FX (x,-z,y). */
    Point3D project(Point3D cad) {
        double yaw = Math.toRadians(orbit), pitch = Math.toRadians(tilt);
        double x = cad.getX() * Math.cos(yaw) + cad.getY() * Math.sin(yaw);
        double z = -cad.getX() * Math.sin(yaw) + cad.getY() * Math.cos(yaw);
        return new Point3D(
                x,
                -cad.getZ() * Math.cos(pitch) - z * Math.sin(pitch),
                -cad.getZ() * Math.sin(pitch) + z * Math.cos(pitch));
    }
}
