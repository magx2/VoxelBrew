package dev.voxelbrew.engine.model;

import static dev.voxelbrew.engine.model.Evaluation.State.*;

import dev.voxelbrew.engine.feature.FeatureRegistry;
import dev.voxelbrew.engine.geometry.Solid;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Deterministic full replay. Only earlier, named outputs can be referenced. */
@lombok.extern.slf4j.Slf4j
public final class Evaluator {
    private Evaluator() {}

    public static Evaluation evaluate(CadDocument document) {
        return evaluate(document, FeatureRegistry.defaultDirectory());
    }

    /** The registry lives only on the evaluating thread; results contain engine-owned solids. */
    public static Evaluation evaluate(CadDocument document, Path pluginDirectory) {
        if (document.features().stream().noneMatch(Feature.Custom.class::isInstance))
            return replay(
                    document,
                    custom -> {
                        throw new AssertionError("No custom features");
                    });
        Evaluation result = null;
        try (var registry = new FeatureRegistry(pluginDirectory)) {
            result = replay(document, registry::evaluate);
            return result;
        } catch (IOException failure) {
            log.warn("Cannot load feature plugins from {}", pluginDirectory, failure);
            if (result != null) result.close();
            return replay(
                    document,
                    custom -> {
                        throw new ModelException(
                                custom.id(),
                                "Cannot load feature plugins: " + failure.getMessage());
                    });
        }
    }

