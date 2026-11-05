package dev.voxelbrew.engine.sketch;

import static dev.voxelbrew.engine.sketch.SketchGeometry.*;

import java.util.ArrayList;
import java.util.List;
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
            List<Problem> problems) {
        public Result {
            problems = List.copyOf(problems);
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
                throw new IllegalArgumentException(
                        problems.stream()
                                .map(Problem::message)
                                .reduce((a, b) -> a + "; " + b)
                                .orElseThrow());
            return geometry;
        }
    }

    public static Result solve(SketchGeometry input) {
        double[] x =
                input.entities().stream()
                        .flatMap(e -> e.values().stream())
                        .mapToDouble(Double::doubleValue)
                        .toArray();
        SketchGeometry current = input;
        try {
            SketchConstraints.validateGeometry(current);
            double[] residual = residual(current);
            double lambda = 1e-5;
            for (int iteration = 0; iteration < 100 && max(residual) > 1e-8; iteration++) {
                if (Thread.currentThread().isInterrupted())
                    throw new CancellationException("Sketch solve cancelled");
                if (x.length == 0) break;
                var svd =
                        new SingularValueDecomposition(
                                new Array2DRowRealMatrix(
                                        jacobian(input, x, residual.length), false));
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
                        for (int col = 0; col < step.length; col++)
                            step[col] += v.getEntry(col, k) * coefficient;
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
                return new Result(current, x.length, residual.length, List.of());
            var singular =
                    new SingularValueDecomposition(
                                    new Array2DRowRealMatrix(
                                            jacobian(input, x, residual.length), false))
                            .getSingularValues();
            double cutoff = Math.max(1e-7, singular[0] * 1e-8);
            int rank = 0;
            for (double value : singular) if (value > cutoff) rank++;
            return new Result(
                    current,
                    Math.max(0, x.length - rank),
                    Math.max(0, residual.length - rank),
                    List.of());
        } catch (IllegalArgumentException failure) {
            return new Result(input, -1, 0, List.of(new Problem("", failure.getMessage())));
        }
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
        for (var c : g.constraints())
            for (double value : SketchConstraints.residuals(g, c)) values.add(value);
        return values.stream().mapToDouble(Double::doubleValue).toArray();
    }

    private static double[][] jacobian(SketchGeometry definition, double[] x, int rows) {
        double[][] result = new double[rows][x.length];
        for (int col = 0; col < x.length; col++) {
            if (Thread.currentThread().isInterrupted())
                throw new CancellationException("Sketch solve cancelled");
            double h = 1e-6 * Math.max(1, Math.abs(x[col]));
            double[] a = x.clone(), b = x.clone();
            a[col] += h;
            b[col] -= h;
            double[] ra = residual(unpack(definition, a)), rb = residual(unpack(definition, b));
            for (int row = 0; row < rows; row++) result[row][col] = (ra[row] - rb[row]) / (2 * h);
        }
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
