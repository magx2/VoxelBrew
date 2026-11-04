package dev.voxelbrew.engine.export;

import java.util.Arrays;
import java.util.stream.Collectors;

/** Rigid, orientation-preserving 3x4 row-major placement. Distances are mm. */
public final class Transform {
    public static final Transform IDENTITY = new Transform(1,0,0,0, 0,1,0,0, 0,0,1,0);
    private final double[] m;
    public Transform(double... matrix) {
        if (matrix.length != 12) throw new IllegalArgumentException("Placement requires 12 values");
        m = matrix.clone();
        for (double v : m) if (!Double.isFinite(v)) throw new IllegalArgumentException("Nonfinite placement");
        for (int r = 0; r < 3; r++) for (int s = 0; s < 3; s++) {
            double dot = 0;
            for (int c = 0; c < 3; c++) dot += m[4*r+c]*m[4*s+c];
            if (Math.abs(dot - (r == s ? 1 : 0)) > 1e-9) throw new IllegalArgumentException("Placement must be rigid");
        }
        double det = m[0]*(m[5]*m[10]-m[6]*m[9])-m[1]*(m[4]*m[10]-m[6]*m[8])+m[2]*(m[4]*m[9]-m[5]*m[8]);
        if (det < 0) throw new IllegalArgumentException("Placement must preserve orientation");
    }
    public static Transform translation(double x, double y, double z) { return new Transform(1,0,0,x, 0,1,0,y, 0,0,1,z); }
    public double[] apply(double x, double y, double z) {
        return new double[] {m[0]*x+m[1]*y+m[2]*z+m[3], m[4]*x+m[5]*y+m[6]*z+m[7], m[8]*x+m[9]*y+m[10]*z+m[11]};
    }
    public String threeMfValues() {
        return Arrays.stream(new double[] {m[0],m[4],m[8],m[1],m[5],m[9],m[2],m[6],m[10],m[3],m[7],m[11]})
                .mapToObj(Double::toString).collect(Collectors.joining(" "));
    }
}
