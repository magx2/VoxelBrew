package dev.voxelbrew.engine.parameters;

import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.engine.sketch.*;
import java.util.*;
import javax.measure.Quantity;

/**
 * Shared field metadata and simultaneous dimension edits; solving is performed by the caller's
 * worker.
 */
public final class FeatureDimensions {
    private FeatureDimensions() {}

    public record Dimension(ParameterSet.Target target, ParameterUnit unit, double value) {
        public javax.measure.Unit<?> measurementUnit() {
            return unit.unit();
        }

        public Quantity<?> quantity() {
            return dev.voxelbrew.engine.units.Measurements.quantity(value, unit.canonicalUnit());
        }

        @Override
        public String toString() {
            return target + " (" + unit + ")";
        }
    }

    public static ParameterUnit unit(SketchGeometry.Type type) {
        return switch (type) {
            case ANGLE -> ParameterUnit.DEG;
            case RHO, TEXT_SPACING -> ParameterUnit.NUMBER;
            default -> ParameterUnit.MM;
        };
    }

    public static List<Dimension> list(Feature feature) {
        var result = new ArrayList<Dimension>();
        java.util.function.BiConsumer<String, Double> length = (key, value) ->
                result.add(new Dimension(new ParameterSet.Target(feature.id(), key), ParameterUnit.MM, value));
        if (feature instanceof Feature.Sketch s) {
            if (!s.plane().isOrigin()) length.accept("offsetZ", s.plane().offsetZ());
            if (s.profile() instanceof Feature.Rectangle r) {
                length.accept("x", r.x());
                length.accept("y", r.y());
                length.accept("width", r.width());
                length.accept("height", r.height());
            } else if (s.profile() instanceof Feature.Circle c) {
                length.accept("centerX", c.centerX());
                length.accept("centerY", c.centerY());
                length.accept("diameter", c.diameter());
            } else if (s.profile() instanceof Feature.Constrained c) {
                for (var d : c.geometry().constraints())
                    if (d.type().dimension())
                        result.add(new Dimension(
                                new ParameterSet.Target(s.id(), "constraint:" + d.id()),
                                unit(d.type()),
                                d.values().getFirst()));
            }
        } else if (feature instanceof Feature.Extrude e) length.accept("distance", e.distance());
        else if (feature instanceof Feature.EdgeFinish f) length.accept("size", f.size());
        else if (feature instanceof Feature.Shell s) length.accept("thickness", s.thickness());
        else if (feature instanceof Feature.SplitBody s)
            length.accept("offset", s.plane().offset());
        return List.copyOf(result);
    }

    public static Dimension dimension(Feature feature, String field) {
        return list(feature).stream()
                .filter(d -> d.target().field().equals(field))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown or non-drivable dimension: " + feature.id() + "/" + field));
    }

    public static Feature apply(Feature feature, Map<String, Double> changes) {
        if (changes.isEmpty()) return feature;
        changes.keySet().forEach(k -> dimension(feature, k));
        if (feature instanceof Feature.Extrude e)
            return new Feature.Extrude(e.id(), e.profile(), changes.getOrDefault("distance", e.distance()));
        if (feature instanceof Feature.EdgeFinish f)
            return new Feature.EdgeFinish(
                    f.id(), f.body(), f.kind(), changes.getOrDefault("size", f.size()), f.edges());
        if (feature instanceof Feature.Shell s)
            return new Feature.Shell(
                    s.id(), s.body(), changes.getOrDefault("thickness", s.thickness()), s.removedFace());
        if (feature instanceof Feature.SplitBody s)
            return new Feature.SplitBody(
                    s.id(),
                    s.body(),
                    new Feature.SplitPlane(
                            s.plane().normalX(),
                            s.plane().normalY(),
                            s.plane().normalZ(),
                            changes.getOrDefault("offset", s.plane().offset())));
        var s = (Feature.Sketch) feature;
        var plane =
                s.plane().withOffset(changes.getOrDefault("offsetZ", s.plane().offsetZ()));
        Feature.Profile profile = s.profile();
        if (profile instanceof Feature.Rectangle r)
            profile = new Feature.Rectangle(
                    changes.getOrDefault("x", r.x()),
                    changes.getOrDefault("y", r.y()),
                    changes.getOrDefault("width", r.width()),
                    changes.getOrDefault("height", r.height()));
        else if (profile instanceof Feature.Circle c)
            profile = new Feature.Circle(
                    changes.getOrDefault("centerX", c.centerX()),
                    changes.getOrDefault("centerY", c.centerY()),
                    changes.getOrDefault("diameter", c.diameter()));
        else if (profile instanceof Feature.Constrained c) {
            var next = c.geometry()
                    .withConstraints(c.geometry().constraints().stream()
                            .map(d -> changes.containsKey("constraint:" + d.id())
                                    ? d.withValue(changes.get("constraint:" + d.id()))
                                    : d)
                            .toList());
            profile = new Feature.Constrained(SketchSolver.solve(next).requireSolved());
        }
        return new Feature.Sketch(s.id(), s.profileId(), plane, profile);
    }

