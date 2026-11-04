package dev.voxelbrew.gui;

import dev.voxelbrew.engine.mesh.MeshData;
import dev.voxelbrew.engine.model.Feature;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import javafx.geometry.Point3D;
import javafx.scene.*;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.*;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Translate;

/** Z-up CAD coordinates map to FX (x,-z,y); camera orbit never changes model coordinates. */
public final class Viewport extends StackPane {
    private final Group bodies = new Group(),
            sketches = new Group(),
            aids = new Group(),
            world = new Group();
    private final Rotate tilt = new Rotate(28, Rotate.X_AXIS),
            orbit = new Rotate(-35, Rotate.Y_AXIS);
    private final Translate center = new Translate(), pan = new Translate();
    private final PerspectiveCamera camera = new PerspectiveCamera(true);
    private final Map<String, MeshView> bodyViews = new LinkedHashMap<>();
    private final SubScene scene;
    private double lastX, lastY;
    private double distance = 180;
    private BiConsumer<String, Integer> selection = (id, face) -> {};
    private String selected;
    private Consumer<Point3D> planeClick;
    private double planeZ;
    private final Group handle = new Group();
    private double handleDistance, handlePlane;

    public Viewport() {
        setId("viewport");
        setMinSize(300, 250);
        world.getChildren().addAll(aids, bodies, sketches, handle);
        world.getTransforms().addAll(pan, tilt, orbit, center);
        var light = new AmbientLight(Color.gray(0.45));
        var key = new PointLight(Color.gray(0.7));
        key.setTranslateX(-100);
        key.setTranslateY(-150);
        key.setTranslateZ(-150);
        scene =
                new SubScene(
                        new Group(world, light, key), 800, 600, true, SceneAntialiasing.BALANCED);
        scene.setCamera(camera);
        camera.setNearClip(0.01);
        camera.setFarClip(100000);
        camera.setTranslateZ(-distance);
        camera.setFieldOfView(35);
        scene.widthProperty().bind(widthProperty());
        scene.heightProperty().bind(heightProperty());
        getChildren().add(scene);
        setDark(false);
        grid();
        scene.setOnMousePressed(
                e -> {
                    lastX = e.getSceneX();
                    lastY = e.getSceneY();
                    requestFocus();
                });
        scene.setOnMouseDragged(
                e -> {
                    double dx = e.getSceneX() - lastX, dy = e.getSceneY() - lastY;
                    lastX = e.getSceneX();
                    lastY = e.getSceneY();
                    if (e.isMiddleButtonDown()) {
                        if (e.isShiftDown()) {
                            pan.setX(pan.getX() + dx * distance / 700);
                            pan.setY(pan.getY() + dy * distance / 700);
                        } else {
                            orbit.setAngle(orbit.getAngle() + dx * 0.5);
                            tilt.setAngle(Math.clamp(tilt.getAngle() - dy * 0.5, -89, 89));
                        }
                    }
                });
        scene.setOnScroll(
                e -> {
                    distance = Math.clamp(distance * Math.exp(-e.getDeltaY() * 0.0015), 2, 100000);
                    camera.setTranslateZ(-distance);
                    e.consume();
                });
        scene.addEventFilter(
                MouseEvent.MOUSE_CLICKED,
                e -> {
                    if (planeClick != null
                            && e.getButton() == MouseButton.PRIMARY
                            && e.isStillSincePress()) {
                        var local = scene.screenToLocal(e.getScreenX(), e.getScreenY());
                        var point = pointOnPlane(local.getX(), local.getY(), planeZ);
                        if (point != null) planeClick.accept(point);
                        e.consume();
                    }
                });
    }

    public static Point3D toView(double x, double y, double z) {
        return new Point3D(x, -z, y);
    }

    public static Point3D toModel(Point3D p) {
        return new Point3D(p.getX(), p.getZ(), -p.getY());
    }

    public void onSelection(BiConsumer<String, Integer> listener) {
        selection = listener;
    }

    public void drawOnPlane(double z, Consumer<Point3D> listener) {
        planeZ = z;
        planeClick = listener;
    }

