package dev.voxelbrew.engine.parameters;

import dev.voxelbrew.engine.model.CadDocument;
import java.util.*;
import javax.measure.Quantity;

/** Persistent named dimensions, formula bindings and independently editable variant overrides. */
public record ParameterSet(
        List<Parameter> parameters,
        List<Configuration> configurations,
        String active,
        List<Binding> bindings,
        dev.voxelbrew.util.UuidGenerator uuidGenerator) {
    public record Parameter(String id, String name, ParameterUnit unit, String expression, String comment) {
        public Parameter {
            CadDocument.identifier(id, "parameter ID");
            requireName(name);
            Objects.requireNonNull(unit);
            requireExpression(expression);
            if (comment == null || comment.length() > 1024)
                throw new IllegalArgumentException("Comment is limited to 1024 characters");
        }

        @Override
        public String toString() {
            return name;
        }
    }

    public record Configuration(String id, String name, Map<String, String> overrides) {
        public Configuration {
            CadDocument.identifier(id, "configuration ID");
            if (name == null || name.isBlank() || name.length() > 80 || name.equalsIgnoreCase("Default"))
                throw new IllegalArgumentException(
                        "Configuration needs a unique name (1–80 characters, other than Default)");
            overrides = Collections.unmodifiableMap(new LinkedHashMap<>(overrides));
            overrides.values().forEach(ParameterSet::requireExpression);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    public record Target(String featureId, String field) {
        public Target {
            CadDocument.identifier(featureId, "bound feature ID");
            if (field == null || !field.matches("[A-Za-z0-9_.:-]{1,150}"))
                throw new IllegalArgumentException("Invalid dimension field");
        }

        @Override
        public String toString() {
            return featureId + "/" + field;
        }
    }

    public record Binding(Target target, String expression) {
        public Binding {
            Objects.requireNonNull(target);
            requireExpression(expression);
        }
    }

    public ParameterSet(
            List<Parameter> parameters, List<Configuration> configurations, String active, List<Binding> bindings) {
        this(parameters, configurations, active, bindings, new dev.voxelbrew.util.UuidGenerator());
    }

    public ParameterSet withUuidGenerator(dev.voxelbrew.util.UuidGenerator generator) {
        return new ParameterSet(parameters, configurations, active, bindings, generator);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ParameterSet p
                && parameters.equals(p.parameters)
                && configurations.equals(p.configurations)
                && active.equals(p.active)
                && bindings.equals(p.bindings);
    }

    @Override
    public int hashCode() {
        return Objects.hash(parameters, configurations, active, bindings);
    }

    public ParameterSet {
        Objects.requireNonNull(uuidGenerator);
        parameters = List.copyOf(parameters);
        configurations = List.copyOf(configurations);
        bindings = List.copyOf(bindings);
        Objects.requireNonNull(active);
        if (parameters.size() > 128 || configurations.size() > 32 || bindings.size() > 2048)
            throw new IllegalArgumentException("Limit: 128 parameters, 32 configurations and 2048 bound dimensions");
        Set<String> ids = new HashSet<>(), names = new HashSet<>();
        for (var p : parameters)
            if (!ids.add(p.id()) || !names.add(p.name()))
                throw new IllegalArgumentException("Duplicate parameter ID/name: " + p.name());
        var configurationIds = new HashSet<String>();
        names.clear();
        for (var c : configurations) {
            if (!configurationIds.add(c.id()) || !names.add(c.name().toLowerCase(Locale.ROOT)))
                throw new IllegalArgumentException("Duplicate configuration: " + c.name());
            if (!ids.containsAll(c.overrides().keySet()))
                throw new IllegalArgumentException("Configuration overrides a missing parameter");
        }
        if (!active.isEmpty() && !configurationIds.contains(active))
            throw new IllegalArgumentException("Missing active configuration: " + active);
        var targets = new HashSet<Target>();
        for (var b : bindings)
            if (!targets.add(b.target())) throw new IllegalArgumentException("Duplicate binding: " + b.target());
    }

    public static ParameterSet empty() {
        return new ParameterSet(List.of(), List.of(), "", List.of());
    }

    public static void requireName(String name) {
        if (name == null
                || !name.matches("[A-Za-z][A-Za-z_.]{0,63}")
                || name.startsWith("vb.")
                || Expressions.reserved(name))
            throw new IllegalArgumentException(
                    "Parameter name must start with an ASCII letter, contain at most 64 ASCII letters/underscores/dots, and not start with vb. (reserved for built-in parameters) or be a unit or function");
    }

    private static void requireExpression(String expression) {
        if (expression == null || expression.isBlank() || expression.length() > 512)
            throw new IllegalArgumentException("Expression must contain 1–512 characters");
    }

    public Parameter parameter(String nameOrId) {
        return parameters.stream()
                .filter(p -> p.id().equals(nameOrId) || p.name().equals(nameOrId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown parameter: " + nameOrId));
    }

    public String configurationId(String nameOrId) {
        if (nameOrId.isEmpty() || nameOrId.equalsIgnoreCase("Default")) return "";
        return configurations.stream()
                .filter(c -> c.id().equals(nameOrId) || c.name().equals(nameOrId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown configuration: " + nameOrId))
                .id();
    }

    public String activeName() {
        return active.isEmpty()
                ? "Default"
                : configurations.stream()
                        .filter(c -> c.id().equals(active))
                        .findFirst()
                        .orElseThrow()
                        .name();
    }

    public String expression(Parameter parameter, String configurationId) {
        if (configurationId.isEmpty()) return parameter.expression();
        return configurations.stream()
                .filter(c -> c.id().equals(configurationId))
                .findFirst()
                .orElseThrow()
                .overrides()
                .getOrDefault(parameter.id(), parameter.expression());
    }

    public String binding(Target target) {
        return bindings.stream()
                .filter(b -> b.target().equals(target))
                .map(Binding::expression)
                .findFirst()
                .orElse("");
    }

    public ParameterSet select(String nameOrId) {
        return new ParameterSet(parameters, configurations, configurationId(nameOrId), bindings, uuidGenerator);
    }

    public ParameterSet bind(Target target, String expression) {
        var next = new ArrayList<>(
                bindings.stream().filter(b -> !b.target().equals(target)).toList());
        if (!expression.isBlank()) next.add(new Binding(target, expression.strip()));
        return new ParameterSet(parameters, configurations, active, next, uuidGenerator);
    }

    public ParameterSet put(String name, ParameterUnit unit, String expression, String comment) {
        var existing = parameters.stream().filter(p -> p.name().equals(name)).findFirst();
        if (existing.isPresent()) {
            var old = existing.get();
            var next = new ArrayList<>(parameters);
            next.set(next.indexOf(old), new Parameter(old.id(), name, unit, old.expression(), comment));
            return new ParameterSet(next, configurations, active, bindings, uuidGenerator)
                    .override(name, active, expression);
        }
        var next = new ArrayList<>(parameters);
        next.add(new Parameter("parameter-" + uuidGenerator.next(), name, unit, expression.strip(), comment));
        return new ParameterSet(next, configurations, active, bindings, uuidGenerator);
    }

    public ParameterSet override(String nameOrId, String configuration, String expression) {
        var p = parameter(nameOrId);
        String id = configurationId(configuration);
        if (id.isEmpty())
            return new ParameterSet(
                    parameters.stream()
                            .map(v -> v.id().equals(p.id())
                                    ? new Parameter(v.id(), v.name(), v.unit(), expression.strip(), v.comment())
                                    : v)
                            .toList(),
                    configurations,
                    active,
                    bindings,
                    uuidGenerator);
        return new ParameterSet(
                parameters,
                configurations.stream()
                        .map(c -> {
                            if (!c.id().equals(id)) return c;
                            var values = new LinkedHashMap<>(c.overrides());
                            if (expression.isBlank()) values.remove(p.id());
                            else values.put(p.id(), expression.strip());
                            return new Configuration(c.id(), c.name(), values);
                        })
                        .toList(),
                active,
                bindings,
                uuidGenerator);
    }

    public ParameterSet rename(String nameOrId, String nextName) {
        requireName(nextName);
        var old = parameter(nameOrId);
        var renamed = parameters.stream()
                .map(p -> new Parameter(
                        p.id(),
                        p.id().equals(old.id()) ? nextName : p.name(),
                        p.unit(),
                        Expressions.rename(p.expression(), old.name(), nextName),
                        p.comment()))
                .toList();
        var configs = configurations.stream()
                .map(c -> {
                    var values = new LinkedHashMap<String, String>();
                    c.overrides()
                            .forEach((id, value) -> values.put(id, Expressions.rename(value, old.name(), nextName)));
                    return new Configuration(c.id(), c.name(), values);
                })
                .toList();
        return new ParameterSet(
                renamed,
                configs,
                active,
                bindings.stream()
                        .map(b -> new Binding(b.target(), Expressions.rename(b.expression(), old.name(), nextName)))
                        .toList(),
                uuidGenerator);
    }

    public ParameterSet remove(String nameOrId) {
        var removed = parameter(nameOrId);
        var configs = configurations.stream()
                .map(c -> {
                    var overrides = new LinkedHashMap<>(c.overrides());
                    overrides.remove(removed.id());
                    return new Configuration(c.id(), c.name(), overrides);
                })
                .toList();
        var result = new ParameterSet(
                parameters.stream().filter(p -> !p.id().equals(removed.id())).toList(),
                configs,
                active,
                bindings,
                uuidGenerator);
        result.validate();
        return result;
    }

    public ParameterSet addConfiguration(String name, boolean copyActive) {
        var overrides = new LinkedHashMap<String, String>();
        if (copyActive) parameters.forEach(p -> overrides.put(p.id(), expression(p, active)));
        var next = new ArrayList<>(configurations);
        next.add(new Configuration("configuration-" + uuidGenerator.next(), name, overrides));
        return new ParameterSet(parameters, next, active, bindings, uuidGenerator);
    }

    public ParameterSet renameConfiguration(String nameOrId, String name) {
        String id = configurationId(nameOrId);
        if (id.isEmpty()) throw new IllegalArgumentException("Default cannot be renamed");
        return new ParameterSet(
                parameters,
                configurations.stream()
                        .map(c -> c.id().equals(id) ? new Configuration(id, name, c.overrides()) : c)
                        .toList(),
                active,
                bindings,
                uuidGenerator);
    }

    public ParameterSet removeConfiguration(String nameOrId) {
        String id = configurationId(nameOrId);
        if (id.isEmpty()) throw new IllegalArgumentException("Default cannot be deleted");
        return new ParameterSet(
                parameters,
                configurations.stream().filter(c -> !c.id().equals(id)).toList(),
                active.equals(id) ? "" : active,
                bindings,
                uuidGenerator);
    }

    public Map<String, Quantity<?>> values() {
        return values(active);
    }

    public Map<String, Quantity<?>> values(String configuration) {
        String id = configurationId(configuration);
        var byName = new LinkedHashMap<String, Parameter>();
        parameters.forEach(p -> byName.put(p.name(), p));
        var resolved = new LinkedHashMap<String, Quantity<?>>();
        var visiting = new LinkedHashSet<String>();
        class Resolver {
            Quantity<?> get(String name) {
                if (resolved.containsKey(name)) return resolved.get(name);
                var p = byName.get(name);
                if (p == null) throw new IllegalArgumentException("Unknown parameter: " + name);
                if (!visiting.add(name))
                    throw new IllegalArgumentException(
                            "Cyclic parameter dependency: " + String.join(" → ", visiting) + " → " + name);
                if (visiting.size() > 32) throw new IllegalArgumentException("Parameter dependency depth exceeds 32");
                try {
                    var q = Expressions.evaluate(expression(p, id), p.unit(), this::get);
                    resolved.put(name, q);
                    return q;
                } catch (IllegalArgumentException failure) {
                    throw new IllegalArgumentException("Parameter " + name + ": " + failure.getMessage(), failure);
                } finally {
                    visiting.remove(name);
                }
            }
        }
        var resolver = new Resolver();
        parameters.forEach(p -> resolver.get(p.name()));
        return Collections.unmodifiableMap(resolved);
    }

    public double value(String expression, ParameterUnit unit) {
        return unit.modelValue(Expressions.evaluate(expression, unit, expressionValues()));
    }

    public String text(String expression) {
        return Expressions.evaluateText(expression, expressionValues());
    }

    // Literal inputs must remain usable when repairing or saving a document with bad parameters.
    private java.util.function.Function<String, Quantity<?>> expressionValues() {
        return new java.util.function.Function<>() {
            private Map<String, Quantity<?>> resolved;

            @Override
            public Quantity<?> apply(String name) {
                if (resolved == null) resolved = values();
                return resolved.get(name);
            }
        };
    }

    public void validate() {
        var ids = new ArrayList<String>();
        ids.add("");
        configurations.forEach(c -> ids.add(c.id()));
        for (var id : ids) {
            try {
                var values = values(id);
                for (var binding : bindings) Expressions.evaluateAny(binding.expression(), values::get);
            } catch (IllegalArgumentException failure) {
                String name = id.isEmpty()
                        ? "Default"
                        : configurations.stream()
                                .filter(c -> c.id().equals(id))
                                .findFirst()
                                .orElseThrow()
                                .name();
                throw new IllegalArgumentException("Configuration " + name + ": " + failure.getMessage(), failure);
            }
        }
    }
}
