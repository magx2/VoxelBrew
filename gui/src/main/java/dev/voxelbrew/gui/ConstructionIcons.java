package dev.voxelbrew.gui;

import dev.voxelbrew.engine.construction.ConstructionTools.Tool;
import javafx.scene.Group;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;

final class ConstructionIcons {
    private ConstructionIcons() {}

    static Group icon(Tool tool) {
        var shape = new SVGPath();
        String name = tool.name();
        shape.setContent(
                tool == Tool.COORDINATE_SYSTEM
                        ? "M3 2V17H18 M3 17L12 8"
                        : name.startsWith("AXIS")
                                ? "M2 18L18 2 M3 7L12 16 M8 2L17 11"
                                : name.startsWith("POINT")
                                        ? "M10 1V19 M1 10H19 M7 7H13V13H7Z"
                                        : "M2 8L12 2L18 12L8 18Z M5 4L15 16");
        shape.setFill(Color.TRANSPARENT);
        shape.setStroke(Color.web(name.startsWith("AXIS") ? "#df9050" : "#58a77b"));
        shape.setStrokeWidth(1.5);
        var group = new Group(shape);
        group.setMouseTransparent(true);
        return group;
    }
}
