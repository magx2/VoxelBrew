package dev.voxelbrew.engine.sketch;

import static dev.voxelbrew.engine.sketch.SketchGeometry.*;

import dev.voxelbrew.engine.geometry.PlanarProfile;
import java.util.*;
import org.apache.commons.math3.linear.*;

/** Exact analytic/rational curves and cubic interpolation; samples are only for display. */
public final class SketchCurves {
    private SketchCurves() {}

    public record BSpline(int degree, List<Vec> poles, List<Double> knots) {
        public BSpline {
            poles = List.copyOf(poles);
            knots = List.copyOf(knots);
        }

        public Vec at(double t, int order) {
            var p = new ArrayList<>(poles);
            var k = new ArrayList<>(knots);
            int d = degree;
            double begin = knots.get(degree), end = knots.get(poles.size());
            for (int derivative = 0; derivative < order; derivative++) {
                var next = new ArrayList<Vec>();
                for (int i = 0; i < p.size() - 1; i++) {
                    double span = k.get(i + d + 1) - k.get(i + 1);
                    next.add(
                            span == 0
                                    ? new Vec(0, 0)
                                    : p.get(i + 1).minus(p.get(i)).times(d / span));
                }
                p = next;
                k = new ArrayList<>(k.subList(1, k.size() - 1));
                d--;
            }
            double u = begin + Math.clamp(t, 0, 1) * (end - begin);
            int span = p.size() - 1;
            if (u < end)
                for (int i = d; i < p.size(); i++)
                    if (u < k.get(i + 1)) {
                        span = i;
                        break;
                    }
            var work = new Vec[d + 1];
            for (int j = 0; j <= d; j++) work[j] = p.get(span - d + j);
            for (int r = 1; r <= d; r++)
                for (int j = d; j >= r; j--) {
                    int i = span - d + j;
                    double denominator = k.get(i + d - r + 1) - k.get(i);
                    double a = denominator == 0 ? 0 : (u - k.get(i)) / denominator;
                    work[j] = work[j - 1].mix(work[j], a);
                }
            return work[d].times(Math.pow(end - begin, order));
        }

        public PlanarProfile.Segment segment(boolean reverse) {
            var values = new ArrayList<Double>();
            values.add((double) degree);
            values.add((double) poles.size());
            for (var p : poles) {
                values.add(p.x());
                values.add(p.y());
            }
            values.addAll(knots);
            return new PlanarProfile.Segment(PlanarProfile.Kind.BSPLINE, values, reverse);
        }
    }

    public static BSpline control(Entity e) {
        var poles = points(e);
        int original = poles.size(), degree = e.degree();
        var knots = new ArrayList<Double>();
        if (e.closed()) {
            for (int i = 0; i < degree; i++) poles.add(poles.get(i));
            for (int i = 0; i < poles.size() + degree + 1; i++) knots.add((double) i);
        } else {
            for (int i = 0; i < original + degree + 1; i++)
                knots.add((double) Math.clamp(i - degree, 0, original - degree));
        }
        return new BSpline(degree, poles, knots);
    }

    public static List<Entity> fitSegments(Entity e) {
        var p = points(e);
        int n = p.size(), spans = e.closed() ? n : n - 1;
        var h = new double[spans];
        for (int i = 0; i < spans; i++) {
            h[i] = p.get(i).distance(p.get((i + 1) % n));
            if (h[i] < 1e-6)
                throw new IllegalArgumentException("Spline fit points must be distinct");
        }
        var matrix = new double[n][n];
        var rhs = new double[n][2];
        for (int i = 0; i < n; i++) {
            if (!e.closed() && (i == 0 || i == n - 1)) {
                matrix[i][i] = 1;
                continue;
            }
            int previous = (i + n - 1) % n, next = (i + 1) % n;
            double a = h[previous], b = h[i];
            matrix[i][previous] += a;
            matrix[i][i] = 2 * (a + b);
            matrix[i][next] += b;
            var difference =
                    p.get(next)
                            .minus(p.get(i))
                            .times(1 / b)
                            .minus(p.get(i).minus(p.get(previous)).times(1 / a))
                            .times(6);
            rhs[i][0] = difference.x();
            rhs[i][1] = difference.y();
        }
        var second =
                new LUDecomposition(new Array2DRowRealMatrix(matrix, false))
                        .getSolver()
                        .solve(new Array2DRowRealMatrix(rhs, false));
        var result = new ArrayList<Entity>();
        for (int i = 0; i < spans; i++) {
            int j = (i + 1) % n;
            var a = p.get(i);
            var b = p.get(j);
            var m0 = new Vec(second.getEntry(i, 0), second.getEntry(i, 1));
            var m1 = new Vec(second.getEntry(j, 0), second.getEntry(j, 1));
            var c0 =
                    a.plus(b.minus(a).times(1.0 / 3))
                            .minus(m0.times(2).plus(m1).times(h[i] * h[i] / 18));
            var c1 =
                    b.minus(b.minus(a).times(1.0 / 3))
                            .minus(m0.plus(m1.times(2)).times(h[i] * h[i] / 18));
            result.add(
                    Sketches.entity(
                            SketchGeometry.derivedId(e.id(), "-span-" + i),
                            Kind.SPLINE,
                            e.construction(),
                            a.x(),
                            a.y(),
                            c0.x(),
                            c0.y(),
                            c1.x(),
                            c1.y(),
                            b.x(),
                            b.y()));
        }
        return List.copyOf(result);
    }

