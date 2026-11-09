cmake_minimum_required(VERSION 3.31.6)
project(VoxelBrewNative VERSION 0.1.0 LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
find_package(JNI REQUIRED COMPONENTS JVM)
find_package(OpenCASCADE 7.9.0 EXACT REQUIRED COMPONENTS FoundationClasses ModelingData ModelingAlgorithms Visualization ApplicationFramework DataExchange)
add_library(voxelbrew_native SHARED src/bridge.cpp)
target_include_directories(voxelbrew_native PRIVATE ${JNI_INCLUDE_DIRS} ${OpenCASCADE_INCLUDE_DIR})
target_link_directories(voxelbrew_native PRIVATE ${OpenCASCADE_LIBRARY_DIR})
target_link_libraries(voxelbrew_native PRIVATE TKDESTEP TKXSBase TKOffset TKFillet TKMesh TKBool TKBO TKShHealing TKPrim TKTopAlgo TKGeomAlgo TKBRep TKGeomBase TKG3d TKG2d TKMath TKernel)
set_target_properties(voxelbrew_native PROPERTIES
    RUNTIME_OUTPUT_DIRECTORY "${VOXELBREW_RUNTIME}"
    RUNTIME_OUTPUT_DIRECTORY_RELEASE "${VOXELBREW_RUNTIME}"
    LIBRARY_OUTPUT_DIRECTORY "${VOXELBREW_RUNTIME}"
    BUILD_WITH_INSTALL_RPATH TRUE
    INSTALL_RPATH "$ORIGIN;@loader_path")
if(MSVC)
    target_compile_options(voxelbrew_native PRIVATE /W4 /EHsc)
else()
    target_compile_options(voxelbrew_native PRIVATE -Wall -Wextra -Wpedantic)
endif()

# Separate read-side acceptance tool. Kept outside the redistributed runtime directory.
add_executable(voxelbrew_step_inspect tests/step_inspect.cpp)
target_include_directories(voxelbrew_step_inspect PRIVATE ${OpenCASCADE_INCLUDE_DIR})
target_link_directories(voxelbrew_step_inspect PRIVATE ${OpenCASCADE_LIBRARY_DIR})
target_link_libraries(voxelbrew_step_inspect PRIVATE TKDESTEP TKXSBase TKBRep TKTopAlgo TKMath TKernel)
set_target_properties(voxelbrew_step_inspect PROPERTIES
    RUNTIME_OUTPUT_DIRECTORY "${VOXELBREW_RUNTIME}/../validation"
    RUNTIME_OUTPUT_DIRECTORY_RELEASE "${VOXELBREW_RUNTIME}/../validation"
    BUILD_WITH_INSTALL_RPATH TRUE
    INSTALL_RPATH "$ORIGIN/../runtime;@loader_path/../runtime")
