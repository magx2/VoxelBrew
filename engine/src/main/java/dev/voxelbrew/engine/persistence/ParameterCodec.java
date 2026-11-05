package dev.voxelbrew.engine.persistence;

import dev.voxelbrew.engine.parameters.*;
import dev.voxelbrew.engine.persistence.proto.DocumentProto;

final class ParameterCodec {
    private ParameterCodec() {}

    static ParameterSet fromProto(DocumentProto.ParameterSet message) {
        return new ParameterSet(
                message.getParametersList().stream()
                        .map(
                                p ->
                                        new ParameterSet.Parameter(
                                                p.getId(),
                                                p.getName(),
                                                ParameterUnit.parse(p.getUnit()),
                                                p.getExpression(),
                                                p.getComment()))
                        .toList(),
                message.getConfigurationsList().stream()
                        .map(
                                c ->
                                        new ParameterSet.Configuration(
                                                c.getId(), c.getName(), c.getOverridesMap()))
                        .toList(),
                message.getActiveConfiguration(),
                message.getBindingsList().stream()
                        .map(
                                b ->
                                        new ParameterSet.Binding(
                                                new ParameterSet.Target(
                                                        b.getFeatureId(), b.getField()),
                                                b.getExpression()))
                        .toList());
    }

    static DocumentProto.ParameterSet toProto(ParameterSet parameters) {
        var result =
                DocumentProto.ParameterSet.newBuilder().setActiveConfiguration(parameters.active());
        for (var p : parameters.parameters())
            result.addParameters(
                    DocumentProto.UserParameter.newBuilder()
                            .setId(p.id())
                            .setName(p.name())
                            .setUnit(p.unit().name())
                            .setExpression(p.expression())
                            .setComment(p.comment()));
        for (var c : parameters.configurations())
            result.addConfigurations(
                    DocumentProto.ParameterConfiguration.newBuilder()
                            .setId(c.id())
                            .setName(c.name())
                            .putAllOverrides(c.overrides()));
        for (var b : parameters.bindings())
            result.addBindings(
                    DocumentProto.DimensionBinding.newBuilder()
                            .setFeatureId(b.target().featureId())
                            .setField(b.target().field())
                            .setExpression(b.expression()));
        return result.build();
    }
}
