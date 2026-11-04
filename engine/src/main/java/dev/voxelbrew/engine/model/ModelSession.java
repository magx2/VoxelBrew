package dev.voxelbrew.engine.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Both frontends submit these transactions. Undo/redo snapshots exclude the timeline cursor. */
public final class ModelSession {
    private final String id;
    private List<Feature> features;
    private int cursor;
    private long revision;
    private final Deque<List<Feature>> undo = new ArrayDeque<>();
    private final Deque<List<Feature>> redo = new ArrayDeque<>();

    public ModelSession(CadDocument document) {
        id = document.id(); features = document.features(); cursor = document.cursor();
    }
    public synchronized CadDocument document() { return new CadDocument(id, features, cursor); }
    public synchronized long revision() { return revision; }
    public synchronized void add(Feature feature) {
        if (cursor != features.size()) { throw new ModelException(feature.id(), "return the timeline cursor to the end before adding a feature"); }
        FeatureValidation.validate(feature);
        var next = new ArrayList<>(features);
        next.add(feature);
        commit(next);
        cursor = features.size();
    }
    public synchronized void replace(Feature feature) {
        FeatureValidation.validate(feature);
        var next = new ArrayList<>(features);
        next.set(index(feature.id()), feature);
        commit(next);
    }
    public synchronized void editDimension(String featureId, String field, double value) {
        Feature feature = features.get(index(featureId));
        if (feature instanceof Feature.Sketch selected && field.equals("offsetZ")) {
            var next = new ArrayList<>(features);
            for (int i = 0; i < next.size(); i++) {
                if (next.get(i) instanceof Feature.Sketch sketch && sketch.plane().id().equals(selected.plane().id())) {
                    var changed = new Feature.Sketch(sketch.id(), sketch.profileId(), new Feature.Plane(sketch.plane().id(), value), sketch.profile());
                    FeatureValidation.validate(changed);
                    next.set(i, changed);
                }
            }
            commit(next);
            return;
        }
        Feature replacement;
        if (feature instanceof Feature.Extrude e && field.equals("distance")) {
            replacement = new Feature.Extrude(e.id(), e.profile(), value);
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
            } else { throw new ModelException(featureId, "unsupported profile"); }
            replacement = new Feature.Sketch(s.id(), s.profileId(), plane, profile);
        } else { throw new ModelException(featureId, "unknown dimension '" + field + "'"); }
        replace(replacement);
    }
    public synchronized void setCursor(int newCursor) {
        new CadDocument(id, features, newCursor);
        cursor = newCursor; revision++;
    }
    public synchronized boolean undo() {
        if (undo.isEmpty()) { return false; }
        boolean followEnd = cursor == features.size();
        redo.push(features); features = undo.pop(); cursor = followEnd ? features.size() : Math.min(cursor, features.size()); revision++; return true;
    }
    public synchronized boolean redo() {
        if (redo.isEmpty()) { return false; }
        boolean followEnd = cursor == features.size();
        undo.push(features); features = redo.pop(); cursor = followEnd ? features.size() : Math.min(cursor, features.size()); revision++; return true;
    }
    public Evaluation evaluate() { return Evaluator.evaluate(document()); }
    private int index(String featureId) {
        for (int i = 0; i < features.size(); i++) { if (features.get(i).id().equals(featureId)) { return i; } }
        throw new ModelException(featureId, "feature does not exist");
    }
    private void commit(List<Feature> next) {
        List<Feature> validated = new CadDocument(id, next, Math.min(cursor, next.size())).features();
        if (validated.equals(features)) { return; }
        undo.push(features); redo.clear(); features = validated; revision++;
    }
}
