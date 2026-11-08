package dev.voxelbrew.engine.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Both frontends submit these transactions. Undo/redo snapshots exclude the timeline cursor. */
@lombok.extern.slf4j.Slf4j
public final class ModelSession {
    private final String id;
    private final dev.voxelbrew.util.UuidGenerator uuidGenerator;
    private List<Feature> features;
    private dev.voxelbrew.engine.printing.PrintSetup printSetup;
    private dev.voxelbrew.engine.parameters.ParameterSet parameters;
    private java.util.Map<String, BodyStyle> bodyStyles;
    private java.util.Map<String, String> bodyNames;
    private java.util.Map<String, String> featureNames;
    private int cursor;
    private long revision;

    private record State(
            List<Feature> features,
            dev.voxelbrew.engine.printing.PrintSetup printSetup,
            dev.voxelbrew.engine.parameters.ParameterSet parameters,
            java.util.Map<String, BodyStyle> bodyStyles,
            java.util.Map<String, String> bodyNames,
            java.util.Map<String, String> featureNames) {}

    private final Deque<State> undo = new ArrayDeque<>();
    private final Deque<State> redo = new ArrayDeque<>();

    public ModelSession(CadDocument document) {
        id = document.id();
        uuidGenerator = document.uuidGenerator();
        features = document.features();
        printSetup = document.printSetup();
        parameters = document.parameters();
        bodyStyles = document.bodyStyles();
        bodyNames = document.bodyNames();
        featureNames = document.featureNames();
        cursor = document.cursor();
    }

    public synchronized CadDocument document() {
        return new CadDocument(id, features, cursor, printSetup, parameters, bodyStyles, bodyNames, featureNames);
    }

    public synchronized long revision() {
        return revision;
    }

    public record Snapshot(CadDocument document, long revision, boolean canUndo, boolean canRedo) {}

    public synchronized Snapshot snapshot() {
        return new Snapshot(document(), revision, !undo.isEmpty(), !redo.isEmpty());
    }

    public synchronized boolean canUndo() {
        return !undo.isEmpty();
    }

    public synchronized boolean canRedo() {
        return !redo.isEmpty();
    }

    public synchronized void add(Feature feature) {
        add(feature, parameters);
    }

    public synchronized void add(Feature feature, dev.voxelbrew.engine.parameters.ParameterSet expressions) {
        if (cursor != features.size()) {
            throw new ModelException(feature.id(), "return the timeline cursor to the end before adding a feature");
        }
        FeatureValidation.validate(feature);
        var next = new ArrayList<>(features);
        next.add(feature);
        expressions.validate();
        commit(next, printSetup, expressions);
        cursor = features.size();
    }

    public synchronized void replace(Feature feature) {
        replace(feature, parameters);
    }

    public synchronized void replace(Feature feature, dev.voxelbrew.engine.parameters.ParameterSet expressions) {
        FeatureValidation.validate(feature);
        var next = new ArrayList<>(features);
        next.set(index(feature.id()), feature);
        expressions.validate();
        commit(next, printSetup, expressions);
    }

    /** Deletes a range in one undoable transaction, preserving repairable surviving references. */
    public synchronized void deleteFeatures(int from, int to) {
        java.util.Objects.checkFromToIndex(from, to, features.size());
        if (from == to) return;
        int nextCursor = cursor - Math.max(0, Math.min(cursor, to) - from);
        var removed = features.subList(from, to).stream().map(Feature::id).collect(java.util.stream.Collectors.toSet());
        var next = new ArrayList<>(features);
        next.subList(from, to).clear();
        var expressions = new dev.voxelbrew.engine.parameters.ParameterSet(
                parameters.parameters(),
                parameters.configurations(),
                parameters.active(),
                parameters.bindings().stream()
                        .filter(b -> !removed.contains(b.target().featureId()))
                        .toList());
        commit(next, printSetup, expressions);
        cursor = nextCursor;
    }

    public synchronized void setParameters(dev.voxelbrew.engine.parameters.ParameterSet next) {
        dev.voxelbrew.engine.parameters.FeatureDimensions.validateBindings(features, next);
        commit(features, printSetup, next);
    }

    public synchronized void bindExpression(String featureId, String field, String expression) {
        setParameters(dev.voxelbrew.engine.parameters.FeatureDimensions.bind(
                document(), new dev.voxelbrew.engine.parameters.ParameterSet.Target(featureId, field), expression));
    }

    public synchronized void selectConfiguration(String nameOrId) {
        setParameters(parameters.select(nameOrId));
    }

    public synchronized void editDimension(String featureId, String field, double value) {
        editDimension(featureId, field, value, dev.voxelbrew.engine.feature.FeatureRegistry.defaultDirectory());
    }

    /** Converts a JSR 385 quantity before the existing validated numeric edit transaction. */
    public synchronized void editDimension(String featureId, String field, javax.measure.Quantity<?> value) {
        var feature = features.get(index(featureId));
        String target = feature instanceof Feature.Sketch s
                        && s.profile() instanceof Feature.Constrained
                        && !field.equals("offsetZ")
                        && !field.startsWith("constraint:")
                ? "constraint:" + field
                : field;
        var dimension = dev.voxelbrew.engine.parameters.FeatureDimensions.dimension(feature, target);
        editDimension(
                featureId, field, dev.voxelbrew.engine.units.Measurements.value(value, dimension.measurementUnit()));
    }

