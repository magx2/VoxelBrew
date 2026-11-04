package dev.voxelbrew.cli;

import dev.voxelbrew.engine.geometry.Solid;
import java.util.Locale;

/** M0 headless kernel smoke demo. Editable commands follow in M1. */
public final class VoxelBrew {
    private VoxelBrew() { }

    public static void main(String[] args) {
        if (args.length != 1 || !"native-demo".equals(args[0])) {
            System.err.println("Usage: voxelbrew native-demo");
            System.exit(2);
        }
        try (Solid plate = Solid.rectanglePrism(0, 0, 0, 60, 40, 8);
             Solid drilled = plate.cutThroughCircle(10, 10, 4)) {
            System.out.printf(Locale.ROOT, "OCCT %s%nvolume=%.12f mm^3%nbounds=%s%nthroughHole=%s%n",
                    Solid.kernelVersion(), drilled.volume(), drilled.bounds(), !drilled.contains(10, 10, 4));
        }
    }
}
