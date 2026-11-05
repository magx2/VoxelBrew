package dev.voxelbrew.engine.geometry;

import java.nio.file.Path;

/** Internal JNI boundary. Tokens are registry IDs, never exposed native pointers. */
final class NativeKernel {
    static {
        String directory = System.getProperty("voxelbrew.native.path");
        try {
            if (directory == null || directory.isBlank()) {
                System.loadLibrary("voxelbrew_native");
            } else {
                Path runtime = Path.of(directory).toAbsolutePath();
                if (System.getProperty("os.name").startsWith("Windows")) {
                    for (String library :
                            new String[] {
                                "TKernel",
                                "TKMath",
                                "TKG2d",
                                "TKG3d",
                                "TKGeomBase",
                                "TKBRep",
                                "TKGeomAlgo",
                                "TKTopAlgo",
                                "TKPrim",
                                "TKShHealing",
                                "TKBO",
                                "TKBool",
                                "TKMesh"
                            }) {
                        System.load(runtime.resolve(library + ".dll").toString());
                    }
                }
                System.load(runtime.resolve(System.mapLibraryName("voxelbrew_native")).toString());
            }
        } catch (UnsatisfiedLinkError failure) {
            throw new UnsatisfiedLinkError(
                    "VoxelBrew native kernel could not load for "
                            + System.getProperty("os.name")
                            + "/"
                            + System.getProperty("os.arch")
                            + ". Run the native bootstrap and :engine:buildNative; set -Dvoxelbrew.native.path to its runtime directory. "
                            + failure.getMessage());
        }
    }

    private NativeKernel() {}

    static native String version();

    static native long rectanglePrism(
            double x, double y, double z, double width, double height, double distance);

    static native long circlePrism(double x, double y, double z, double diameter, double distance);

    static native long profilePrism(
            int[] types, int[] loops, int[] sizes, double[] parameters, double z, double distance);

    static native long cutThroughProfile(
            long body, int[] types, int[] loops, int[] sizes, double[] parameters);

    static native long cutThroughCircle(long body, double x, double y, double diameter);

    static native long[] split(long body, double nx, double ny, double nz, double offset);

    static native double volume(long body);

    static native double[] bounds(long body);

    static native boolean contains(long body, double x, double y, double z);

    static native void release(long body);

    static native int liveHandles();

    static native dev.voxelbrew.engine.mesh.MeshData tessellate(
            long body, double chord, double angleRadians);
}
