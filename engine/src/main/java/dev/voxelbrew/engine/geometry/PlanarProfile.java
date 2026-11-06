package dev.voxelbrew.engine.geometry;

import java.util.List;

/** Ordered exact XY curves: counterclockwise exterior followed by clockwise holes. */
public record PlanarProfile(List<List<Segment>> loops) {
    public PlanarProfile {
        loops = loops.stream().map(List::copyOf).toList();
        if (loops.isEmpty() || loops.size() > 256 || loops.stream().anyMatch(List::isEmpty))
            throw new IllegalArgumentException("A profile needs a nonempty exterior and at most 255 holes");
        if (loops.stream().mapToInt(List::size).sum() > 4096)
            throw new IllegalArgumentException("A profile supports up to 4096 evaluated curves");
    }

    public enum Kind {
        LINE,
        CIRCLE,
        ARC,
        BEZIER,
        ELLIPSE,
        CONIC,
        BSPLINE
    }

    public record Segment(Kind kind, List<Double> values, boolean reversed) {
        public Segment {
            java.util.Objects.requireNonNull(kind);
            values = List.copyOf(values);
            int size = switch (kind) {
                case LINE -> 4;
                case CIRCLE -> 3;
                case ARC -> 5;
                case BEZIER -> 8;
                case ELLIPSE -> 5;
                case CONIC -> 7;
                case BSPLINE -> values.size();
            };
            if (values.size() != size || values.stream().anyMatch(v -> !Double.isFinite(v)))
                throw new IllegalArgumentException("Invalid planar curve parameters for " + kind);
            if (kind == Kind.BSPLINE) {
                if (values.size() < 10) throw new IllegalArgumentException("Invalid spline encoding");
                int degree = values.get(0).intValue(), count = values.get(1).intValue();
                if ((degree != 3 && degree != 5)
                        || count < degree + 1
                        || count > 37
                        || values.get(0) != degree
                        || values.get(1) != count
                        || size != 3 + 3 * count + degree)
                    throw new IllegalArgumentException("Invalid spline degree/poles/knots");
            }
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
        return loops.stream()
                .flatMap(List::stream)
                .flatMap(s -> s.values().stream())
                .mapToDouble(Double::doubleValue)
                .toArray();
    }

    int[] parameterSizes() {
        return loops.stream()
                .flatMap(List::stream)
                .mapToInt(s -> s.values().size())
                .toArray();
    }
}
