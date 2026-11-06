package dev.voxelbrew.cli;

import static dev.voxelbrew.engine.sketch.SketchGeometry.*;

import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.engine.sketch.*;
import dev.voxelbrew.exporter.api.DocumentSink;
import dev.voxelbrew.importer.api.DocumentSource;
import java.util.*;
import picocli.CommandLine;
import picocli.CommandLine.*;

/** Scriptable sketch operations backed by the same geometry, constraints and solver as the GUI. */
@Command(
        name = "sketch",
        mixinStandardHelpOptions = true,
        description = "Create and constrain 2D sketches (millimeters; arc parameters in radians)",
        subcommands = {
            SketchCommands.Create.class,
            SketchCommands.Convert.class,
            SketchCommands.PutEntity.class,
            SketchCommands.Constrain.class,
            SketchCommands.RemoveConstraint.class,
            SketchCommands.RemoveEntity.class,
            SketchCommands.Profile.class,
            SketchCommands.Solve.class,
            SketchCommands.Listing.class,
            SketchCommands.Move.class,
            SketchCommands.Polygon.class,
            SketchCommands.Draw.class,
            SketchCommands.EditText.class
        })
final class SketchCommands implements Runnable {

    @javax.inject.Inject
    SketchCommands() {}

    @Spec
    CommandLine.Model.CommandSpec spec;

    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    static void register(CommandLine cli) {
        cli.addSubcommand("sketch", SketchCommands.class);
    }

    @Command(
            name = "draw",
            mixinStandardHelpOptions = true,
            description = "Create a sketch type/variant; use --help for tools and numeric options")
    static final class Draw extends EditSketch {

        @javax.inject.Inject
        Draw(DocumentSource documents, DocumentSink documentWriter) {
            super(documents, documentWriter);
        }

        @Option(names = "--id", required = true)
        String id;

        @Option(names = "--tool", required = true, description = "${COMPLETION-CANDIDATES}")
        SketchTools.Tool tool;

        @Option(names = "--points", required = true, split = ",", description = "Ordered x,y pairs in millimeters")
        List<Double> points;

        @Option(names = "--refs", split = ",", description = "Selected lines, tangent endpoint, or text path")
        List<String> refs = List.of();

        @Option(names = "--sides", defaultValue = "6")
        int sides;

        @Option(names = "--degree", defaultValue = "3")
        int degree;

        @Option(names = "--closed")
        boolean closed;

        @Option(names = "--construction")
        boolean construction;

        @Option(names = "--radius", defaultValue = "5", description = "2-tangent circle radius")
        double radius;

        @Option(names = "--rho", defaultValue = "0.5")
        double rho;

        @Option(names = "--text", defaultValue = "Text")
        String text;

        @Option(names = "--font", defaultValue = "SansSerif")
        String font;

        @Option(names = "--style", defaultValue = "0", description = "0 regular, 1 bold, 2 italic, 3 bold italic")
        int style;

        @Option(names = "--height", defaultValue = "10")
        double height;

        @Option(names = "--angle", defaultValue = "0", description = "Text rotation in degrees")
        double angle;

        @Option(names = "--spacing", defaultValue = "0", description = "Extra text character spacing in em")
        double spacing;

        public Integer call() throws Exception {
            if (points.size() % 2 != 0) throw new IllegalArgumentException("Points must be x,y pairs");
            var positions = new ArrayList<Vec>();
            for (int i = 0; i < points.size(); i += 2) positions.add(new Vec(points.get(i), points.get(i + 1)));
            var session = session();
            return publish(
                    session,
                    SketchTools.add(
                            geometry(session),
                            tool,
                            id,
                            positions,
                            refs.stream().map(Ref::parse).toList(),
                            new SketchTools.Options(
                                    sides,
                                    degree,
                                    closed,
                                    construction,
                                    radius,
                                    rho,
                                    text,
                                    font,
                                    style,
                                    height,
                                    dev.voxelbrew.engine.units.Measurements.radians(angle),
                                    spacing)));
        }
    }

    @Command(
            name = "text-edit",
            mixinStandardHelpOptions = true,
            description = "Change persisted sketch text while preserving its identity and dimensions")
    static final class EditText extends EditSketch {

        @javax.inject.Inject
        EditText(DocumentSource documents, DocumentSink documentWriter) {
            super(documents, documentWriter);
        }