    public void stopDrawing() {
        planeClick = null;
    }

    javafx.geometry.Point2D modelToScreen(Point3D point) {
        return world.localToScreen(toView(point.getX(), point.getY(), point.getZ()));
    }

    javafx.geometry.Point2D handleScreenPosition() {
        return handle.getChildren().isEmpty()
                ? null
                : handle.getChildren().getLast().localToScreen(Point3D.ZERO);
    }

    public Point3D pointOnPlane(double x, double y, double z) {
        double scale =
                2 * Math.tan(Math.toRadians(camera.getFieldOfView() / 2)) / scene.getHeight();
        Point3D a = toModel(world.parentToLocal(new Point3D(0, 0, -distance)));
        Point3D b =
                toModel(
                        world.parentToLocal(
                                new Point3D(
                                        (x - scene.getWidth() / 2) * scale,
                                        (y - scene.getHeight() / 2) * scale,
                                        1 - distance)));
        Point3D direction = b.subtract(a);
        if (Math.abs(direction.getZ()) < 1e-10) return null;
        double t = (z - a.getZ()) / direction.getZ();
        return t > 0 ? a.add(direction.multiply(t)) : null;
    }

    public void showSketches(List<Feature> features, String selectedSketch) {
        sketches.getChildren().clear();
        sketches.setDepthTest(planeClick == null ? DepthTest.INHERIT : DepthTest.DISABLE);
        for (var feature : features)
            if (feature instanceof Feature.Sketch sketch) {
                double[] xy;
                if (sketch.profile() instanceof Feature.Rectangle r)
                    xy =
                            new double[] {
                                r.x(),
                                r.y(),
                                r.x() + r.width(),
                                r.y(),
                                r.x() + r.width(),
                                r.y() + r.height(),
                                r.x(),
                                r.y() + r.height()
                            };
                else {
                    var c = (Feature.Circle) sketch.profile();
                    xy = new double[128];
                    for (int n = 0; n < 64; n++) {
                        xy[2 * n] = c.centerX() + c.diameter() / 2 * Math.cos(2 * Math.PI * n / 64);
                        xy[2 * n + 1] =
                                c.centerY() + c.diameter() / 2 * Math.sin(2 * Math.PI * n / 64);
                    }
                }
                int count = xy.length / 2;
                float[] points = new float[count * 3];
                for (int i = 0; i < count; i++) {
                    var p = toView(xy[2 * i], xy[2 * i + 1], sketch.plane().offsetZ() + 0.035);
                    points[3 * i] = (float) p.getX();
                    points[3 * i + 1] = (float) p.getY();
                    points[3 * i + 2] = (float) p.getZ();
                }
                var mesh = new TriangleMesh();
                mesh.getPoints().setAll(points);
                mesh.getTexCoords().setAll(0, 0);
                int[] faces = new int[(count - 2) * 6];
                for (int i = 0; i < count - 2; i++) {
                    faces[6 * i] = 0;
                    faces[6 * i + 2] = i + 1;
                    faces[6 * i + 4] = i + 2;
                }
                mesh.getFaces().setAll(faces);
                var profile = new MeshView(mesh);
                profile.setCullFace(CullFace.NONE);
                boolean chosen = sketch.id().equals(selectedSketch);
                profile.setMaterial(
                        new PhongMaterial(
                                Color.web(chosen ? "#eead48" : "#388eae", chosen ? 0.52 : 0.18)));
                profile.setOnMouseClicked(
                        e -> {
                            if (e.getButton() == MouseButton.PRIMARY && e.isStillSincePress()) {
                                selection.accept(sketch.id(), -1);
                                e.consume();
                            }
                        });
                sketches.getChildren().add(profile);
                for (int i = 0; i < count; i++) {
                    var line =
                            line(
                                    toView(
                                            xy[2 * i],
                                            xy[2 * i + 1],
                                            sketch.plane().offsetZ() + 0.04),
                                    toView(
                                            xy[2 * ((i + 1) % count)],
                                            xy[2 * ((i + 1) % count) + 1],
                                            sketch.plane().offsetZ() + 0.04),
                                    chosen ? 0.14 : 0.065,
                                    chosen ? Color.web("#dc8d18") : Color.web("#2d839f"));
                    line.setMouseTransparent(true);
                    sketches.getChildren().add(line);
                }
            }
    }

