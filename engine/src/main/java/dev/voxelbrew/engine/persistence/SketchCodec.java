package dev.voxelbrew.engine.persistence;

import dev.voxelbrew.engine.persistence.proto.DocumentProto;
import dev.voxelbrew.engine.sketch.SketchGeometry;

/** Protobuf remains an implementation detail; enum names are the compatibility contract. */
final class SketchCodec {
    private SketchCodec() {}

    static SketchGeometry fromProto(DocumentProto.ConstrainedSketch message) {
        return new SketchGeometry(
                message.getEntitiesList().stream()
                        .map(
                                e ->
                                        new SketchGeometry.Entity(
                                                e.getId(),
                                                SketchGeometry.Kind.valueOf(e.getKind().name()),
                                                e.getValuesList(),
                                                e.getConstruction()))
                        .toList(),
                message.getConstraintsList().stream()
                        .map(
                                c ->
                                        new SketchGeometry.Constraint(
                                                c.getId(),
                                                SketchGeometry.Type.valueOf(c.getType().name()),
                                                c.getRefsList().stream()
                                                        .map(
                                                                r ->
                                                                        new SketchGeometry.Ref(
                                                                                r.getEntityId(),
                                                                                SketchGeometry.Part
                                                                                        .valueOf(
                                                                                                r.getPart()
                                                                                                        .name())))
                                                        .toList(),
                                                c.getValuesList()))
                        .toList(),
                message.getBoundaryList());
    }

    static DocumentProto.ConstrainedSketch toProto(SketchGeometry geometry) {
        var out = DocumentProto.ConstrainedSketch.newBuilder().addAllBoundary(geometry.boundary());
        for (var e : geometry.entities())
            out.addEntities(
                    DocumentProto.SketchEntity.newBuilder()
                            .setId(e.id())
                            .setKind(DocumentProto.SketchEntity.Kind.valueOf(e.kind().name()))
                            .addAllValues(e.values())
                            .setConstruction(e.construction()));
        for (var c : geometry.constraints()) {
            var constraint =
                    DocumentProto.SketchConstraint.newBuilder()
                            .setId(c.id())
                            .setType(DocumentProto.SketchConstraint.Type.valueOf(c.type().name()))
                            .addAllValues(c.values());
            for (var r : c.refs())
                constraint.addRefs(
                        DocumentProto.SketchReference.newBuilder()
                                .setEntityId(r.entityId())
                                .setPart(
                                        DocumentProto.SketchReference.Part.valueOf(
                                                r.part().name())));
            out.addConstraints(constraint);
        }
        return out.build();
    }
}
