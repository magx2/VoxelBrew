package dev.voxelbrew.engine.parameters;

import dev.voxelbrew.engine.model.Feature;
import dev.voxelbrew.engine.sketch.SketchGeometry;
import dev.voxelbrew.engine.sketch.SketchText;
import java.util.*;
import javax.measure.Quantity;

/** Persistent text formulas share the existing parameter binding schema and stable entity IDs. */
public final class FeatureText {
    private FeatureText() {}

    public record Field(ParameterSet.Target target, String value) {}

    public static String key(String entityId, String property) {
        if (!Set.of("content", "font").contains(property))
            throw new IllegalArgumentException("Unknown text property: " + property);
        return "text:" + entityId + ":" + property;
    }

    public static boolean isField(String field) {
        return field.startsWith("text:");
    }

    public static List<Field> list(Feature feature) {
        if (feature instanceof Feature.Sketch sketch && sketch.profile() instanceof Feature.Constrained profile)
            return list(sketch.id(), profile.geometry());
        return List.of();
    }

    public static List<Field> list(String featureId, SketchGeometry geometry) {
        var result = new ArrayList<Field>();
        for (var entity : geometry.entities()) {
            if (entity.kind() != SketchGeometry.Kind.TEXT) continue;
            result.add(new Field(
                    new ParameterSet.Target(featureId, key(entity.id(), "content")),
                    entity.text().content()));
            result.add(new Field(
                    new ParameterSet.Target(featureId, key(entity.id(), "font")),
                    entity.text().font()));
        }
        return List.copyOf(result);
    }

    public static Field field(Feature feature, String name) {
        return list(feature).stream()
                .filter(f -> f.target().field().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown text field: " + feature.id() + "/" + name));
    }

    public static void validate(String expression, Map<String, Quantity<?>> values) {
        if (!Expressions.isFormula(expression))
            throw new IllegalArgumentException("A text formula must start with '='");
        Expressions.evaluateText(expression, values::get);
    }

    public static SketchGeometry resolve(
            SketchGeometry geometry, String featureId, ParameterSet parameters, Map<String, Quantity<?>> values) {
        var available = new HashSet<String>();
        list(featureId, geometry).forEach(f -> available.add(f.target().field()));
        for (var binding : parameters.bindings()) {
            if (binding.target().featureId().equals(featureId)
                    && isField(binding.target().field())) {
                if (!available.contains(binding.target().field()))
                    throw new IllegalArgumentException("Missing bound sketch text: " + binding.target());
                validate(binding.expression(), values);
            }
        }
        var entities = new ArrayList<SketchGeometry.Entity>();
        for (var entity : geometry.entities()) {
            if (entity.kind() == SketchGeometry.Kind.TEXT) {
                String content = value(
                        parameters,
                        values,
                        featureId,
                        entity.id(),
                        "content",
                        entity.text().content());
                String font = value(
                        parameters,
                        values,
                        featureId,
                        entity.id(),
                        "font",
                        entity.text().font());
                // Reuse the persisted outlines when the text is unchanged, including offline fonts.
                if (!content.equals(entity.text().content())
                        || !font.equals(entity.text().font()))
                    entity = SketchText.create(
                            entity.id(),
                            content,
                            font,
                            entity.text().style(),
                            entity.value(0),
                            entity.value(1),
                            entity.value(2),
                            entity.value(3),
                            entity.value(4),
                            entity.text().pathId(),
                            entity.construction());
            }
            entities.add(entity);
        }
        return entities.equals(geometry.entities()) ? geometry : geometry.withEntities(entities);
    }

    private static String value(
            ParameterSet parameters,
            Map<String, Quantity<?>> values,
            String featureId,
            String entityId,
            String property,
            String fallback) {
        String expression = parameters.binding(new ParameterSet.Target(featureId, key(entityId, property)));
        return expression.isEmpty() ? fallback : Expressions.evaluateText(expression, values::get);
    }
}