        @Option(names = "--id", required = true)
        String id;

        @Option(names = "--text", required = true)
        String text;

        @Option(names = "--font")
        String font;

        @Option(names = "--path", description = "Replacement text path ID; an empty value detaches it")
        String path;

        public Integer call() throws Exception {
            var session = session();
            var g = geometry(session);
            var e = g.entity(id);
            if (e.kind() != Kind.TEXT) throw new IllegalArgumentException("Select a text entity");
            var data = e.text();
            var edited = SketchText.create(
                    id,
                    text,
                    font == null ? data.font() : font,
                    data.style(),
                    e.value(0),
                    e.value(1),
                    e.value(2),
                    e.value(3),
                    e.value(4),
                    path == null ? data.pathId() : path,
                    e.construction());
            return publish(session, g.replace(edited));
        }
    }

    abstract static class EditSketch extends VoxelBrew.FileCommand {

        EditSketch(DocumentSource documents, DocumentSink documentWriter) {
            super(documents, documentWriter);
        }

        @Option(names = "--sketch", required = true, description = "Stable sketch feature ID")
        String sketchId;

        Feature.Sketch selected(ModelSession session) {
            return session.document().features().stream()
                    .filter(f -> f.id().equals(sketchId) && f instanceof Feature.Sketch)
                    .map(f -> (Feature.Sketch) f)
                    .findFirst()
                    .orElseThrow(() -> new ModelException(sketchId, "sketch does not exist"));
        }

        int publish(ModelSession session, SketchGeometry geometry) throws Exception {
            var sketch = selected(session);
            var parameters = dev.voxelbrew.engine.parameters.FeatureDimensions.convert(
                            sketch, session.document().parameters())
                    .parameters();
            parameters = dev.voxelbrew.engine.parameters.FeatureDimensions.retainSketchBindings(
                    parameters, sketch.id(), geometry);
            geometry =
                    dev.voxelbrew.engine.parameters.FeatureDimensions.resolveSketch(geometry, sketch.id(), parameters);
            var result = SketchSolver.solve(geometry);
            var solved = result.requireSolved();
            session.replace(
                    new Feature.Sketch(
                            sketch.id(), sketch.profileId(), sketch.plane(), new Feature.Constrained(solved)),
                    parameters);
            spec.commandLine().getOut().println(result.status());
            return save(session);
        }

        SketchGeometry geometry(ModelSession session) {
            var document = session.document();
            var sketch = (Feature.Sketch) dev.voxelbrew.engine.parameters.FeatureDimensions.resolve(
                    selected(session),
                    document.features(),
                    document.parameters(),
                    document.parameters().values());
            return dev.voxelbrew.engine.parameters.FeatureDimensions.convert(sketch, document.parameters())
                    .geometry();
        }
    }

    @Command(name = "new", mixinStandardHelpOptions = true, description = "Add an empty constrained sketch")
    static final class Create extends VoxelBrew.AddSketch {

        @javax.inject.Inject
        Create(DocumentSource documents, DocumentSink documentWriter) {
            super(documents, documentWriter);
        }

        public Integer call() throws Exception {
            return sketch(new Feature.Constrained(SketchGeometry.empty()));
        }
    }

    @Command(
            name = "convert",
            mixinStandardHelpOptions = true,
            description = "Convert a rectangle/circle without changing its feature or profile IDs")
    static final class Convert extends EditSketch {

        @javax.inject.Inject
        Convert(DocumentSource documents, DocumentSink documentWriter) {
            super(documents, documentWriter);
        }

        public Integer call() throws Exception {
            var session = session();
            return publish(session, geometry(session));
        }
    }

    @Command(
            name = "entity",
            mixinStandardHelpOptions = true,
            description =
                    "Add POINT x,y; LINE x1,y1,x2,y2; CIRCLE cx,cy,r; ARC cx,cy,r,start,sweep; ELLIPSE cx,cy,r1,r2,angle; CONIC startX,startY,vertexX,vertexY,endX,endY,rho; SPLINE four x,y pairs; FIT_SPLINE / CONTROL_SPLINE variable x,y pairs. Angles are radians. Use sketch draw for text.")
    static final class PutEntity extends EditSketch {