    public static Feature resolve(
            Feature feature, List<Feature> all, ParameterSet parameters, Map<String, Quantity<?>> values) {
        var changes = new LinkedHashMap<String, Double>();
        for (var binding : parameters.bindings()) {
            boolean own = binding.target().featureId().equals(feature.id());
            boolean sharedPlane = false;
            if (feature instanceof Feature.Sketch sketch
                    && binding.target().field().equals("offsetZ")) {
                sharedPlane = all.stream()
                        .anyMatch(f -> f.id().equals(binding.target().featureId())
                                && f instanceof Feature.Sketch other
                                && other.plane().id().equals(sketch.plane().id()));
            }
            if (!own && !sharedPlane) continue;
            if (own && FeatureText.isField(binding.target().field())) {
                FeatureText.field(feature, binding.target().field());
                continue;
            }
            var descriptor = dimension(feature, binding.target().field());
            double value = descriptor
                    .unit()
                    .modelValue(Expressions.evaluate(binding.expression(), descriptor.unit(), values::get));
            var previous = changes.put(descriptor.target().field(), value);
            if (previous != null && Math.abs(previous - value) > 1e-9)
                throw new IllegalArgumentException("Conflicting expressions for shared plane " + feature.id());
        }
        if (feature instanceof Feature.Sketch sketch && sketch.profile() instanceof Feature.Constrained profile) {
            var geometry = FeatureText.resolve(profile.geometry(), feature.id(), parameters, values);
            if (geometry != profile.geometry()) {
                if (changes.isEmpty()) geometry = SketchSolver.solve(geometry).requireSolved();
                feature = new Feature.Sketch(
                        sketch.id(), sketch.profileId(), sketch.plane(), new Feature.Constrained(geometry));
            }
        }
        return apply(feature, changes);
    }

