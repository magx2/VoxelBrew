package dev.voxelbrew.engine.model;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import lombok.NonNull;

/** Immutable millimeter document. The cursor counts evaluated features. */
public record CadDocument(
        String id,
        List<Feature> features,
        int cursor,
        @NonNull dev.voxelbrew.engine.printing.PrintSetup printSetup,
        @NonNull dev.voxelbrew.engine.parameters.ParameterSet parameters,
        Map<String, BodyStyle> bodyStyles,
        Map<String, String> bodyNames,
        Map<String, String> featureNames,
        Map<String, List<String>> gcode,
        Map<String, dev.voxelbrew.engine.printing.PrintArtifact> printArtifacts) {
    public CadDocument(
            String id,
            List<Feature> features,
            int cursor,
            dev.voxelbrew.engine.printing.PrintSetup printSetup,
            dev.voxelbrew.engine.parameters.ParameterSet parameters,
            Map<String, BodyStyle> bodyStyles,
            Map<String, String> bodyNames,
            Map<String, String> featureNames,
            Map<String, List<String>> gcode) {
        this(id, features, cursor, printSetup, parameters, bodyStyles, bodyNames, featureNames, gcode, Map.of());
    }

    public CadDocument withPrintArtifacts(Map<String, dev.voxelbrew.engine.printing.PrintArtifact> artifacts) {
        return new CadDocument(
                id, features, cursor, printSetup, parameters, bodyStyles, bodyNames, featureNames, gcode, artifacts);
    }

    public CadDocument(
            String id,
            List<Feature> features,
            int cursor,
            dev.voxelbrew.engine.printing.PrintSetup printSetup,
            dev.voxelbrew.engine.parameters.ParameterSet parameters,
            Map<String, BodyStyle> bodyStyles,
            Map<String, String> bodyNames,
            Map<String, String> featureNames) {
        this(id, features, cursor, printSetup, parameters, bodyStyles, bodyNames, featureNames, Map.of());
    }

    /** Complete UTF-8 jobs keyed by plate, with one element per source line. */
    public CadDocument withGcode(Map<String, List<String>> jobs) {
        return new CadDocument(id, features, cursor, printSetup, parameters, bodyStyles, bodyNames, featureNames, jobs);
    }

    public List<String> activeGcode() {
        return gcode.getOrDefault(printSetup.activePlateId(), List.of());
    }

    public CadDocument(
            String id,
            List<Feature> features,
            int cursor,
            dev.voxelbrew.engine.printing.PrintSetup printSetup,
            dev.voxelbrew.engine.parameters.ParameterSet parameters,
            Map<String, BodyStyle> bodyStyles,
            Map<String, String> bodyNames) {
        this(id, features, cursor, printSetup, parameters, bodyStyles, bodyNames, Map.of());
    }

    public CadDocument(
            String id,
            List<Feature> features,
            int cursor,
            dev.voxelbrew.engine.printing.PrintSetup printSetup,
            dev.voxelbrew.engine.parameters.ParameterSet parameters,
            Map<String, BodyStyle> bodyStyles) {
        this(id, features, cursor, printSetup, parameters, bodyStyles, Map.of());
    }

    public CadDocument(
            String id,
            List<Feature> features,
            int cursor,
            dev.voxelbrew.engine.printing.PrintSetup printSetup,
            dev.voxelbrew.engine.parameters.ParameterSet parameters) {
        this(id, features, cursor, printSetup, parameters, Map.of());
    }

    public CadDocument(
            String id, List<Feature> features, int cursor, dev.voxelbrew.engine.printing.PrintSetup printSetup) {
        this(id, features, cursor, printSetup, dev.voxelbrew.engine.parameters.ParameterSet.empty());
    }

    public CadDocument(String id, List<Feature> features, int cursor) {
        this(id, features, cursor, dev.voxelbrew.engine.printing.PrintSetup.empty());
    }

    public CadDocument {
        printArtifacts = Map.copyOf(printArtifacts);
        printArtifacts.keySet().forEach(plate -> identifier(plate, "Print artifact plate ID"));
        var jobs = new java.util.TreeMap<String, List<String>>();
        gcode.forEach((plate, lines) -> {
            identifier(plate, "G-code plate ID");
            var copy = List.copyOf(lines);
            if (copy.stream().anyMatch(line -> line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0))
                throw new IllegalArgumentException("Each G-code element must contain one line");
            if (!copy.isEmpty()) jobs.put(plate, copy);
        });
        gcode = Map.copyOf(jobs);

        bodyStyles = Map.copyOf(bodyStyles);
        bodyStyles.keySet().forEach(BodyStyle::requireBodyId);
        bodyNames = Map.copyOf(bodyNames);
        bodyNames.keySet().forEach(BodyStyle::requireBodyId);
        bodyNames.values().forEach(CadDocument::requireBodyName);
        featureNames = Map.copyOf(featureNames);
        featureNames.keySet().forEach(key -> identifier(key, "feature ID"));
        featureNames.values().forEach(CadDocument::requireBodyName);
        identifier(id, "document ID");
        features = List.copyOf(features);
        var ids = new HashSet<String>();
        for (Feature feature : features) {
            identifier(feature.id(), "feature ID");
            if (!ids.add(feature.id())) {
                throw new ModelException(feature.id(), "duplicate feature ID");
            }
        }
        if (cursor < 0 || cursor > features.size()) {
            throw new IllegalArgumentException("cursor must be between 0 and " + features.size());
        }
    }

    public dev.voxelbrew.engine.model.UuidGenerator uuidGenerator() {
        return parameters.uuidGenerator();
    }

    public static CadDocument empty() {
        var ids = new dev.voxelbrew.engine.model.UuidGenerator();
        return new CadDocument(
                Long.toString(ids.next()),
                List.of(),
                0,
                dev.voxelbrew.engine.printing.PrintSetup.empty(),
                dev.voxelbrew.engine.parameters.ParameterSet.empty().withUuidGenerator(ids));
    }

    /** Display names never replace stable body identities or feature references. */
    public String bodyName(String bodyId, String fallback) {
        return bodyNames.getOrDefault(bodyId, fallback);
    }

    public String featureName(String featureId, String fallback) {
        return featureNames.getOrDefault(featureId, fallback);
    }

    public static void requireBodyName(String name) {
        if (name == null
                || name.isBlank()
                || name.length() > 128
                || name.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Body name must contain 1–128 characters and no control characters");
    }

    /**
     * Explicit assignments win; split children inherit from their source, including through cuts.
     */
    public BodyStyle bodyStyle(String bodyId) {
        return bodyStyle(bodyId, new HashSet<>());
    }

    private BodyStyle bodyStyle(String bodyId, java.util.Set<String> visited) {
        var assigned = bodyStyles.get(bodyId);
        if (assigned != null) return assigned;
        if (!visited.add(bodyId)) return BodyStyle.DEFAULT;
        for (var feature : features) {
            if (feature instanceof Feature.SplitBody split
                    && (bodyId.equals(split.id() + ".negative") || bodyId.equals(split.id() + ".positive"))) {
                return outputStyle(split.body(), visited);
            }
        }
        return BodyStyle.DEFAULT;
    }

    private BodyStyle outputStyle(Feature.BodyRef output, java.util.Set<String> visited) {
        for (var feature : features) {
            if (feature.id().equals(output.featureId())) {
                if (!visited.add("output:" + output.featureId())) return BodyStyle.DEFAULT;
                if (feature instanceof Feature.ThroughCut cut) return outputStyle(cut.body(), visited);
                if (feature instanceof Feature.EdgeFinish finish) return outputStyle(finish.body(), visited);
                if (feature instanceof Feature.Shell shell) return outputStyle(shell.body(), visited);
            }
        }
        return bodyStyle(output.featureId() + "." + output.outputName(), visited);
    }

    public static void identifier(String id, String field) {
        if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")) {
            throw new IllegalArgumentException(
                    field + " must contain 1–128 letters, digits, dots, underscores or hyphens");
        }
    }
}
