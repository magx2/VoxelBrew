package dev.voxelbrew.engine.model;

import static dev.voxelbrew.engine.model.Evaluation.State.*;

import dev.voxelbrew.engine.geometry.Solid;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deterministic full replay. Only earlier, named outputs can be referenced. */
public final class Evaluator {
    private Evaluator() {}

    public static Evaluation evaluate(CadDocument document) {
        var diagnostics = new ArrayList<Evaluation.Diagnostic>();
        var owned = new ArrayList<Solid>();
        Map<String, Feature.Sketch> sketches = new LinkedHashMap<>();
        Map<String, Feature.Plane> planes = new LinkedHashMap<>();
        Map<Feature.BodyRef, Evaluation.Body> outputs = new LinkedHashMap<>();
        Map<String, Evaluation.Body> active = new LinkedHashMap<>();
        Map<String, Evaluation.State> states = new LinkedHashMap<>();
        try {
            for (int index = 0; index < document.features().size(); index++) {
                Feature feature = document.features().get(index);
                if (index >= document.cursor()) {
                    diagnostics.add(
                            new Evaluation.Diagnostic(
                                    feature.id(), ROLLED_BACK, "after timeline cursor"));
                    continue;
                }
                try {
                    for (String input : references(feature)) {
                        if (!states.containsKey(input)) {
                            throw new ModelException(
                                    feature.id(), "missing or non-earlier input '" + input + "'");
                        }
                        if (states.get(input) != OK) {
                            throw new Blocked("input '" + input + "' did not rebuild");
                        }
                    }
                    FeatureValidation.validate(feature);
                    if (feature instanceof Feature.Sketch s) {
                        var earlier = planes.get(s.plane().id());
                        if (earlier != null && !earlier.equals(s.plane())) {
                            throw new ModelException(
                                    s.id(),
                                    "conflicting definition of plane '" + s.plane().id() + "'");
                        }
                        planes.put(s.plane().id(), s.plane());
                        sketches.put(s.id(), s);
                    } else if (feature instanceof Feature.Extrude e) {
                        Feature.Sketch sketch = profile(e.id(), e.profile(), sketches);
                        Solid solid;
                        if (sketch.profile() instanceof Feature.Rectangle r) {
                            solid =
                                    Solid.rectanglePrism(
                                            r.x(),
                                            r.y(),
                                            sketch.plane().offsetZ(),
                                            r.width(),
                                            r.height(),
                                            e.distance());
                        } else if (sketch.profile() instanceof Feature.Circle c) {
                            solid =
                                    Solid.circlePrism(
                                            c.centerX(),
                                            c.centerY(),
                                            sketch.plane().offsetZ(),
                                            c.diameter(),
                                            e.distance());
                        } else {
                            throw new ModelException(e.id(), "unsupported extrusion profile");
                        }
                        owned.add(solid);
                        var body =
                                new Evaluation.Body(
                                        e.id() + ".body",
                                        new Feature.BodyRef(e.id(), "body"),
                                        solid);
                        outputs.put(body.output(), body);
                        active.put(body.id(), body);
                    } else if (feature instanceof Feature.SplitBody split) {
                        Evaluation.Body input = outputs.get(split.body());
                        if (input == null)
                            throw new ModelException(
                                    split.id(), "missing body output " + split.body());
                        if (active.get(input.id()) != input)
                            throw new ModelException(
                                    split.id(),
                                    "body input has been superseded by a later feature");
                        var p = split.plane();
                        var parts =
                                input.solid()
                                        .split(p.normalX(), p.normalY(), p.normalZ(), p.offset());
                        owned.add(parts.negative());
                        owned.add(parts.positive());
                        active.remove(input.id());
                        for (var side : List.of("negative", "positive")) {
                            var body =
                                    new Evaluation.Body(
                                            split.id() + "." + side,
                                            new Feature.BodyRef(split.id(), side),
                                            side.equals("negative")
                                                    ? parts.negative()
                                                    : parts.positive());
                            outputs.put(body.output(), body);
                            active.put(body.id(), body);
                        }
                    } else if (feature instanceof Feature.ThroughCut cut) {
                        Feature.Sketch sketch = profile(cut.id(), cut.profile(), sketches);
                        if (!(sketch.profile() instanceof Feature.Circle circle)) {
                            throw new ModelException(
                                    cut.id(), "through-all cut requires a circle profile");
                        }
                        Evaluation.Body input = outputs.get(cut.body());
                        if (input == null) {
                            throw new ModelException(cut.id(), "missing body output " + cut.body());
                        }
                        if (active.get(input.id()) != input)
                            throw new ModelException(
                                    cut.id(), "body input has been superseded by a later feature");
                        // M1 through-all is the complete Z extent normal to any XY-parallel plane.
                        Solid solid =
                                input.solid()
                                        .cutThroughCircle(
                                                circle.centerX(),
                                                circle.centerY(),
                                                circle.diameter());
                        owned.add(solid);
                        var body =
                                new Evaluation.Body(
                                        input.id(), new Feature.BodyRef(cut.id(), "body"), solid);
                        outputs.put(body.output(), body);
                        active.put(body.id(), body);
                    }
                    states.put(feature.id(), OK);
                    diagnostics.add(new Evaluation.Diagnostic(feature.id(), OK, "rebuilt"));
                } catch (Blocked failure) {
                    states.put(feature.id(), BLOCKED);
                    diagnostics.add(
                            new Evaluation.Diagnostic(feature.id(), BLOCKED, failure.getMessage()));
                } catch (RuntimeException failure) {
                    states.put(feature.id(), FAILED);
                    diagnostics.add(
                            new Evaluation.Diagnostic(feature.id(), FAILED, failure.getMessage()));
                }
            }
            return new Evaluation(document, diagnostics, new ArrayList<>(active.values()), owned);
        } catch (Throwable failure) {
            owned.forEach(Solid::close);
            throw failure;
        }
    }

    private static Feature.Sketch profile(
            String owner, Feature.ProfileRef ref, Map<String, Feature.Sketch> sketches) {
        Feature.Sketch sketch = sketches.get(ref.sketchId());
        if (sketch == null || !sketch.profileId().equals(ref.profileId())) {
            throw new ModelException(
                    owner,
                    "missing sketch/profile '" + ref.sketchId() + "/" + ref.profileId() + "'");
        }
        return sketch;
    }

    private static List<String> references(Feature feature) {
        if (feature instanceof Feature.Extrude e) {
            return List.of(e.profile().sketchId());
        }
        if (feature instanceof Feature.ThroughCut c) {
            return List.of(c.profile().sketchId(), c.body().featureId());
        }
        if (feature instanceof Feature.SplitBody split) return List.of(split.body().featureId());
        return List.of();
    }

    private static final class Blocked extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Blocked(String message) {
            super(message);
        }
    }
}
