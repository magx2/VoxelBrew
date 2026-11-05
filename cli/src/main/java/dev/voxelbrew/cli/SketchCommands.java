package dev.voxelbrew.cli;

import static dev.voxelbrew.engine.sketch.SketchGeometry.*;

import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.engine.sketch.*;
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
            SketchCommands.Polygon.class
        })
final class SketchCommands implements Runnable {
    @Spec CommandLine.Model.CommandSpec spec;

    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    static void register(CommandLine cli) {
        cli.addSubcommand("sketch", new SketchCommands());
    }

    abstract static class EditSketch extends VoxelBrew.FileCommand {
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
            var result = SketchSolver.solve(geometry);
            var solved = result.requireSolved();
            session.replace(
                    new Feature.Sketch(
                            sketch.id(),
                            sketch.profileId(),
                            sketch.plane(),
                            new Feature.Constrained(solved)));
            spec.commandLine().getOut().println(result.status());
            return save(session);
        }

        SketchGeometry geometry(ModelSession session) {
            return Sketches.fromProfile(selected(session).profile());
        }
    }

    @Command(
            name = "new",
            mixinStandardHelpOptions = true,
            description = "Add an empty constrained sketch")
    static final class Create extends VoxelBrew.AddSketch {
        public Integer call() throws Exception {
            return sketch(new Feature.Constrained(SketchGeometry.empty()));
        }
    }

    @Command(
            name = "convert",
            mixinStandardHelpOptions = true,
            description = "Convert a rectangle/circle without changing its feature or profile IDs")
    static final class Convert extends EditSketch {
        public Integer call() throws Exception {
            var session = session();
            return publish(session, geometry(session));
        }
    }

    @Command(
            name = "entity",
            mixinStandardHelpOptions = true,
            description =
                    "Add an entity: POINT x,y; LINE x1,y1,x2,y2; CIRCLE cx,cy,r; ARC cx,cy,r,start,sweep (radians); SPLINE x0,y0,x1,y1,x2,y2,x3,y3")
    static final class PutEntity extends EditSketch {
        @Option(names = "--id", required = true)
        String id;

        @Option(names = "--kind", required = true)
        Kind kind;

        @Option(names = "--values", required = true, split = ",")
        List<Double> values;

        @Option(names = "--construction")
        boolean construction;

        @Option(
                names = "--replace",
                description =
                        "Replace an existing entity; reject edits that conflict with constraints")
        boolean replace;

        public Integer call() throws Exception {
            var session = session();
            var g = geometry(session);
            var entity = new Entity(id, kind, values, construction || kind == Kind.POINT);
            if (replace) {
                var previous = g.entity(id);
                if (previous.kind() != kind)
                    throw new IllegalArgumentException(
                            "Changing an entity's kind would invalidate references; remove it first");
                if (entity.construction())
                    g = g.withBoundary(g.boundary().stream().filter(e -> !e.equals(id)).toList());
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

        @Option(
                names = "--value",
                defaultValue = "0",
                description = "Driving value in mm, or degrees for ANGLE")
        double value;

        @Option(names = "--tangency", description = "Optional circle/circle tangency branch")
        Tangency tangency;

        public Integer call() throws Exception {
            var session = session();
            var g = geometry(session);
            var constraint =
                    SketchConstraints.create(
                            g, id, type, refs.stream().map(Ref::parse).toList(), value);
            if (tangency != null) {
                if (type != Type.TANGENT
                        || constraint.refs().size() != 2
                        || constraint.refs().stream()
                                .anyMatch(
                                        r ->
                                                r.part() != Part.OBJECT
                                                        || g.entity(r.entityId()).kind()
                                                                != Kind.CIRCLE))
                    throw new IllegalArgumentException(
                            "--tangency requires two whole circles and TANGENT");
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
        @Option(names = "--id", required = true)
        String id;

        public Integer call() throws Exception {
            var session = session();
            var g = geometry(session);
            if (g.constraints().stream().noneMatch(c -> c.id().equals(id)))
                throw new IllegalArgumentException("Missing constraint: " + id);
            return publish(
                    session,
                    g.withConstraints(
                            g.constraints().stream().filter(c -> !c.id().equals(id)).toList()));
        }
    }

    @Command(
            name = "remove-entity",
            mixinStandardHelpOptions = true,
            description = "Remove an entity and its dependent constraints")
    static final class RemoveEntity extends EditSketch {
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
            description =
                    "Select one closed exterior and optional interior holes for extrusion/cutting")
    static final class Profile extends EditSketch {
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
        public Integer call() throws Exception {
            var session = session();
            return publish(session, geometry(session));
        }
    }

    @Command(
            name = "list",
            mixinStandardHelpOptions = true,
            description =
                    "List entity IDs, constraints, profile selection, degrees of freedom and conflicts")
    static final class Listing extends EditSketch {
        public Integer call() throws Exception {
            var g = geometry(session());
            var output = spec.commandLine().getOut();
            var solved = SketchSolver.solve(g);
            output.println(solved.status());
            g.entities()
                    .forEach(
                            e ->
                                    output.println(
                                            "ENTITY "
                                                    + e.id()
                                                    + " "
                                                    + e.kind().name()
                                                    + " "
                                                    + e.values()
                                                    + " construction="
                                                    + e.construction()));
            g.constraints()
                    .forEach(
                            c ->
                                    output.println(
                                            "CONSTRAINT "
                                                    + c.id()
                                                    + " "
                                                    + c.type().name()
                                                    + " "
                                                    + c.refs()
                                                    + " "
                                                    + c.values()));
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
