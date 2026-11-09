#include <STEPControl_Reader.hxx>
#include <BRepCheck_Analyzer.hxx>
#include <BRepBndLib.hxx>
#include <BRepGProp.hxx>
#include <BRepClass3d_SolidClassifier.hxx>
#include <Bnd_Box.hxx>
#include <GProp_GProps.hxx>
#include <TopExp_Explorer.hxx>
#include <Standard_Failure.hxx>
#include <gp_Pnt.hxx>
#include <iomanip>
#include <iostream>

// Independent file reader used only by export acceptance tests, never by the writer or JNI.
int main(int argc, char** argv) {
    if (argc != 2) return 2;
    try {
        STEPControl_Reader reader;
        if (reader.ReadFile(argv[1]) != IFSelect_RetDone || reader.TransferRoots() == 0) return 3;
        const auto shape = reader.OneShape();
        if (shape.IsNull() || !BRepCheck_Analyzer(shape).IsValid()) return 4;
        int solids = 0;
        bool holeContainsMaterial = false;
        for (TopExp_Explorer it(shape, TopAbs_SOLID); it.More(); it.Next()) {
            ++solids;
            BRepClass3d_SolidClassifier classifier(it.Current(), gp_Pnt(10, 10, 4), 1e-7);
            holeContainsMaterial |=
                classifier.State() == TopAbs_IN || classifier.State() == TopAbs_ON;
        }
        GProp_GProps properties;
        BRepGProp::VolumeProperties(shape, properties);
        Bnd_Box bounds;
        BRepBndLib::AddOptimal(shape, bounds, false, false);
        double x0, y0, z0, x1, y1, z1;
        bounds.Get(x0, y0, z0, x1, y1, z1);
        std::cout << std::setprecision(17) << "solids=" << solids
                  << "\nvolume=" << properties.Mass() << "\nbounds=" << x0 << ',' << y0 << ',' << z0
                  << ',' << x1 << ',' << y1 << ',' << z1
                  << "\nholeContainsMaterial=" << holeContainsMaterial << '\n';
        return 0;
    } catch (const Standard_Failure& error) {
        std::cerr << error.GetMessageString() << '\n';
        return 5;
    }
}
