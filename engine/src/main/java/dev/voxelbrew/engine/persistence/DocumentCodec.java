package dev.voxelbrew.engine.persistence;

import com.google.protobuf.Message;
import dev.voxelbrew.engine.export.ModelView;
import dev.voxelbrew.engine.export.Transform;
import dev.voxelbrew.engine.model.CadDocument;
import dev.voxelbrew.engine.model.Feature;
import dev.voxelbrew.engine.persistence.proto.DocumentProto;
import dev.voxelbrew.engine.printing.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;

/**
 * In-memory CAD document schema, integrity envelope and stable revision fingerprint. File readers
 * and writers live in the import/export projects.
 */
public final class DocumentCodec {
    public static final int SCHEMA_VERSION = 6;
    private static final byte[] MAGIC = {'J', 'C', 'A', 'D', 13, 10, 26, 10};
    private static final int HEADER = 44;
    private static final int MAX_PAYLOAD = 16 * 1024 * 1024;

    public static final int MAX_ENCODED_BYTES = MAX_PAYLOAD + HEADER;

    private DocumentCodec() {}

    public static CadDocument decode(byte[] bytes) throws IOException {
        int size = bytes.length;
        if (size < HEADER || size > MAX_PAYLOAD + HEADER) {
            throw new DocumentFormatException(
                    "Invalid or truncated .jcad file: expected a complete header and <=16 MiB payload");
        }
        bytes = bytes.clone();
        if (!Arrays.equals(MAGIC, Arrays.copyOf(bytes, MAGIC.length))) {
            throw new DocumentFormatException("Not a VoxelBrew .jcad file (magic mismatch)");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.position(8);
        int length = buffer.getInt();
        if (length < 1 || length > MAX_PAYLOAD || length != bytes.length - HEADER) {
            throw new DocumentFormatException(
                    "Truncated or damaged .jcad payload: length does not match the header");
        }
        byte[] expectedHash = new byte[32];
        buffer.get(expectedHash);
        byte[] payload = new byte[length];
        buffer.get(payload);
        if (!MessageDigest.isEqual(expectedHash, sha256(payload))) {
            throw new DocumentFormatException("Damaged .jcad file: payload checksum mismatch");
        }
        try {
            DocumentProto.Document message = DocumentProto.Document.parseFrom(payload);
            if (message.getSchemaVersion() < 1 || message.getSchemaVersion() > SCHEMA_VERSION) {
                throw new DocumentFormatException(
                        "Unsupported .jcad schema "
                                + message.getSchemaVersion()
                                + "; supported version is "
                                + SCHEMA_VERSION
                                + ". File is read-only; no write performed.");
            }
            if (unknownFields(message)) {
                throw new DocumentFormatException(
                        "Document contains unsupported fields; file is read-only to prevent data loss");
            }
            if (message.getUnits() != DocumentProto.Units.MILLIMETERS) {
                throw new DocumentFormatException(
                        "Unsupported document units; expected MILLIMETERS");
            }
            return new CadDocument(
                    message.getDocumentId(),
                    message.getFeaturesList().stream().map(DocumentCodec::fromProto).toList(),
                    message.getCursor(),
                    message.hasPrintSetup()
                            ? fromProto(message.getPrintSetup())
                            : PrintSetup.empty(),
                    message.hasParameters()
                            ? ParameterCodec.fromProto(message.getParameters())
                            : dev.voxelbrew.engine.parameters.ParameterSet.empty());
        } catch (DocumentFormatException failure) {
            throw failure;
        } catch (IOException | IllegalArgumentException failure) {
            throw new DocumentFormatException(
                    "Invalid .jcad feature definitions: " + failure.getMessage(), failure);
        }
    }

    /** Encodes the shared document schema without accessing the filesystem. */
    public static byte[] encode(CadDocument document) throws IOException {
        byte[] payload = toProto(document).toByteArray();
        if (payload.length > MAX_PAYLOAD) {
            throw new IOException("Document exceeds 16 MiB limit");
        }
        ByteBuffer bytes =
                ByteBuffer.allocate(HEADER + payload.length)
                        .put(MAGIC)
                        .putInt(payload.length)
                        .put(sha256(payload))
                        .put(payload);
        return bytes.array();
    }

    private static Feature fromProto(DocumentProto.Feature feature) {
        String id = feature.getId();
        return switch (feature.getDefinitionCase()) {
            case SKETCH -> {
                var sketch = feature.getSketch();
                var plane =
                        new Feature.Plane(
                                sketch.getPlane().getId(), sketch.getPlane().getOffsetZ());
                Feature.Profile profile =
                        switch (sketch.getProfileCase()) {
                            case RECTANGLE -> {
                                var r = sketch.getRectangle();
                                yield new Feature.Rectangle(
                                        r.getX(), r.getY(), r.getWidth(), r.getHeight());
                            }
                            case CIRCLE -> {
                                var c = sketch.getCircle();
                                yield new Feature.Circle(
                                        c.getCenterX(), c.getCenterY(), c.getDiameter());
                            }
                            case CONSTRAINED ->
                                    new Feature.Constrained(
                                            SketchCodec.fromProto(sketch.getConstrained()));
                            case PROFILE_NOT_SET ->
                                    throw new IllegalArgumentException(
                                            "Feature '" + id + "': missing sketch profile");
                        };
                yield new Feature.Sketch(id, sketch.getProfileId(), plane, profile);
            }
            case EXTRUDE ->
                    new Feature.Extrude(
                            id,
                            profileRef(feature.getExtrude().getProfile()),
                            feature.getExtrude().getDistance());
            case THROUGH_CUT ->
                    new Feature.ThroughCut(
                            id,
                            profileRef(feature.getThroughCut().getProfile()),
                            new Feature.BodyRef(
                                    feature.getThroughCut().getBody().getFeatureId(),
                                    feature.getThroughCut().getBody().getOutputName()));
            case SPLIT_BODY -> {
                var s = feature.getSplitBody();
                yield new Feature.SplitBody(
                        id,
                        new Feature.BodyRef(
                                s.getBody().getFeatureId(), s.getBody().getOutputName()),
                        new Feature.SplitPlane(
                                s.getNormalX(), s.getNormalY(), s.getNormalZ(), s.getOffset()));
            }
            case CUSTOM -> {
                var custom = feature.getCustom();
                yield new Feature.Custom(
                        id,
                        custom.getProviderId(),
                        custom.getType(),
                        custom.getPayloadVersion(),
                        custom.getPayload().toByteArray());
            }
            case DEFINITION_NOT_SET ->
                    throw new IllegalArgumentException(
                            "Feature '" + id + "': missing or unsupported feature definition");
        };
    }

    /** Includes geometry definitions and print setup; derived output is never part of identity. */
    public static String fingerprint(CadDocument document) {
        return java.util.HexFormat.of().formatHex(sha256(toProto(document).toByteArray()));
    }

    private static DocumentProto.Document toProto(CadDocument document) {
        var message =
                DocumentProto.Document.newBuilder()
                        .setSchemaVersion(SCHEMA_VERSION)
                        .setDocumentId(document.id())
                        .setUnits(DocumentProto.Units.MILLIMETERS)
                        .setCursor(document.cursor())
                        .setPrintSetup(toProto(document.printSetup()))
                        .setParameters(ParameterCodec.toProto(document.parameters()));
        document.features().forEach(feature -> message.addFeatures(toProto(feature)));
        return message.build();
    }

    private static PrintSetup fromProto(DocumentProto.PrintSetup setup) {
        var p = setup.getProcess();
        var process =
                new ProcessSettings(
                        p.getLayerHeight(),
                        p.getWalls(),
                        p.getInfillPercent(),
                        p.getAutomaticSupports(),
                        p.getSupportsOnBedOnly(),
                        p.getSupportThreshold(),
                        p.getSupportContactGap(),
                        p.getInterfaceLayers(),
                        p.getBrimWidth(),
                        p.getRaftLayers());
        return new PrintSetup(
                setup.getId(),
                setup.getVersion(),
                setup.getBackendId(),
                setup.getPrinterId(),
                setup.getMaterialId(),
                process,
                setup.getInstancesList().stream()
                        .map(
                                i ->
                                        new ModelView.Instance(
                                                i.getId(),
                                                i.getBodyId(),
                                                new Transform(
                                                        i.getTransformList().stream()
                                                                .mapToDouble(Double::doubleValue)
                                                                .toArray())))
                        .toList(),
                setup.getSupportsList().stream()
                        .map(
                                s ->
                                        new SupportVolume(
                                                s.getId(),
                                                s.getInstanceId(),
                                                switch (s.getRole()) {
                                                    case BLOCKER -> SupportVolume.Role.BLOCKER;
                                                    case ENFORCER -> SupportVolume.Role.ENFORCER;
                                                    case SOLID_SUPPORT ->
                                                            SupportVolume.Role.SOLID_SUPPORT;
                                                    default ->
                                                            throw new IllegalArgumentException(
                                                                    "Unsupported support role "
                                                                            + s.getRole());
                                                },
                                                s.getX(),
                                                s.getY(),
                                                s.getZ(),
                                                s.getWidth(),
                                                s.getDepth(),
                                                s.getHeight()))
                        .toList());
    }

    private static DocumentProto.PrintSetup toProto(PrintSetup setup) {
        var p = setup.process();
        var out =
                DocumentProto.PrintSetup.newBuilder()
                        .setId(setup.id())
                        .setVersion(setup.version())
                        .setBackendId(setup.backendId())
                        .setPrinterId(setup.printerId())
                        .setMaterialId(setup.materialId())
                        .setProcess(
                                DocumentProto.PrintProcess.newBuilder()
                                        .setLayerHeight(p.layerHeight())
                                        .setWalls(p.walls())
                                        .setInfillPercent(p.infillPercent())
                                        .setAutomaticSupports(p.automaticSupports())
                                        .setSupportsOnBedOnly(p.supportsOnBedOnly())
                                        .setSupportThreshold(p.supportThreshold())
                                        .setSupportContactGap(p.supportContactGap())
                                        .setInterfaceLayers(p.interfaceLayers())
                                        .setBrimWidth(p.brimWidth())
                                        .setRaftLayers(p.raftLayers()));
        for (var i : setup.instances())
            out.addInstances(
                    DocumentProto.PrintInstance.newBuilder()
                            .setId(i.id())
                            .setBodyId(i.bodyId())
                            .addAllTransform(
                                    Arrays.stream(i.transform().matrix()).boxed().toList()));
        for (var s : setup.supports())
            out.addSupports(
                    DocumentProto.SupportVolume.newBuilder()
                            .setId(s.id())
                            .setInstanceId(s.instanceId())
                            .setRole(
                                    switch (s.role()) {
                                        case BLOCKER -> DocumentProto.SupportVolume.Role.BLOCKER;
                                        case ENFORCER -> DocumentProto.SupportVolume.Role.ENFORCER;
                                        case SOLID_SUPPORT ->
                                                DocumentProto.SupportVolume.Role.SOLID_SUPPORT;
                                    })
                            .setX(s.x())
                            .setY(s.y())
                            .setZ(s.z())
                            .setWidth(s.width())
                            .setDepth(s.depth())
                            .setHeight(s.height()));
        return out.build();
    }

    private static DocumentProto.Feature toProto(Feature feature) {
        var out = DocumentProto.Feature.newBuilder().setId(feature.id());
        if (feature instanceof Feature.Sketch s) {
            var sketch =
                    DocumentProto.Sketch.newBuilder()
                            .setProfileId(s.profileId())
                            .setPlane(
                                    DocumentProto.Plane.newBuilder()
                                            .setId(s.plane().id())
                                            .setOffsetZ(s.plane().offsetZ()));
            if (s.profile() instanceof Feature.Rectangle r) {
                sketch.setRectangle(
                        DocumentProto.Rectangle.newBuilder()
                                .setX(r.x())
                                .setY(r.y())
                                .setWidth(r.width())
                                .setHeight(r.height()));
            } else if (s.profile() instanceof Feature.Circle c) {
                sketch.setCircle(
                        DocumentProto.Circle.newBuilder()
                                .setCenterX(c.centerX())
                                .setCenterY(c.centerY())
                                .setDiameter(c.diameter()));
            } else if (s.profile() instanceof Feature.Constrained c) {
                sketch.setConstrained(SketchCodec.toProto(c.geometry()));
            } else {
                throw new IllegalArgumentException("Feature '" + s.id() + "': unsupported profile");
            }
            out.setSketch(sketch);
        } else if (feature instanceof Feature.Extrude e) {
            out.setExtrude(
                    DocumentProto.Extrude.newBuilder()
                            .setProfile(profileRef(e.profile()))
                            .setDistance(e.distance()));
        } else if (feature instanceof Feature.ThroughCut c) {
            out.setThroughCut(
                    DocumentProto.ThroughCut.newBuilder()
                            .setProfile(profileRef(c.profile()))
                            .setBody(
                                    DocumentProto.BodyRef.newBuilder()
                                            .setFeatureId(c.body().featureId())
                                            .setOutputName(c.body().outputName())));
        } else if (feature instanceof Feature.SplitBody s) {
            out.setSplitBody(
                    DocumentProto.SplitBody.newBuilder()
                            .setBody(
                                    DocumentProto.BodyRef.newBuilder()
                                            .setFeatureId(s.body().featureId())
                                            .setOutputName(s.body().outputName()))
                            .setNormalX(s.plane().normalX())
                            .setNormalY(s.plane().normalY())
                            .setNormalZ(s.plane().normalZ())
                            .setOffset(s.plane().offset()));
        } else {
            if (feature instanceof Feature.Custom custom) {
                out.setCustom(
                        DocumentProto.CustomFeature.newBuilder()
                                .setProviderId(custom.providerId())
                                .setType(custom.type())
                                .setPayloadVersion(custom.payloadVersion())
                                .setPayload(
                                        com.google.protobuf.ByteString.copyFrom(custom.payload())));
            } else throw new IllegalArgumentException("Unsupported feature " + feature.id());
        }
        return out.build();
    }

    private static Feature.ProfileRef profileRef(DocumentProto.ProfileRef ref) {
        return new Feature.ProfileRef(ref.getSketchId(), ref.getProfileId());
    }

    private static DocumentProto.ProfileRef profileRef(Feature.ProfileRef ref) {
        return DocumentProto.ProfileRef.newBuilder()
                .setSketchId(ref.sketchId())
                .setProfileId(ref.profileId())
                .build();
    }

    private static boolean unknownFields(Message message) {
        if (!message.getUnknownFields().asMap().isEmpty()) {
            return true;
        }
        for (Object value : message.getAllFields().values()) {
            if (value instanceof Message child && unknownFields(child)) {
                return true;
            }
            if (value instanceof List<?> values) {
                for (Object item : values) {
                    if (item instanceof Message child && unknownFields(child)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
