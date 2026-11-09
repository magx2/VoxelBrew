package dev.voxelbrew.gui;

import javafx.scene.Group;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;

final class OperationIcons {
    private OperationIcons() {}

    static Group icon(String command) {
        var shape = new SVGPath();
        shape.setContent(
                switch (command) {
                    case "extrude" ->
                        "M2 11L10 7L18 11V16L10 20L2 16Z M2 11L10 15L18 11 M10 15V20 M10 10V1 M6 5L10 1L14 5";
                    case "cut" -> "M2 8L10 4L18 8V16L10 20L2 16Z M2 8L10 12L18 8 M10 12V20 M10 1V10 M6 6L10 10L14 6";
                    case "fillet" -> "M2 19V9Q2 2 9 2H19 M6 19V10Q6 6 10 6H19 M2 19H6 M19 2V6";
                    case "chamfer" -> "M2 19V9L9 2H19 M6 19V11L11 6H19 M2 19H6 M19 2V6";
                    case "shell" -> "M2 6L10 2L18 6V15L10 19L2 15Z M2 6L10 10L18 6 M10 10V19 M6 6L10 4L14 6L10 8Z";
                    case "split-body" -> "M1 6L7 3L7 17L1 14Z M13 3L19 6V14L13 17Z M10 1V5 M10 8V12 M10 15V19";
                    default -> throw new IllegalArgumentException("Unknown operation: " + command);
                });
        shape.setFill(Color.TRANSPARENT);
        shape.setStroke(Color.web("#658dcc"));
        shape.setStrokeWidth(1.5);
        var group = new Group(shape);
        group.setMouseTransparent(true);
        return group;
    }
}
