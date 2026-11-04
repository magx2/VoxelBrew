package dev.voxelbrew.engine.model;

/** Persistent definitions only: no UI objects, protobuf messages or native handles. */
public sealed interface Feature permits Feature.Sketch, Feature.Extrude, Feature.ThroughCut {
    String id();

    record Plane(String id, double offsetZ) {}

    record ProfileRef(String sketchId, String profileId) {}

    record BodyRef(String featureId, String outputName) {}

    sealed interface Profile permits Rectangle, Circle {}

    record Rectangle(double x, double y, double width, double height) implements Profile {}

    record Circle(double centerX, double centerY, double diameter) implements Profile {}

    record Sketch(String id, String profileId, Plane plane, Profile profile) implements Feature {}

    record Extrude(String id, ProfileRef profile, double distance) implements Feature {}

    record ThroughCut(String id, ProfileRef profile, BodyRef body) implements Feature {}
}