    private static Cylinder line(Point3D a, Point3D b, double radius, Color color) {
        Point3D delta = b.subtract(a), middle = a.midpoint(b);
        var line = new Cylinder(radius, delta.magnitude(), 6);
        line.setMaterial(new PhongMaterial(color));
        line.setTranslateX(middle.getX());
        line.setTranslateY(middle.getY());
        line.setTranslateZ(middle.getZ());
        Point3D axis = Rotate.Y_AXIS.crossProduct(delta);
        double angle = Rotate.Y_AXIS.angle(delta);
        if (axis.magnitude() > 1e-9) line.getTransforms().add(new Rotate(angle, axis));
        return line;
    }

    public void clearHandle() {
        handle.getChildren().clear();
    }

    public void updateExtrusionDistance(double value) {
        handleDistance = value;
        if (handle.getChildren().isEmpty()) return;
        var shaft = (Cylinder) handle.getChildren().getFirst();
        var tip = handle.getChildren().getLast();
        double end = value + Math.copySign(5, value);
        tip.setTranslateY(-handlePlane - end);
        shaft.setHeight(Math.abs(end));
        shaft.setTranslateY(-handlePlane - end / 2);
    }

    public void extrusionHandle(
            Feature.Sketch sketch, double value, DoubleConsumer preview, Runnable accept) {
        if (!handle.getChildren().isEmpty()) return;
        double x, y;
        if (sketch.profile() instanceof Feature.Rectangle r) {
            x = r.x() + r.width() / 2;
            y = r.y() + r.height() / 2;
        } else {
            var c = (Feature.Circle) sketch.profile();
            x = c.centerX();
            y = c.centerY();
        }
        var anchor = toView(x, y, sketch.plane().offsetZ());
        var tip = new Sphere(1.5, 12);
        tip.setId("extrusion-handle");
        tip.setMaterial(new PhongMaterial(Color.web("#f3ad3d")));
        var shaft =
                line(
                        anchor,
                        toView(x, y, sketch.plane().offsetZ() + value + Math.copySign(5, value)),
                        0.35,
                        Color.web("#f3ad3d"));
        shaft.setMouseTransparent(true);
        tip.setTranslateX(anchor.getX());
        tip.setTranslateY(-sketch.plane().offsetZ() - value - Math.copySign(5, value));
        tip.setTranslateZ(anchor.getZ());
        final double[] start = new double[2];
        tip.setOnMousePressed(
                e -> {
                    start[0] = e.getScreenY();
                    start[1] = handleDistance;
                    e.consume();
                });
        tip.setOnMouseDragged(
                e -> {
                    double next =
                            start[1]
                                    + (start[0] - e.getScreenY())
                                            * distance
                                            / Math.max(scene.getHeight(), 1);
                    if (Math.abs(next) < 0.01) next = 0.01;
                    updateExtrusionDistance(next);
                    preview.accept(next);
                    e.consume();
                });
        tip.setOnMouseReleased(
                e -> {
                    accept.run();
                    e.consume();
                });
        handle.getChildren().addAll(shaft, tip);
        handlePlane = sketch.plane().offsetZ();
        updateExtrusionDistance(value);
    }

    public void setDark(boolean dark) {
        scene.setFill(Color.web(dark ? "#161c23" : "#e8edf1"));
    }

    public void showBodies(Map<String, MeshData> meshes) {
        bodies.getChildren().clear();
        bodyViews.clear();
        meshes.forEach(
                (id, data) -> {
                    MeshView view = meshView(data);
                    bodyViews.put(id, view);
                    view.setId("body-" + id);
                    view.setOnMouseClicked(
                            e -> {
                                if (e.getButton() == MouseButton.PRIMARY && e.isStillSincePress()) {
                                    int triangle = e.getPickResult().getIntersectedFace();
                                    selection.accept(
                                            id, triangle >= 0 ? data.faceIndex(triangle) : -1);
                                    e.consume();
                                }
                            });
                    bodies.getChildren().add(view);
                });
        select(selected);
    }