    public synchronized void editDimension(
            String featureId, String field, double value, java.nio.file.Path pluginDirectory) {
        Feature feature = features.get(index(featureId));
        if (feature instanceof Feature.Custom custom) {
            try (var registry = new dev.voxelbrew.engine.feature.FeatureRegistry(pluginDirectory)) {
                replace(registry.edit(custom, java.util.Map.of(field, value)));
            } catch (java.io.IOException failure) {
                throw new ModelException(featureId, "Cannot load feature plugins: " + failure.getMessage());
            }
            return;
        }
        if (feature instanceof Feature.Sketch selected && field.equals("offsetZ")) {
            var next = new ArrayList<>(features);
            for (int i = 0; i < next.size(); i++) {
                if (next.get(i) instanceof Feature.Sketch sketch
                        && sketch.plane().id().equals(selected.plane().id())) {
                    var changed = new Feature.Sketch(
                            sketch.id(), sketch.profileId(), sketch.plane().withOffset(value), sketch.profile());
                    FeatureValidation.validate(changed);
                    next.set(i, changed);
                }
            }
            var unbound = parameters;
            for (var candidate : next)
                if (candidate instanceof Feature.Sketch s
                        && s.plane().id().equals(selected.plane().id()))
                    unbound = unbound.bind(new dev.voxelbrew.engine.parameters.ParameterSet.Target(s.id(), field), "");
            commit(next, printSetup, unbound);
            return;
        }
        Feature replacement;
        if (feature instanceof Feature.Extrude e && field.equals("distance")) {
            replacement = new Feature.Extrude(e.id(), e.profile(), value);
        } else if (feature instanceof Feature.EdgeFinish f && field.equals("size")) {
            replacement = new Feature.EdgeFinish(f.id(), f.body(), f.kind(), value, f.edges());
        } else if (feature instanceof Feature.Shell s && field.equals("thickness")) {
            replacement = new Feature.Shell(s.id(), s.body(), value, s.removedFace());
        } else if (feature instanceof Feature.SplitBody s && field.equals("offset")) {
            var p = s.plane();
            replacement = new Feature.SplitBody(
                    s.id(), s.body(), new Feature.SplitPlane(p.normalX(), p.normalY(), p.normalZ(), value));
        } else if (feature instanceof Feature.Sketch s) {
            Feature.Profile profile = s.profile();
            Feature.Plane plane = s.plane();
            if (profile instanceof Feature.Rectangle r) {
                profile = switch (field) {
                    case "x" -> new Feature.Rectangle(value, r.y(), r.width(), r.height());
                    case "y" -> new Feature.Rectangle(r.x(), value, r.width(), r.height());
                    case "width" -> new Feature.Rectangle(r.x(), r.y(), value, r.height());
                    case "height" -> new Feature.Rectangle(r.x(), r.y(), r.width(), value);
                    default -> throw new ModelException(featureId, "unknown rectangle dimension '" + field + "'");
                };
            } else if (profile instanceof Feature.Circle c) {
                profile = switch (field) {
                    case "centerX" -> new Feature.Circle(value, c.centerY(), c.diameter());
                    case "centerY" -> new Feature.Circle(c.centerX(), value, c.diameter());
                    case "diameter" -> new Feature.Circle(c.centerX(), c.centerY(), value);
                    default -> throw new ModelException(featureId, "unknown circle dimension '" + field + "'");
                };
            } else if (profile instanceof Feature.Constrained c) {
                String constraintId = field.startsWith("constraint:") ? field.substring(11) : field;
                profile = new Feature.Constrained(
                        dev.voxelbrew.engine.sketch.Sketches.editDimension(c.geometry(), constraintId, value));
            } else {
                throw new ModelException(featureId, "unsupported profile");
            }
            replacement = new Feature.Sketch(s.id(), s.profileId(), plane, profile);
        } else {
            throw new ModelException(featureId, "unknown dimension '" + field + "'");
        }
        var unbound = parameters.bind(new dev.voxelbrew.engine.parameters.ParameterSet.Target(featureId, field), "");
        if (feature instanceof Feature.Sketch s
                && s.profile() instanceof Feature.Constrained
                && !field.startsWith("constraint:"))
            unbound = unbound.bind(
                    new dev.voxelbrew.engine.parameters.ParameterSet.Target(featureId, "constraint:" + field), "");
        replace(replacement, unbound);
    }

    public synchronized void setCursor(int newCursor) {
        new CadDocument(id, features, newCursor);
        cursor = newCursor;
        revision++;
        log.debug("Document {} moved to cursor {} at revision {}", id, cursor, revision);
    }

