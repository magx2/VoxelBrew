# M2 mesh export and external providers

Format implementations and exporter discovery live in the `export` project.
The versioned SPI and read-only `ModelView`/`ManufacturingView` contracts live in `:api:export`.
`ModelViews` adapters and selection live in `export`; shared CAD placements stay in engine.
See [file-I/O APIs and boundaries](file-io.md).

The installed `cli`/`cli.bat` supports:

```text
cli exporters --plugin-dir "path with spaces/plugins"
cli export part.jcad --format stl --body thickness.body --output part.stl
cli export part.jcad --format 3mf --output part.3mf --chord-tolerance 0.02 --angular-tolerance 5
cli export part.jcad --format probe --plugin-dir "path with spaces/plugins" --option label=custom --output report.txt
```

`--body` is optional for a single body. STL requires one instance; 3MF preserves
multiple named body resources and build placements. Coordinates and chordal
tolerance are mm; angular tolerance is degrees. Defaults are 0.02 mm and 5 degrees.
Use `--overwrite` explicitly to replace an export. Failed writes preserve the old
destination. New output uses atomic create-if-absent publication; replacement
requires atomic rename. Broken or rolled-back documents cannot be exported.

`:api:export` publishes `dev.voxelbrew:export-api:0.1.0-SNAPSHOT` via `maven-publish`.
`./gradlew :engine:buildExporterFixture` publishes both APIs and their engine/util
dependencies to a local Maven repository under `engine/build/repository`. `fixtures/exporter-plugin` is an
independent Gradle consumer, not an application subproject. Root `check` compiles
its JAR against the published API and tests discovery without recompiling the app.

Implement `dev.voxelbrew.exporter.api.ModelExporter` (SPI version 1) and list
provider classes in `META-INF/services/dev.voxelbrew.exporter.api.ModelExporter`.
Descriptors declare stable format ID, display name, extensions, single/multiple
instance capability, and STRING/BOOLEAN/INTEGER/DECIMAL/CHOICE options. Options are
validated and defaults applied before writing. The host owns the model view and
output stream; providers must not retain them. Returned meshes are immutable and
detached. Plugin contracts contain no UI types, Protobuf messages, or native handles.

Discovery uses a closeable dedicated class loader over trusted JARs in
`~/.voxelbrew/plugins`, overridden by `--plugin-dir`. Built-ins use the same
ServiceLoader registry. Duplicate IDs retain the first provider; incompatible
providers and load failures produce diagnostics while valid providers remain usable.
The fixture deliberately includes duplicate, incompatible, missing, and failed
providers to exercise these paths. Plugins execute with the application's permissions.

OCCT meshing copies topology and geometry before attaching triangulations. Face
indices identify only the evaluated mesh, not persistent sketch supports. The
bridge limits a mesh to four million vertices/two million triangles and diagnoses
overflow; increase tolerances to reduce density. Mesh preflight rejects open,
degenerate or inconsistently oriented solids after welding on a 1e-7 mm grid.
3MF also shares welded vertex indices. Read-only views can carry rigid placements;
M4 adds persisted build placements and explicit design/build-space selection.
See [printing and support exchange](printing-workflow.md).

3MF is a portable Core geometry package, not a vendor slicer project. Slicer
settings, supports, G-code and bundled-JVM installers are outside M2.