    public static ParameterSet bind(CadDocument document, ParameterSet.Target target, String expression) {
        var feature = document.features().stream()
                .filter(f -> f.id().equals(target.featureId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Missing feature: " + target.featureId()));
        boolean text = FeatureText.isField(target.field());
        if (text) FeatureText.field(feature, target.field());
        else dimension(feature, target.field());
        var parameters = document.parameters();
        // One shared plane has one expression, regardless of which sketch is used to edit it.
        if (feature instanceof Feature.Sketch s && target.field().equals("offsetZ")) {
            for (var f : document.features())
                if (f instanceof Feature.Sketch other
                        && s.plane().id().equals(other.plane().id()))
                    parameters = parameters.bind(new ParameterSet.Target(f.id(), "offsetZ"), "");
        }
        var result = parameters.bind(target, expression);
        result.validate();
        if (!expression.isBlank()) {
            var configs = new ArrayList<String>();
            configs.add("");
            result.configurations().forEach(c -> configs.add(c.id()));
            for (var id : configs) {
                var values = result.values(id);
                if (text) FeatureText.validate(expression, values);
                else
                    Expressions.evaluate(
                            expression, dimension(feature, target.field()).unit(), values::get);
            }
        }
        return result;
    }

    public static void validateBindings(List<Feature> features, ParameterSet parameters) {
        parameters.validate();
        var configurations = new ArrayList<String>();
        configurations.add("");
        parameters.configurations().forEach(c -> configurations.add(c.id()));
        for (var configuration : configurations) {
            var values = parameters.values(configuration);
            for (var binding : parameters.bindings()) {
                var feature = features.stream()
                        .filter(f -> f.id().equals(binding.target().featureId()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Missing bound feature: " + binding.target()));
                try {
                    if (FeatureText.isField(binding.target().field())) {
                        FeatureText.field(feature, binding.target().field());
                        FeatureText.validate(binding.expression(), values);
                    } else {
                        var dimension = dimension(feature, binding.target().field());
                        Expressions.evaluate(binding.expression(), dimension.unit(), values::get);
                    }
                } catch (IllegalArgumentException failure) {
                    throw new IllegalArgumentException(binding.target() + ": " + failure.getMessage(), failure);
                }
            }
        }
    }

    public static ParameterSet retainSketchBindings(ParameterSet parameters, String id, SketchGeometry geometry) {
        var fields = new HashSet<String>();
        geometry.constraints().stream()
                .filter(c -> c.type().dimension())
                .forEach(c -> fields.add("constraint:" + c.id()));
        FeatureText.list(id, geometry).forEach(f -> fields.add(f.target().field()));
        return new ParameterSet(
                parameters.parameters(),
                parameters.configurations(),
                parameters.active(),
                parameters.bindings().stream()
                        .filter(b -> !b.target().featureId().equals(id)
                                || (!b.target().field().startsWith("constraint:")
                                        && !FeatureText.isField(b.target().field()))
                                || fields.contains(b.target().field()))
                        .toList(),
                parameters.uuidGenerator());
    }

    public static SketchGeometry resolveSketch(SketchGeometry geometry, String featureId, ParameterSet parameters) {
        var values = parameters.values();
        geometry = FeatureText.resolve(geometry, featureId, parameters, values);
        var changes = new LinkedHashMap<String, Double>();
        for (var b : parameters.bindings())
            if (b.target().featureId().equals(featureId) && b.target().field().startsWith("constraint:")) {
                String id = b.target().field().substring(11);
                var c = geometry.constraints().stream()
                        .filter(d -> d.id().equals(id) && d.type().dimension())
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Missing bound sketch dimension: " + id));
                changes.put(
                        id,
                        unit(c.type()).modelValue(Expressions.evaluate(b.expression(), unit(c.type()), values::get)));
            }
        return geometry.withConstraints(geometry.constraints().stream()
                .map(c -> changes.containsKey(c.id()) ? c.withValue(changes.get(c.id())) : c)
                .toList());
    }

    public record Converted(SketchGeometry geometry, ParameterSet parameters) {}

    public static Converted convert(Feature.Sketch sketch, ParameterSet parameters) {
        var g = Sketches.fromProfile(sketch.profile());
        if (sketch.profile() instanceof Feature.Constrained) return new Converted(g, parameters);
        boolean rectangle = sketch.profile() instanceof Feature.Rectangle;
        String x = rectangle ? "x" : "centerX", y = rectangle ? "y" : "centerY";
        if (!parameters.binding(new ParameterSet.Target(sketch.id(), x)).isEmpty()
                || !parameters.binding(new ParameterSet.Target(sketch.id(), y)).isEmpty()) {
            var point = rectangle
                    ? g.entity("rectangle-bottom").ref(SketchGeometry.Part.START)
                    : g.entity("circle").ref(SketchGeometry.Part.CENTER);
            var position = g.point(point);
            g = g.withConstraints(g.constraints().stream()
                    .filter(c -> !c.id().equals("anchor"))
                    .toList());
            g = g.add(SketchConstraints.create(
                    g, x, SketchGeometry.Type.DISTANCE_X, List.of(SketchGeometry.ORIGIN, point), position.x()));
            g = g.add(SketchConstraints.create(
                    g, y, SketchGeometry.Type.DISTANCE_Y, List.of(SketchGeometry.ORIGIN, point), position.y()));
        }
        var bindings = new ArrayList<ParameterSet.Binding>();
        for (var b : parameters.bindings()) {
            var target = b.target();
            if (target.featureId().equals(sketch.id()) && !target.field().equals("offsetZ"))
                target = new ParameterSet.Target(sketch.id(), "constraint:" + target.field());
            bindings.add(new ParameterSet.Binding(target, b.expression()));
        }
        return new Converted(
                g,
                new ParameterSet(
                        parameters.parameters(),
                        parameters.configurations(),
                        parameters.active(),
                        bindings,
                        parameters.uuidGenerator()));
    }
}