        @javax.inject.Inject
        PutEntity(DocumentSource documents, DocumentSink documentWriter) {
            super(documents, documentWriter);
        }

        @Option(names = "--id", required = true)
        String id;

        @Option(names = "--kind", required = true)
        Kind kind;

        @Option(names = "--values", required = true, split = ",")
        List<Double> values;

        @Option(names = "--construction")
        boolean construction;

        @Option(names = "--degree", defaultValue = "3", description = "Control spline degree: 3 or 5")
        int degree;

        @Option(names = "--closed", description = "Close a fit/control spline")
        boolean closed;

        @Option(
                names = "--replace",
                description = "Replace an existing entity; reject edits that conflict with constraints")
        boolean replace;

        public Integer call() throws Exception {
            var session = session();
            var g = geometry(session);
            var entity = new Entity(
                    id,
                    kind,
                    values,
                    construction || kind == Kind.POINT,
                    kind == Kind.CONTROL_SPLINE ? degree : 0,
                    closed,
                    null);
            if (replace) {
                var previous = g.entity(id);
                if (previous.kind() != kind)
                    throw new IllegalArgumentException(
                            "Changing an entity's kind would invalidate references; remove it first");
                if (entity.construction())
                    g = g.withBoundary(
                            g.boundary().stream().filter(e -> !e.equals(id)).toList());
                g = Sketches.reshape(g, entity).requireSolved();
            } else {
                g = g.add(entity);
                if (!entity.construction()) {
                    var boundary = new ArrayList<>(g.boundary());
                    boundary.add(id);
                    g = g.withBoundary(boundary);
                }
            }
            return publish(session, g);
        }
    }

    @Command(
            name = "constrain",
            mixinStandardHelpOptions = true,
            description =
                    "Add a geometric constraint or driving dimension; refs use entity.start/end/center/control1/control2 or origin.point")
    static final class Constrain extends EditSketch {

        @javax.inject.Inject
        Constrain(DocumentSource documents, DocumentSink documentWriter) {
            super(documents, documentWriter);
        }

        enum Tangency {
            EXTERNAL,
            INTERNAL
        }

        @Option(names = "--id", required = true)
        String id;

        @Option(names = "--type", required = true)
        Type type;

        @Option(names = "--refs", required = true, split = ",")
        List<String> refs;

        @Option(names = "--value", defaultValue = "0", description = "Driving value in mm, or degrees for ANGLE")
        double value;

        @Option(names = "--tangency", description = "Optional circle/circle tangency branch")
        Tangency tangency;

        public Integer call() throws Exception {
            var session = session();
            var g = geometry(session);
            var constraint = SketchConstraints.create(
                    g, id, type, refs.stream().map(Ref::parse).toList(), value);
            if (tangency != null) {
                if (type != Type.TANGENT
                        || constraint.refs().size() != 2
                        || constraint.refs().stream()
                                .anyMatch(r -> r.part() != Part.OBJECT
                                        || g.entity(r.entityId()).kind() != Kind.CIRCLE))
                    throw new IllegalArgumentException("--tangency requires two whole circles and TANGENT");
                constraint = constraint.withValue(tangency == Tangency.EXTERNAL ? 1 : -1);
            }
            return publish(session, g.add(constraint));
        }
    }

    @Command(
            name = "remove-constraint",
            mixinStandardHelpOptions = true,
            description = "Remove a constraint or unfix geometry by constraint ID")
    static final class RemoveConstraint extends EditSketch {

        @javax.inject.Inject
        RemoveConstraint(DocumentSource documents, DocumentSink documentWriter) {
            super(documents, documentWriter);
        }

        @Option(names = "--id", required = true)
        String id;

        public Integer call() throws Exception {
            var session = session();
            var g = geometry(session);
            if (g.constraints().stream().noneMatch(c -> c.id().equals(id)))
                throw new IllegalArgumentException("Missing constraint: " + id);
            return publish(
                    session,
                    g.withConstraints(g.constraints().stream()
                            .filter(c -> !c.id().equals(id))
                            .toList()));
        }
    }

    @Command(
            name = "remove-entity",
            mixinStandardHelpOptions = true,
            description = "Remove an entity and its dependent constraints")
    static final class RemoveEntity extends EditSketch {