    public synchronized boolean undo() {
        if (undo.isEmpty()) {
            return false;
        }
        boolean followEnd = cursor == features.size();
        redo.push(new State(features, printSetup, parameters, bodyStyles, bodyNames, featureNames));
        var previous = undo.pop();
        log.debug("Undo in document {} from revision {}", id, revision);
        features = previous.features();
        printSetup = previous.printSetup();
        parameters = previous.parameters();
        bodyStyles = previous.bodyStyles();
        bodyNames = previous.bodyNames();
        featureNames = previous.featureNames();
        cursor = followEnd ? features.size() : Math.min(cursor, features.size());
        revision++;
        return true;
    }

    public synchronized boolean redo() {
        if (redo.isEmpty()) {
            return false;
        }
        boolean followEnd = cursor == features.size();
        undo.push(new State(features, printSetup, parameters, bodyStyles, bodyNames, featureNames));
        var next = redo.pop();
        log.debug("Redo in document {} from revision {}", id, revision);
        features = next.features();
        printSetup = next.printSetup();
        parameters = next.parameters();
        bodyStyles = next.bodyStyles();
        bodyNames = next.bodyNames();
        featureNames = next.featureNames();
        cursor = followEnd ? features.size() : Math.min(cursor, features.size());
        revision++;
        return true;
    }

    public Evaluation evaluate() {
        return Evaluator.evaluate(document());
    }

    public Evaluation evaluate(java.nio.file.Path pluginDirectory) {
        return Evaluator.evaluate(document(), pluginDirectory);
    }

    private int index(String featureId) {
        for (int i = 0; i < features.size(); i++) {
            if (features.get(i).id().equals(featureId)) {
                return i;
            }
        }
        throw new ModelException(featureId, "feature does not exist");
    }

    private void commit(List<Feature> next) {
        commit(next, printSetup);
    }

    public synchronized void setPrintSetup(dev.voxelbrew.engine.printing.PrintSetup setup) {
        commit(features, setup);
    }

    private void commit(List<Feature> next, dev.voxelbrew.engine.printing.PrintSetup setup) {
        commit(next, setup, parameters);
    }

    private void commit(
            List<Feature> next,
            dev.voxelbrew.engine.printing.PrintSetup setup,
            dev.voxelbrew.engine.parameters.ParameterSet expressions) {
        commit(next, setup, expressions, bodyStyles);
    }

    /** Null removes the override and restores the default or inherited split-body assignment. */
    public synchronized void setBodyStyle(String bodyId, BodyStyle style) {
        BodyStyle.requireBodyId(bodyId);
        var next = new java.util.HashMap<>(bodyStyles);
        if (style == null) next.remove(bodyId);
        else next.put(bodyId, style);
        commit(features, printSetup, parameters, next);
        log.debug("Updated material and color for body {} in document {}", bodyId, id);
    }

    private void commit(
            List<Feature> next,
            dev.voxelbrew.engine.printing.PrintSetup setup,
            dev.voxelbrew.engine.parameters.ParameterSet expressions,
            java.util.Map<String, BodyStyle> styles) {
        commit(next, setup, expressions, styles, bodyNames);
    }

    /** Null restores the generated label; assignments are one undoable document transaction. */
    public synchronized void renameBody(String bodyId, String name) {
        BodyStyle.requireBodyId(bodyId);
        if (name != null) {
            CadDocument.requireBodyName(name);
            name = name.strip();
        }
        var next = new java.util.HashMap<>(bodyNames);
        if (name == null) next.remove(bodyId);
        else next.put(bodyId, name);
        commit(features, printSetup, parameters, bodyStyles, next);
        log.debug("Updated display name for body {} in document {}", bodyId, id);
    }

    public synchronized void renameFeature(String featureId, String name) {
        if (features.stream().noneMatch(feature -> feature.id().equals(featureId)))
            throw new IllegalArgumentException("Unknown feature: " + featureId);
        if (name != null) {
            CadDocument.requireBodyName(name);
            name = name.strip();
        }
        var next = new java.util.HashMap<>(featureNames);
        if (name == null) next.remove(featureId);
        else next.put(featureId, name);
        if (next.equals(featureNames)) return;
        undo.push(new State(features, printSetup, parameters, bodyStyles, bodyNames, featureNames));
        redo.clear();
        featureNames = java.util.Map.copyOf(next);
        revision++;
    }

    private void commit(
            List<Feature> next,
            dev.voxelbrew.engine.printing.PrintSetup setup,
            dev.voxelbrew.engine.parameters.ParameterSet expressions,
            java.util.Map<String, BodyStyle> styles,
            java.util.Map<String, String> names) {
        List<Feature> validated =
                new CadDocument(id, next, Math.min(cursor, next.size()), setup, expressions, styles, names).features();
        if (validated.equals(features)
                && setup.equals(printSetup)
                && expressions.equals(parameters)
                && styles.equals(bodyStyles)
                && names.equals(bodyNames)) {
            return;
        }
        undo.push(new State(features, printSetup, parameters, bodyStyles, bodyNames, featureNames));
        redo.clear();
        features = validated;
        printSetup = setup;
        parameters = expressions.withUuidGenerator(uuidGenerator);
        bodyStyles = java.util.Map.copyOf(styles);
        bodyNames = java.util.Map.copyOf(names);
        revision++;
        log.debug("Committed document {} revision {} with {} features", id, revision, features.size());
    }
}
