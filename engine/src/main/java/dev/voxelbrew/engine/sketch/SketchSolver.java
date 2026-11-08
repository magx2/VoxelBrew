package dev.voxelbrew.engine.sketch;

import static dev.voxelbrew.engine.sketch.SketchGeometry.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.SingularValueDecomposition;

/**
 * Damped least-squares constraint solver. No native kernel, UI toolkit, or mutable global state.
 */
public final class SketchSolver {
    private SketchSolver() {}

    public record Problem(String constraintId, String message) {}

    public record Result(
            SketchGeometry geometry,
            int degreesOfFreedom,
            int redundantEquations,
            List<Problem> problems,
            Set<Ref> fullyConstrainedReferences) {
        public Result(SketchGeometry geometry, int degreesOfFreedom, int redundantEquations, List<Problem> problems) {
            this(geometry, degreesOfFreedom, redundantEquations, problems, Set.of());
        }

        public Result {
            problems = List.copyOf(problems);
            fullyConstrainedReferences = Set.copyOf(fullyConstrainedReferences);
        }

        public boolean solved() {
            return problems.isEmpty();
        }

        public String status() {
            if (!solved()) return "Conflicting or invalid constraints";
            return degreesOfFreedom == 0
                    ? "Fully constrained"
                    : "Under-constrained · " + degreesOfFreedom + " degrees of freedom";
        }

        public SketchGeometry requireSolved() {
            if (!solved())
                throw new IllegalArgumentException(problems.stream()
                        .map(Problem::message)
                        .reduce((a, b) -> a + "; " + b)
                        .orElseThrow());
            return geometry;
        }
    }

    public static Result solve(SketchGeometry input) {
        return solve(input, true);
    }

    static Result solve(SketchGeometry input, boolean parallel) {
        double[] x = input.entities().stream()
                .flatMap(e -> e.values().stream())
                .mapToDouble(Double::doubleValue)
                .toArray();
        SketchGeometry current = input;
        try {
            SketchConstraints.validateGeometry(current);
            double[] residual = residual(current);
            double lambda = 1e-5;
            for (int iteration = 0; iteration < 100 && max(residual) > 1e-8; iteration++) {
                if (Thread.currentThread().isInterrupted()) throw new CancellationException("Sketch solve cancelled");
                if (x.length == 0) break;
                var svd = new SingularValueDecomposition(
                        new Array2DRowRealMatrix(jacobian(input, x, residual.length, parallel), false));
                var singular = svd.getSingularValues();
                var u = svd.getU();
                var v = svd.getV();
                double oldCost = cost(residual);
                boolean improved = false;
                for (int attempt = 0; attempt < 10; attempt++) {
                    var step = new double[x.length];
                    for (int k = 0; k < singular.length; k++) {
                        double coefficient = 0;
                        for (int row = 0; row < residual.length; row++)
                            coefficient -= u.getEntry(row, k) * residual[row];
                        coefficient *= singular[k] / (singular[k] * singular[k] + lambda);
                        for (int col = 0; col < step.length; col++) step[col] += v.getEntry(col, k) * coefficient;
                    }
                    double[] candidate = x.clone();
                    for (int i = 0; i < x.length; i++) candidate[i] += step[i];
                    try {
                        var geometry = unpack(input, candidate);
                        SketchConstraints.validateGeometry(geometry);
                        var next = residual(geometry);
                        if (cost(next) < oldCost) {
                            x = candidate;
                            current = geometry;
                            residual = next;
                            lambda = Math.max(1e-12, lambda / 3);
                            improved = true;
                            break;
                        }
                    } catch (IllegalArgumentException invalidTrial) {
                        /* Keep the last valid geometry. */
                    }
                    lambda *= 10;
                }
                if (!improved) break;
            }
            var problems = problems(current);
            if (!problems.isEmpty()) return new Result(current, -1, 0, problems);
            if (x.length == 0 || residual.length == 0)
                return new Result(current, x.length, residual.length, List.of(), Set.of(ORIGIN));
            var decomposition = new SingularValueDecomposition(
                    new Array2DRowRealMatrix(jacobian(input, x, residual.length, parallel), false));
            var singular = decomposition.getSingularValues();
            double cutoff = Math.max(1e-7, singular[0] * 1e-8);
            int rank = 0;
            for (double value : singular) if (value > cutoff) rank++;
            return new Result(
                    current,
                    Math.max(0, x.length - rank),
                    Math.max(0, residual.length - rank),
                    List.of(),
                    constrainedReferences(current, decomposition.getV(), rank));
        } catch (IllegalArgumentException failure) {
            return new Result(input, -1, 0, List.of(new Problem("", failure.getMessage())));
        }
    }

