#include <jni.h>
#include <BRepAlgoAPI_Cut.hxx>
#include <BRepAlgoAPI_Splitter.hxx>
#include <BRepBndLib.hxx>
#include <BRepBuilderAPI_MakeFace.hxx>
#include <BRepBuilderAPI_Copy.hxx>
#include <BRepMesh_IncrementalMesh.hxx>
#include <BRep_Tool.hxx>
#include <Poly_Triangulation.hxx>
#include <TopoDS.hxx>
#include <TopLoc_Location.hxx>
#include <BRepBuilderAPI_MakePolygon.hxx>
#include <BRepCheck_Analyzer.hxx>
#include <BRepClass3d_SolidClassifier.hxx>
#include <BRepGProp.hxx>
#include <BRepPrimAPI_MakeCylinder.hxx>
#include <BRepPrimAPI_MakePrism.hxx>
#include <Bnd_Box.hxx>
#include <GProp_GProps.hxx>
#include <Standard_Failure.hxx>
#include <Standard_Version.hxx>
#include <TopExp_Explorer.hxx>
#include <TopTools_ListOfShape.hxx>
#include <TopoDS_Shape.hxx>
#include <gp_Ax2.hxx>
#include <gp_Dir.hxx>
#include <gp_Pnt.hxx>
#include <gp_Pln.hxx>
#include <gp_Vec.hxx>
#include <cmath>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>
#include <algorithm>

namespace {
std::mutex registryMutex;
std::unordered_map<jlong, std::shared_ptr<const TopoDS_Shape>> registry;
jlong nextId = 1;

void diagnostic(JNIEnv* env, const char* operation, const char* message) {
    if (env->ExceptionCheck()) return;
    const std::string text =
        std::string(operation) + ": " + (message ? message : "unknown kernel failure");
    jclass type = env->FindClass("dev/voxelbrew/engine/geometry/GeometryException");
    if (type) env->ThrowNew(type, text.c_str());
}

template <typename T, typename F>
T guarded(JNIEnv* env, const char* operation, T fallback, F action) {
    try {
        return action();
    } catch (const Standard_Failure& e) {
        diagnostic(env, operation, e.GetMessageString());
    } catch (const std::exception& e) {
        diagnostic(env, operation, e.what());
    } catch (...) {
        diagnostic(env, operation, "unrecognized native failure");
    }
    return fallback;
}

std::shared_ptr<const TopoDS_Shape> lookup(jlong id) {
    std::lock_guard<std::mutex> lock(registryMutex);
    const auto entry = registry.find(id);
    if (entry == registry.end()) throw std::invalid_argument("unknown or closed solid handle");
    return entry->second;
}

jlong own(const TopoDS_Shape& shape) {
    if (shape.IsNull() || !BRepCheck_Analyzer(shape).IsValid())
        throw std::runtime_error("result is not a valid B-rep");
    int solids = 0;
    for (TopExp_Explorer it(shape, TopAbs_SOLID); it.More(); it.Next()) ++solids;
    if (solids != 1)
        throw std::runtime_error("operation must produce exactly one solid; got " +
                                 std::to_string(solids));
    auto owned = std::make_shared<const TopoDS_Shape>(shape);
    std::lock_guard<std::mutex> lock(registryMutex);
    const jlong id = nextId++;
    registry.emplace(id, std::move(owned));
    return id;
}

void requireFinite(double value) {
    if (!std::isfinite(value)) throw std::invalid_argument("dimensions must be finite");
}
void requirePositive(double value) {
    requireFinite(value);
    if (value < 1e-6) throw std::invalid_argument("dimension must be >= 0.000001 mm");
}
Bnd_Box exactBounds(const TopoDS_Shape& shape) {
    Bnd_Box box;
    BRepBndLib::AddOptimal(shape, box, false, false);
    if (box.IsVoid() || box.IsOpen()) throw std::runtime_error("solid has no finite bounds");
    return box;
}
}  // namespace

