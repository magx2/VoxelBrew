package dev.voxelbrew.engine.geometry;

import java.util.List;

/** Ordered exact XY curves: counterclockwise exterior followed by clockwise holes. */
public record PlanarProfile(List<List<Segment>> loops) {
    public PlanarProfile {
        loops = loops.stream().map(List::copyOf).toList();
        if (loops.isEmpty() || loops.size() > 64 || loops.stream().anyMatch(List::isEmpty))
            throw new IllegalArgumentException(
                    "A profile needs a nonempty exterior and at most 63 holes");
        if (loops.stream().mapToInt(List::size).sum() > 64)
            throw new IllegalArgumentException("A profile supports up to 64 curves");
    }

    public enum Kind {
        LINE,
        CIRCLE,
        ARC,
        BEZIER
    }

    public record Segment(Kind kind, List<Double> values, boolean reversed) {
        public Segment {
            java.util.Objects.requireNonNull(kind);
            values = List.copyOf(values);
            int size =
                    switch (kind) {
                        case LINE -> 4;
                        case CIRCLE -> 3;
                        case ARC -> 5;
                        case BEZIER -> 8;
                    };
            if (values.size() != size || values.stream().anyMatch(v -> !Double.isFinite(v)))
                throw new IllegalArgumentException("Invalid planar curve parameters for " + kind);
        }
    }

    int[] sizes() {
        return loops.stream().mapToInt(List::size).toArray();
    }

    int[] types() {
        return loops.stream()
                .flatMap(List::stream)
                .mapToInt(s -> (s.kind().ordinal() + 1) * (s.reversed() ? -1 : 1))
                .toArray();
    }

    double[] parameters() {
        double[] result = new double[loops.stream().mapToInt(List::size).sum() * 8];
        int segment = 0;
        for (var loop : loops)
            for (var s : loop) {
                for (int i = 0; i < s.values().size(); i++)
                    result[segment * 8 + i] = s.values().get(i);
                segment++;
            }
        return result;
    }
}
