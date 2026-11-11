rootProject.name = "voxelbrew"

include(
    "util",
    "engine",
    "api:model",
    "api:import",
    "api:export",
    "api:slicer",
    "api:printer",
    "impl:importer",
    "impl:exporter",
    "impl:slicer",
    "impl:printer",
    "gui",
    "cli",
)