extern "C" {
JNIEXPORT jstring JNICALL Java_dev_voxelbrew_engine_geometry_NativeKernel_version(JNIEnv* env,
                                                                                  jclass) {
    return env->NewStringUTF(OCC_VERSION_COMPLETE);
}

JNIEXPORT jlong JNICALL Java_dev_voxelbrew_engine_geometry_NativeKernel_rectanglePrism(
    JNIEnv* env, jclass, jdouble x, jdouble y, jdouble z, jdouble w, jdouble h, jdouble d) {
    return guarded<jlong>(env, "rectangle extrusion", 0, [&] {
        requireFinite(x);
        requireFinite(y);
        requireFinite(z);
        requirePositive(w);
        requirePositive(h);
        requirePositive(std::abs(d));
        requireFinite(x + w);
        requireFinite(y + h);
        requireFinite(z + d);
        BRepBuilderAPI_MakePolygon wire;
        wire.Add(gp_Pnt(x, y, z));
        wire.Add(gp_Pnt(x + w, y, z));
        wire.Add(gp_Pnt(x + w, y + h, z));
        wire.Add(gp_Pnt(x, y + h, z));
        wire.Close();
        if (!wire.IsDone()) throw std::runtime_error("rectangle wire construction failed");
        BRepBuilderAPI_MakeFace face(wire.Wire());
        if (!face.IsDone()) throw std::runtime_error("rectangle face construction failed");
        BRepPrimAPI_MakePrism prism(face.Face(), gp_Vec(0, 0, d));
        prism.Build();
        if (!prism.IsDone()) throw std::runtime_error("prism construction failed");
        return own(prism.Shape());
    });
}

JNIEXPORT jlong JNICALL Java_dev_voxelbrew_engine_geometry_NativeKernel_cutThroughCircle(
    JNIEnv* env, jclass, jlong id, jdouble x, jdouble y, jdouble diameter) {
    return guarded<jlong>(env, "through-all circle cut", 0, [&] {
        requireFinite(x);
        requireFinite(y);
        requirePositive(diameter);
        const auto body = lookup(id);
        const auto box = exactBounds(*body);
        double x0, y0, z0, x1, y1, z1;
        box.Get(x0, y0, z0, x1, y1, z1);
        // Overshoot both ends so the result is independent of the original plate thickness.
        BRepPrimAPI_MakeCylinder tool(gp_Ax2(gp_Pnt(x, y, z0 - 1), gp_Dir(0, 0, 1)), diameter / 2,
                                      z1 - z0 + 2);
        TopTools_ListOfShape arguments;
        arguments.Append(*body);
        TopTools_ListOfShape tools;
        tools.Append(tool.Shape());
        BRepAlgoAPI_Cut cut;
        cut.SetArguments(arguments);
        cut.SetTools(tools);
        cut.SetRunParallel(false);
        cut.SetNonDestructive(true);
        cut.Build();
        if (!cut.IsDone() || cut.HasErrors())
            throw std::runtime_error("boolean subtraction failed");
        return own(cut.Shape());
    });
}

JNIEXPORT jlong JNICALL Java_dev_voxelbrew_engine_geometry_NativeKernel_circlePrism(
    JNIEnv* env, jclass, jdouble x, jdouble y, jdouble z, jdouble diameter, jdouble distance) {
    return guarded<jlong>(env, "circle extrusion", 0, [&] {
        requireFinite(x);
        requireFinite(y);
        requireFinite(z);
        requireFinite(z + distance);
        requirePositive(diameter);
        requirePositive(std::abs(distance));
        BRepPrimAPI_MakeCylinder cylinder(
            gp_Ax2(gp_Pnt(x, y, z), gp_Dir(0, 0, distance > 0 ? 1 : -1)), diameter / 2,
            std::abs(distance));
        cylinder.Build();
        if (!cylinder.IsDone()) throw std::runtime_error("cylinder construction failed");
        return own(cylinder.Shape());
    });
}

JNIEXPORT jlongArray JNICALL Java_dev_voxelbrew_engine_geometry_NativeKernel_split(
    JNIEnv* env, jclass, jlong id, jdouble nx, jdouble ny, jdouble nz, jdouble offset) {
    return guarded<jlongArray>(env, "split body", nullptr, [&]() -> jlongArray {
        requireFinite(nx);
        requireFinite(ny);
        requireFinite(nz);
        requireFinite(offset);
        if (std::abs(std::hypot(std::hypot(nx, ny), nz) - 1) > 1e-9)
            throw std::invalid_argument("split plane normal must have unit length");
        const auto source = lookup(id);
        TopTools_ListOfShape arguments, tools;
        arguments.Append(*source);
        tools.Append(BRepBuilderAPI_MakeFace(
                         gp_Pln(gp_Pnt(nx * offset, ny * offset, nz * offset), gp_Dir(nx, ny, nz)))
                         .Face());
        BRepAlgoAPI_Splitter splitter;
        splitter.SetArguments(arguments);
        splitter.SetTools(tools);
        splitter.SetNonDestructive(true);
        splitter.Build();
        if (!splitter.IsDone() || splitter.HasErrors())
            throw std::runtime_error("plane split failed");
        std::vector<TopoDS_Shape> parts;
        for (TopExp_Explorer it(splitter.Shape(), TopAbs_SOLID); it.More(); it.Next())
            parts.push_back(it.Current());
        if (parts.size() != 2)
            throw std::runtime_error(parts.size() < 2 ? "plane does not intersect the body"
                                                      : "plane produces more than two solids");
        auto side = [&](const TopoDS_Shape& shape) {
            GProp_GProps props;
            BRepGProp::VolumeProperties(shape, props);
            const auto c = props.CentreOfMass();
            return nx * c.X() + ny * c.Y() + nz * c.Z() - offset;
        };
        if (side(parts[0]) > side(parts[1])) std::swap(parts[0], parts[1]);
        if (!(side(parts[0]) < -1e-7 && side(parts[1]) > 1e-7))
            throw std::runtime_error("plane does not separate two nonempty sides");
        auto result = env->NewLongArray(2);
        if (!result) return nullptr;
        jlong handles[2] = {0, 0};
        try {
            handles[0] = own(parts[0]);
            handles[1] = own(parts[1]);
            env->SetLongArrayRegion(result, 0, 2, handles);
            if (!env->ExceptionCheck()) return result;
        } catch (...) {
            std::lock_guard<std::mutex> lock(registryMutex);
            registry.erase(handles[0]);
            registry.erase(handles[1]);
            throw;
        }
        std::lock_guard<std::mutex> lock(registryMutex);
        registry.erase(handles[0]);
        registry.erase(handles[1]);
        return nullptr;
    });
}

JNIEXPORT jdouble JNICALL Java_dev_voxelbrew_engine_geometry_NativeKernel_volume(JNIEnv* env,
                                                                                 jclass, jlong id) {
    return guarded<jdouble>(env, "volume", 0, [&] {
        GProp_GProps properties;
        BRepGProp::VolumeProperties(*lookup(id), properties, true, false, false);
        return properties.Mass();
    });
}

JNIEXPORT jobject JNICALL Java_dev_voxelbrew_engine_geometry_NativeKernel_tessellate(
    JNIEnv* env, jclass, jlong id, jdouble chord, jdouble angle) {
    return guarded<jobject>(env, "tessellation", nullptr, [&]() -> jobject {
        requirePositive(chord);
        requirePositive(angle);
        if (angle > 3.14159265358979323846)
            throw std::invalid_argument("angular tolerance exceeds pi");
        // Meshing attaches mutable triangulations to faces. Never mesh registry-owned topology.
        BRepBuilderAPI_Copy copy(*lookup(id), true, false);
        TopoDS_Shape shape = copy.Shape();
        BRepMesh_IncrementalMesh mesher(shape, chord, false, angle, false);
        if (!mesher.IsDone() || mesher.GetStatusFlags() != 0)
            throw std::runtime_error("meshing failed");
        std::vector<jdouble> vertices;
        std::vector<jint> indices, faces;
        jint faceIndex = 0;
        for (TopExp_Explorer it(shape, TopAbs_FACE); it.More(); it.Next(), ++faceIndex) {
            const auto face = TopoDS::Face(it.Current());
            TopLoc_Location location;
            const auto mesh = BRep_Tool::Triangulation(face, location);
            if (mesh.IsNull()) throw std::runtime_error("face has no triangulation");
            if (vertices.size() / 3 + mesh->NbNodes() > 4000000 ||
                faces.size() + mesh->NbTriangles() > 2000000)
                throw std::runtime_error(
                    "mesh exceeds safety limit; increase tessellation tolerance");
            const auto base = static_cast<jint>(vertices.size() / 3);
            for (int n = 1; n <= mesh->NbNodes(); ++n) {
                const gp_Pnt point = mesh->Node(n).Transformed(location.Transformation());
                vertices.insert(vertices.end(), {point.X(), point.Y(), point.Z()});
            }
            for (int n = 1; n <= mesh->NbTriangles(); ++n) {
                int a, b, c;
                mesh->Triangle(n).Get(a, b, c);
                if (face.Orientation() == TopAbs_REVERSED) std::swap(b, c);
                indices.insert(indices.end(), {base + a - 1, base + b - 1, base + c - 1});
                faces.push_back(faceIndex);
            }
        }
        auto points = env->NewDoubleArray(static_cast<jsize>(vertices.size()));
        if (!points) return nullptr;
        auto triangles = env->NewIntArray(static_cast<jsize>(indices.size()));
        if (!triangles) return nullptr;
        auto faceIds = env->NewIntArray(static_cast<jsize>(faces.size()));
        if (!faceIds) return nullptr;
        env->SetDoubleArrayRegion(points, 0, static_cast<jsize>(vertices.size()), vertices.data());
        env->SetIntArrayRegion(triangles, 0, static_cast<jsize>(indices.size()), indices.data());
        env->SetIntArrayRegion(faceIds, 0, static_cast<jsize>(faces.size()), faces.data());
        auto type = env->FindClass("dev/voxelbrew/engine/mesh/MeshData");
        if (!type || env->ExceptionCheck()) return nullptr;
        auto constructor = env->GetMethodID(type, "<init>", "([D[I[I)V");
        return constructor ? env->NewObject(type, constructor, points, triangles, faceIds)
                           : nullptr;
    });
}

JNIEXPORT jdoubleArray JNICALL Java_dev_voxelbrew_engine_geometry_NativeKernel_bounds(JNIEnv* env,
                                                                                      jclass,
                                                                                      jlong id) {
    return guarded<jdoubleArray>(env, "bounds", nullptr, [&] {
        double values[6];
        exactBounds(*lookup(id))
            .Get(values[0], values[1], values[2], values[3], values[4], values[5]);
        auto result = env->NewDoubleArray(6);
        if (result) env->SetDoubleArrayRegion(result, 0, 6, values);
        return result;
    });
}

JNIEXPORT jboolean JNICALL Java_dev_voxelbrew_engine_geometry_NativeKernel_contains(
    JNIEnv* env, jclass, jlong id, jdouble x, jdouble y, jdouble z) {
    return guarded<jboolean>(env, "point classification", JNI_FALSE, [&] {
        requireFinite(x);
        requireFinite(y);
        requireFinite(z);
        BRepClass3d_SolidClassifier classifier(*lookup(id), gp_Pnt(x, y, z), 1e-7);
        return static_cast<jboolean>(classifier.State() == TopAbs_IN ||
                                     classifier.State() == TopAbs_ON);
    });
}

JNIEXPORT void JNICALL Java_dev_voxelbrew_engine_geometry_NativeKernel_release(JNIEnv* env, jclass,
                                                                               jlong id) {
    guarded<int>(env, "release", 0, [&] {
        std::lock_guard<std::mutex> lock(registryMutex);
        registry.erase(id);
        return 0;
    });
}

JNIEXPORT jint JNICALL Java_dev_voxelbrew_engine_geometry_NativeKernel_liveHandles(JNIEnv* env,
                                                                                   jclass) {
    return guarded<jint>(env, "live handle count", 0, [&] {
        std::lock_guard<std::mutex> lock(registryMutex);
        return static_cast<jint>(registry.size());
    });
}
}  // extern C