    private static Evaluation replay(
            CadDocument document, Function<Feature.Custom, Solid> customEvaluator) {
        long started = System.nanoTime();
        log.debug(
                "Rebuilding document {}: {} features, cursor {}",
                document.id(),
                document.features().size(),
                document.cursor());
        var diagnostics = new ArrayList<Evaluation.Diagnostic>();
        var owned = new ArrayList<Solid>();
        Map<String, Feature.Sketch> sketches = new LinkedHashMap<>();
        Map<String, Feature.Plane> planes = new LinkedHashMap<>();
        Map<Feature.BodyRef, Evaluation.Body> outputs = new LinkedHashMap<>();
        Map<String, Evaluation.Body> active = new LinkedHashMap<>();
        Map<String, Evaluation.State> states = new LinkedHashMap<>();
        var resolved = new ArrayList<>(document.features());
        Map<String, javax.measure.Quantity<?>> parameterValues;
        try {
            parameterValues = document.parameters().values();
            for (var binding : document.parameters().bindings())
                if (document.features().stream()
                        .noneMatch(f -> f.id().equals(binding.target().featureId())))
                    throw new IllegalArgumentException(
                            "Binding refers to missing feature " + binding.target());
        } catch (IllegalArgumentException failure) {
            log.debug("Parameter evaluation failed for document {}", document.id(), failure);
            return new Evaluation(
                    document,
                    List.of(new Evaluation.Diagnostic("parameters", FAILED, failure.getMessage())),
                    List.of(),
                    List.of());
        }
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
                    feature =
                            dev.voxelbrew.engine.parameters.FeatureDimensions.resolve(
                                    feature,
                                    document.features(),
                                    document.parameters(),
                                    parameterValues);
                    resolved.set(index, feature);
                    FeatureValidation.validate(feature);
                    if (feature instanceof Feature.Sketch s) {
                        if (s.plane().support() != null) {
                            var support = s.plane().support();
                            var source = outputs.get(support.body());
                            if (source == null)
                                throw new ModelException(
                                        s.id(), "Missing supporting body output " + support.body());
                            var plane =
                                    new Feature.Plane(
                                            s.plane().id(),
                                            s.plane().offsetZ(),
                                            support.resolve(
                                                    source.solid().planarFaces(),
                                                    s.plane().frame()),
                                            support);
                            s = new Feature.Sketch(s.id(), s.profileId(), plane, s.profile());
                            resolved.set(index, s);
                        }
                        var earlier = planes.get(s.plane().id());
                        if (earlier != null && !earlier.equals(s.plane())) {
                            throw new ModelException(
                                    s.id(),
                                    "conflicting definition of plane '" + s.plane().id() + "'");
                        }
                        planes.put(s.plane().id(), s.plane());
                        sketches.put(s.id(), s);
                    } else if (feature instanceof Feature.Custom custom) {
                        Solid solid = customEvaluator.apply(custom);
                        owned.add(solid);
                        var body =
                                new Evaluation.Body(
                                        custom.id() + ".body",
                                        new Feature.BodyRef(custom.id(), "body"),
                                        solid);
                        outputs.put(body.output(), body);
                        active.put(body.id(), body);
                    } else if (feature instanceof Feature.Extrude e) {
                        Feature.Sketch sketch = profile(e.id(), e.profile(), sketches);
                        Solid solid;
                        var additionalBodies = new ArrayList<Evaluation.Body>();
                        if (sketch.profile() instanceof Feature.Rectangle r) {
                            solid =
                                    place(
                                            Solid.rectanglePrism(
                                                    r.x(),
                                                    r.y(),
                                                    sketch.plane().offsetZ(),
                                                    r.width(),
                                                    r.height(),
                                                    e.distance()),
                                            sketch.plane().frame());
                        } else if (sketch.profile() instanceof Feature.Circle c) {
                            solid =
                                    place(
                                            Solid.circlePrism(
                                                    c.centerX(),
                                                    c.centerY(),
                                                    sketch.plane().offsetZ(),
                                                    c.diameter(),
                                                    e.distance()),
                                            sketch.plane().frame());
                        } else if (sketch.profile() instanceof Feature.Constrained c) {
                            var regions =
                                    dev.voxelbrew.engine.sketch.SketchProfiles.regions(
                                            c.geometry());
                            solid =
                                    place(
                                            Solid.profilePrism(
                                                    regions.getFirst().profile(),
                                                    sketch.plane().offsetZ(),
                                                    e.distance()),
                                            sketch.plane().frame());
                            owned.add(solid);
                            for (int region = 1; region < regions.size(); region++) {
                                var r = regions.get(region);
                                var extra =
                                        place(
                                                Solid.profilePrism(
                                                        r.profile(),
                                                        sketch.plane().offsetZ(),
                                                        e.distance()),
                                                sketch.plane().frame());
                                owned.add(extra);
                                String name = "body-" + r.id();
                                var body =
                                        new Evaluation.Body(
                                                e.id() + "." + name,
                                                new Feature.BodyRef(e.id(), name),
                                                extra);
                                additionalBodies.add(body);
                            }
                        } else {
                            throw new ModelException(e.id(), "unsupported extrusion profile");
                        }
                        if (!owned.contains(solid)) owned.add(solid);
                        var body =
                                new Evaluation.Body(
                                        e.id() + ".body",
                                        new Feature.BodyRef(e.id(), "body"),
                                        solid);
                        outputs.put(body.output(), body);
                        active.put(body.id(), body);
                        for (var additional : additionalBodies) {
                            outputs.put(additional.output(), additional);
                            active.put(additional.id(), additional);
                        }
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
                        if (!(sketch.profile() instanceof Feature.Circle)
                                && !(sketch.profile() instanceof Feature.Constrained)) {
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
                        // Evaluate through-all in sketch coordinates, then restore model
                        // coordinates.
                        var frame = sketch.plane().frame();
                        Solid local = input.solid();
                        if (!frame.equals(Transform.IDENTITY)) {
                            local = local.transformed(frame.inverse());
                            owned.add(local);
                        }
                        Solid solid;
                        if (sketch.profile() instanceof Feature.Circle circle) {
                            solid =
                                    local.cutThroughCircle(
                                            circle.centerX(), circle.centerY(), circle.diameter());
                        } else {
                            var constrained = (Feature.Constrained) sketch.profile();
                            solid = local;
                            for (var region :
                                    dev.voxelbrew.engine.sketch.SketchProfiles.regions(
                                            constrained.geometry())) {
                                solid = solid.cutThroughProfile(region.profile());
                                owned.add(solid);
                            }
                        }
                        if (!owned.contains(solid)) owned.add(solid);
                        if (!frame.equals(Transform.IDENTITY)) {
                            solid = solid.transformed(frame);
                            owned.add(solid);
                        }
                        var body =
                                new Evaluation.Body(
                                        input.id(), new Feature.BodyRef(cut.id(), "body"), solid);
                        outputs.put(body.output(), body);
                        active.put(body.id(), body);
                    }
                    states.put(feature.id(), OK);
                    diagnostics.add(new Evaluation.Diagnostic(feature.id(), OK, "rebuilt"));
                } catch (Blocked failure) {
                    log.debug("Feature {} blocked: {}", feature.id(), failure.getMessage());
                    states.put(feature.id(), BLOCKED);
                    diagnostics.add(
                            new Evaluation.Diagnostic(feature.id(), BLOCKED, failure.getMessage()));
                } catch (RuntimeException failure) {
                    log.debug(
                            "Feature {} failed in document {}",
                            feature.id(),
                            document.id(),
                            failure);
                    states.put(feature.id(), FAILED);
                    diagnostics.add(
                            new Evaluation.Diagnostic(feature.id(), FAILED, failure.getMessage()));
                }
            }
            if (log.isDebugEnabled())
                log.debug(
                        "Rebuilt document {} in {} ms: {} active bodies, {} failed features",
                        document.id(),
                        (System.nanoTime() - started) / 1_000_000,
                        active.size(),
                        diagnostics.stream().filter(d -> d.state() == FAILED).count());
            return new Evaluation(
                    document, diagnostics, new ArrayList<>(active.values()), owned, resolved);
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

    private static Solid place(Solid local, Transform frame) {
        if (frame.equals(Transform.IDENTITY)) return local;
        try (local) {
            return local.transformed(frame);
        }
    }

    private static List<String> references(Feature feature) {
        if (feature instanceof Feature.Sketch s && s.plane().support() != null)
            return List.of(s.plane().support().body().featureId());
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