        @javax.inject.Inject
        RemoveEntity(DocumentSource documents, DocumentSink documentWriter) {
            super(documents, documentWriter);
        }

        @Option(names = "--id", required = true)
        String id;

        public Integer call() throws Exception {
            var session = session();
            return publish(session, Sketches.removeEntity(geometry(session), id));
        }
    }

    @Command(
            name = "profile",
            mixinStandardHelpOptions = true,
            description = "Select one closed exterior and optional interior holes for extrusion/cutting")
    static final class Profile extends EditSketch {

        @javax.inject.Inject
        Profile(DocumentSource documents, DocumentSink documentWriter) {
            super(documents, documentWriter);
        }

        @Option(names = "--entities", required = true, split = ",")
        List<String> entities;

        public Integer call() throws Exception {
            var session = session();
            var g = geometry(session).withBoundary(entities);
            SketchProfiles.profile(g);
            return publish(session, g);
        }
    }

    @Command(
            name = "solve",
            mixinStandardHelpOptions = true,
            description = "Solve and save a repairable sketch; conflicts leave the file unchanged")
    static final class Solve extends EditSketch {

        @javax.inject.Inject
        Solve(DocumentSource documents, DocumentSink documentWriter) {
            super(documents, documentWriter);
        }

        public Integer call() throws Exception {
            var session = session();
            return publish(session, geometry(session));
        }
    }

    @Command(
            name = "list",
            mixinStandardHelpOptions = true,
            description = "List entity IDs, constraints, profile selection, degrees of freedom and conflicts")
    static final class Listing extends EditSketch {

        @javax.inject.Inject
        Listing(DocumentSource documents, DocumentSink documentWriter) {
            super(documents, documentWriter);
        }

        public Integer call() throws Exception {
            var g = geometry(session());
            var output = spec.commandLine().getOut();
            var solved = SketchSolver.solve(g);
            output.println(solved.status());
            g.entities()
                    .forEach(e -> output.println("ENTITY "
                            + e.id()
                            + " "
                            + e.kind().name()
                            + " "
                            + e.values()
                            + " construction="
                            + e.construction()));
            g.constraints()
                    .forEach(c -> output.println(
                            "CONSTRAINT " + c.id() + " " + c.type().name() + " " + c.refs() + " " + c.values()));
            output.println("PROFILE " + g.boundary());
            solved.problems().forEach(p -> spec.commandLine().getErr().println(p.message()));
            return solved.solved() ? 0 : 1;
        }
    }

    @Command(
            name = "move",
            mixinStandardHelpOptions = true,
            description = "Move a point while satisfying its constraints")
    static final class Move extends EditSketch {

        @javax.inject.Inject
        Move(DocumentSource documents, DocumentSink documentWriter) {
            super(documents, documentWriter);
        }

        @Option(names = "--point", required = true)
        String point;

        @Option(names = "--x", required = true)
        double x;

        @Option(names = "--y", required = true)
        double y;

        public Integer call() throws Exception {
            var session = session();
            return publish(
                    session,
                    Sketches.drag(geometry(session), Ref.parse(point), new Vec(x, y))
                            .requireSolved());
        }
    }

    @Command(
            name = "polygon",
            mixinStandardHelpOptions = true,
            description = "Add a regular polygon with its persistent polygon constraint")
    static final class Polygon extends EditSketch {

        @javax.inject.Inject
        Polygon(DocumentSource documents, DocumentSink documentWriter) {
            super(documents, documentWriter);
        }

        @Option(names = "--id", required = true)
        String id;

        @Option(names = "--center-x", defaultValue = "0")
        double x;

        @Option(names = "--center-y", defaultValue = "0")
        double y;

        @Option(names = "--radius", required = true)
        double radius;

        @Option(names = "--sides", defaultValue = "6")
        int sides;

        public Integer call() throws Exception {
            var session = session();
            var g = geometry(session);
            var polygon = Sketches.polygon(id, x, y, radius, sides);
            var entities = new ArrayList<>(g.entities());
            entities.addAll(polygon.entities());
            var constraints = new ArrayList<>(g.constraints());
            constraints.addAll(polygon.constraints());
            var boundary = new ArrayList<>(g.boundary());
            boundary.addAll(polygon.boundary());
            return publish(session, new SketchGeometry(entities, constraints, boundary));
        }
    }
}