    private static MeshView meshView(MeshData data) {
        var mesh = new TriangleMesh();
        float[] points = new float[data.vertexCount() * 3];
        for (int v = 0; v < data.vertexCount(); v++) {
            var p = toView(data.coordinate(v, 0), data.coordinate(v, 1), data.coordinate(v, 2));
            points[3 * v] = (float) p.getX();
            points[3 * v + 1] = (float) p.getY();
            points[3 * v + 2] = (float) p.getZ();
        }
        int[] faces = new int[data.triangleCount() * 6];
        for (int t = 0; t < data.triangleCount(); t++)
            for (int c = 0; c < 3; c++) faces[6 * t + 2 * c] = data.vertexIndex(t, c);
        mesh.getPoints().setAll(points);
        mesh.getTexCoords().setAll(0, 0);
        mesh.getFaces().setAll(faces);
        mesh.getFaceSmoothingGroups().setAll(new int[data.triangleCount()]);
        var result = new MeshView(mesh);
        result.setCullFace(CullFace.BACK);
        result.setMaterial(new PhongMaterial(Color.web("#91a8b9")));
        return result;
    }

    public void select(String id) {
        selected = id;
        bodyViews.forEach(
                (key, view) ->
                        view.setMaterial(
                                new PhongMaterial(
                                        Color.web(key.equals(id) ? "#edae49" : "#91a8b9"))));
    }

    public void fit(Map<String, MeshData> meshes) {
        if (meshes.isEmpty()) {
            center.setX(-30);
            center.setY(0);
            center.setZ(-20);
            distance = 180;
        } else {
            double[]
                    min =
                            {
                                Double.POSITIVE_INFINITY,
                                Double.POSITIVE_INFINITY,
                                Double.POSITIVE_INFINITY
                            },
                    max = {-1e99, -1e99, -1e99};
            for (var mesh : meshes.values())
                for (int v = 0; v < mesh.vertexCount(); v++)
                    for (int a = 0; a < 3; a++) {
                        double x = mesh.coordinate(v, a);
                        min[a] = Math.min(min[a], x);
                        max[a] = Math.max(max[a], x);
                    }
            var p = toView((min[0] + max[0]) / 2, (min[1] + max[1]) / 2, (min[2] + max[2]) / 2);
            center.setX(-p.getX());
            center.setY(-p.getY());
            center.setZ(-p.getZ());
            distance =
                    Math.max(
                            20,
                            Math.max(Math.max(max[0] - min[0], max[1] - min[1]), max[2] - min[2])
                                    * 3.2);
        }
        pan.setX(0);
        pan.setY(0);
        camera.setTranslateZ(-distance);
    }

    public void standardView(String name) {
        switch (name) {
            case "Top" -> {
                tilt.setAngle(90);
                orbit.setAngle(0);
            }
            case "Front" -> {
                tilt.setAngle(0);
                orbit.setAngle(0);
            }
            case "Right" -> {
                tilt.setAngle(0);
                orbit.setAngle(90);
            }
            default -> {
                tilt.setAngle(28);
                orbit.setAngle(-35);
            }
        }
    }

    private void grid() {
        for (int n = -100; n <= 100; n += 10) {
            double width = n == 0 ? 0.15 : 0.055;
            var x = new Box(200, width, width);
            x.setTranslateZ(n);
            var y = new Box(width, width, 200);
            y.setTranslateX(n);
            x.setMaterial(new PhongMaterial(Color.web(n == 0 ? "#bb6666" : "#8795a3")));
            y.setMaterial(new PhongMaterial(Color.web(n == 0 ? "#55a184" : "#8795a3")));
            aids.getChildren().addAll(x, y);
        }
        var z = new Box(0.15, 50, 0.15);
        z.setTranslateY(-25);
        z.setMaterial(new PhongMaterial(Color.web("#5b86d6")));
        aids.getChildren().add(z);
        aids.setMouseTransparent(true);
    }
}