    private static ArrayList<Vec> points(Entity e) {
        var result = new ArrayList<Vec>();
        for (int i = 0; i < e.values().size(); i += 2)
            result.add(new Vec(e.value(i), e.value(i + 1)));
        return result;
    }

    public static Vec at(Entity e, double t, int order) {
        if (e.kind() == Kind.TEXT)
            return order == 0 ? new Vec(e.value(0), e.value(1)) : new Vec(0, 0);
        if (e.kind() == Kind.CONTROL_SPLINE) return control(e).at(t, order);
        if (e.kind() == Kind.FIT_SPLINE) return fitAt(fitSegments(e), points(e), t, order);
        if (e.kind() == Kind.ELLIPSE) {
            double a = t * Math.TAU, rotation = e.value(4);
            Vec local =
                    switch (order) {
                        case 0 -> new Vec(e.value(2) * Math.cos(a), e.value(3) * Math.sin(a));
                        case 1 ->
                                new Vec(-e.value(2) * Math.sin(a), e.value(3) * Math.cos(a))
                                        .times(Math.TAU);
                        default ->
                                new Vec(-e.value(2) * Math.cos(a), -e.value(3) * Math.sin(a))
                                        .times(Math.TAU * Math.TAU);
                    };
            var rotated = rotate(local, rotation);
            return order == 0 ? rotated.plus(new Vec(e.value(0), e.value(1))) : rotated;
        }
        if (e.kind() == Kind.CONIC) {
            var a = new Vec(e.value(0), e.value(1));
            var v = new Vec(e.value(2), e.value(3));
            var b = new Vec(e.value(4), e.value(5));
            double w = e.value(6) / (1 - e.value(6)), s = 1 - t;
            double denominator = s * s + 2 * w * s * t + t * t;
            var n = a.times(s * s).plus(v.times(2 * w * s * t)).plus(b.times(t * t));
            var position = n.times(1 / denominator);
            if (order == 0) return position;
            double firstD = -2 * s + 2 * w * (1 - 2 * t) + 2 * t;
            var firstN = a.times(-2 * s).plus(v.times(2 * w * (1 - 2 * t))).plus(b.times(2 * t));
            var first = firstN.minus(position.times(firstD)).times(1 / denominator);
            if (order == 1) return first;
            var secondN = a.times(2).minus(v.times(4 * w)).plus(b.times(2));
            return secondN.minus(first.times(2 * firstD))
                    .minus(position.times(4 - 4 * w))
                    .times(1 / denominator);
        }
        throw new IllegalArgumentException("Unsupported curve: " + e.kind());
    }

    private static Vec fitAt(List<Entity> spans, List<Vec> p, double t, int order) {
        double total = 0;
        var lengths = new double[spans.size()];
        for (int i = 0; i < lengths.length; i++)
            total += lengths[i] = p.get(i).distance(p.get((i + 1) % p.size()));
        double distance = Math.clamp(t, 0, 1) * total;
        int i = 0;
        while (i < spans.size() - 1 && distance > lengths[i]) distance -= lengths[i++];
        var span = spans.get(i);
        double s = Math.clamp(distance / lengths[i], 0, 1), factor = total / lengths[i];
        if (order == 0) return span.at(s);
        if (order == 1) return span.derivative(s).times(factor);
        var a = new Vec(span.value(0), span.value(1));
        var b = new Vec(span.value(2), span.value(3));
        var c = new Vec(span.value(4), span.value(5));
        var d = new Vec(span.value(6), span.value(7));
        return c.minus(b.times(2))
                .plus(a)
                .times(6 * (1 - s))
                .plus(d.minus(c.times(2)).plus(b).times(6 * s))
                .times(factor * factor);
    }

    public static List<Vec> sample(Entity e, int count) {
        var result = new ArrayList<Vec>();
        var spans = e.kind() == Kind.FIT_SPLINE ? fitSegments(e) : List.<Entity>of();
        var points = points(e);
        var control = e.kind() == Kind.CONTROL_SPLINE ? control(e) : null;
        for (int i = 0; i <= count; i++)
            result.add(
                    control != null
                            ? control.at((double) i / count, 0)
                            : fitAt(spans, points, (double) i / count, 0));
        return List.copyOf(result);
    }

    public static Vec rotate(Vec p, double angle) {
        return new Vec(
                p.x() * Math.cos(angle) - p.y() * Math.sin(angle),
                p.x() * Math.sin(angle) + p.y() * Math.cos(angle));
    }

    public static List<PlanarProfile.Segment> segments(Entity e, boolean reversed) {
        if (e.kind() == Kind.CONTROL_SPLINE) return List.of(control(e).segment(reversed));
        if (e.kind() == Kind.FIT_SPLINE) {
            var spans = new ArrayList<>(fitSegments(e));
            if (reversed) Collections.reverse(spans);
            return spans.stream()
                    .map(
                            s ->
                                    new PlanarProfile.Segment(
                                            PlanarProfile.Kind.BEZIER, s.values(), reversed))
                    .toList();
        }
        var kind =
                switch (e.kind()) {
                    case LINE -> PlanarProfile.Kind.LINE;
                    case CIRCLE -> PlanarProfile.Kind.CIRCLE;
                    case ARC -> PlanarProfile.Kind.ARC;
                    case SPLINE -> PlanarProfile.Kind.BEZIER;
                    case ELLIPSE -> PlanarProfile.Kind.ELLIPSE;
                    case CONIC -> PlanarProfile.Kind.CONIC;
                    default ->
                            throw new IllegalArgumentException(
                                    "This entity is not a profile curve");
                };
        return List.of(new PlanarProfile.Segment(kind, e.values(), reversed));
    }
}