    /** Classifies geometry by its remaining infinitesimal motion, including indirect constraints. */
    private static Set<Ref> constrainedReferences(
            SketchGeometry geometry, org.apache.commons.math3.linear.RealMatrix v, int rank) {
        var fixed = new HashSet<Ref>();
        fixed.add(ORIGIN);
        int offset = 0;
        for (var entity : geometry.entities()) {
            int count = entity.values().size();
            boolean entityFixed = true;
            for (int i = 0; i < count; i++) {
                double constrained = 0;
                for (int k = 0; k < rank; k++) constrained += v.getEntry(offset + i, k) * v.getEntry(offset + i, k);
                entityFixed &= 1 - constrained < 1e-7;
            }
            if (entityFixed) fixed.add(entity.ref());
            for (var ref : entity.pointRefs()) {
                if (entityFixed) {
                    fixed.add(ref);
                    continue;
                }
                double[] dx = new double[count], dy = new double[count];
                for (int i = 0; i < count; i++) {
                    double h = 1e-6 * Math.max(1, Math.abs(entity.value(i)));
                    var a = new ArrayList<>(entity.values());
                    var b = new ArrayList<>(entity.values());
                    a.set(i, a.get(i) + h);
                    b.set(i, b.get(i) - h);
                    var delta = entity.withValues(a)
                            .point(ref)
                            .minus(entity.withValues(b).point(ref))
                            .times(1 / (2 * h));
                    dx[i] = delta.x();
                    dy[i] = delta.y();
                }
                if (hasNoFreedom(dx, offset, v, rank) && hasNoFreedom(dy, offset, v, rank)) fixed.add(ref);
            }
            offset += count;
        }
        return fixed;
    }

    private static boolean hasNoFreedom(
            double[] gradient, int offset, org.apache.commons.math3.linear.RealMatrix v, int rank) {
        double total = 0, constrained = 0;
        for (double value : gradient) total += value * value;
        for (int k = 0; k < rank; k++) {
            double dot = 0;
            for (int i = 0; i < gradient.length; i++) dot += gradient[i] * v.getEntry(offset + i, k);
            constrained += dot * dot;
        }
        return total - constrained < 1e-7 * Math.max(1, total);
    }

    private static List<Problem> problems(SketchGeometry g) {
        var result = new ArrayList<Problem>();
        for (var c : g.constraints()) {
            double error = max(SketchConstraints.residuals(g, c));
            if (!Double.isFinite(error) || error > TOLERANCE)
                result.add(
                        new Problem(
                                c.id(),
                                c.id()
                                        + " · "
                                        + c.type()
                                        + ": cannot satisfy this constraint together with the current dimensions/fixed geometry"));
        }
        return result;
    }

    private static double[] residual(SketchGeometry g) {
        var values = new ArrayList<Double>();
        for (var c : g.constraints()) for (double value : SketchConstraints.residuals(g, c)) values.add(value);
        return values.stream().mapToDouble(Double::doubleValue).toArray();
    }

    private static double[][] jacobian(SketchGeometry definition, double[] x, int rows, boolean parallel) {
        double[][] result = new double[rows][x.length];
        java.util.function.IntConsumer column = col -> {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException("Sketch solve cancelled");
            double h = 1e-6 * Math.max(1, Math.abs(x[col]));
            double[] a = x.clone(), b = x.clone();
            a[col] += h;
            b[col] -= h;
            double[] ra = residual(unpack(definition, a)), rb = residual(unpack(definition, b));
            for (int row = 0; row < rows; row++) result[row][col] = (ra[row] - rb[row]) / (2 * h);
        };
        // Tiny sketches cost less than scheduling work. Columns write disjoint matrix entries.
        if (parallel && x.length >= 128 && (long) rows * x.length >= 16384)
            dev.voxelbrew.util.concurrent.CpuWork.forEach(x.length, column);
        else for (int col = 0; col < x.length; col++) column.accept(col);
        return result;
    }

    private static SketchGeometry unpack(SketchGeometry source, double[] values) {
        int index = 0;
        var entities = new ArrayList<Entity>();
        for (var entity : source.entities()) {
            var parameters = new ArrayList<Double>();
            for (int i = 0; i < entity.values().size(); i++) parameters.add(values[index++]);
            entities.add(entity.withValues(parameters));
        }
        return source.withEntities(entities);
    }

    private static double max(double[] values) {
        double result = 0;
        for (double value : values) result = Math.max(result, Math.abs(value));
        return result;
    }

    private static double cost(double[] values) {
        double result = 0;
        for (double value : values) result += value * value;
        return result;
    }
}
