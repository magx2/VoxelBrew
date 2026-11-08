package dev.voxelbrew.gui;

import dev.voxelbrew.engine.construction.ConstructionGeometry.*;
import java.util.Map;
import java.util.function.Consumer;
import javafx.geometry.Point3D;
import javafx.scene.Group;
import javafx.scene.input.MouseButton;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.*;
import javafx.scene.transform.Rotate;

final class ConstructionView extends Group {
    void show(Map<String, Datum> values, Consumer<String> selected) {
        getChildren().clear();
        values.forEach((id, value) -> {
            var node = node(value, Color.web("#e6a347"));
            node.setId("construction-" + id);
            node.setOnMouseClicked(event -> {
                if (event.getButton() == MouseButton.PRIMARY) {
                    selected.accept(id);
                    event.consume();
                }
            });
            getChildren().add(node);
        });
    }

    void preview(Datum datum) {
        getChildren().clear();
        setMouseTransparent(true);
        if (datum != null) getChildren().add(node(datum, Color.CYAN));
    }

    private static Point3D view(Vector vector) {
        return Viewport.toView(vector.x(), vector.y(), vector.z());
    }

    private static Group node(Datum datum, Color color) {
        var group = new Group();
        if (datum instanceof Point point) {
            var sphere = new Sphere(.65);
            sphere.setMaterial(new PhongMaterial(color));
            var v = view(point.position());
            sphere.setTranslateX(v.getX());
            sphere.setTranslateY(v.getY());
            sphere.setTranslateZ(v.getZ());
            group.getChildren().add(sphere);
        } else if (datum instanceof Axis axis) {
            group.getChildren()
                    .add(line(
                            view(axis.origin().minus(axis.direction().times(40))),
                            view(axis.origin().plus(axis.direction().times(40))),
                            color));
        } else if (datum instanceof Plane plane) {
            var mesh = new TriangleMesh();
            mesh.getPoints().setAll(-20, 0, -20, 20, 0, -20, 20, 0, 20, -20, 0, 20);
            mesh.getTexCoords().setAll(0, 0);
            mesh.getFaces().setAll(0, 0, 1, 0, 2, 0, 0, 0, 2, 0, 3, 0);
            var face = new MeshView(mesh);
            face.setCullFace(CullFace.NONE);
            face.setMaterial(new PhongMaterial(color.deriveColor(0, 1, 1, .25)));
            group.getChildren().add(face);
            Point3D[] corners = {
                new Point3D(-20, 0, -20), new Point3D(20, 0, -20), new Point3D(20, 0, 20), new Point3D(-20, 0, 20)
            };
            for (int i = 0; i < 4; i++) group.getChildren().add(line(corners[i], corners[(i + 1) % 4], color));
            group.getTransforms().add(Viewport.viewTransform(plane.frame()));
        } else if (datum instanceof CoordinateSystem frame) {
            group.getChildren()
                    .addAll(
                            line(Point3D.ZERO, Viewport.toView(15, 0, 0), Color.RED),
                            line(Point3D.ZERO, Viewport.toView(0, 15, 0), Color.GREEN),
                            line(Point3D.ZERO, Viewport.toView(0, 0, 15), Color.BLUE));
            group.getTransforms().add(Viewport.viewTransform(frame.frame()));
        }
        return group;
    }

    private static Cylinder line(Point3D a, Point3D b, Color color) {
        var direction = b.subtract(a);
        var cylinder = new Cylinder(.15, direction.magnitude(), 8);
        var middle = a.midpoint(b);
        cylinder.setTranslateX(middle.getX());
        cylinder.setTranslateY(middle.getY());
        cylinder.setTranslateZ(middle.getZ());
        var y = new Point3D(0, 1, 0);
        var rotationAxis = y.crossProduct(direction);
        if (rotationAxis.magnitude() > 1e-9) cylinder.getTransforms().add(new Rotate(y.angle(direction), rotationAxis));
        cylinder.setMaterial(new PhongMaterial(color));
        return cylinder;
    }
}
