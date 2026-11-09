# File import and export modules

The `import` and `export` Gradle projects separate file I/O from the CAD engine.
The Java packages are `dev.voxelbrew.importer` and `dev.voxelbrew.exporter`;
`import` is a Java keyword, so it cannot be a package segment.

| Project    | Owns                                                                                    | Production dependencies          |
| ---------- | --------------------------------------------------------------------------------------- | -------------------------------- |
| util       | Configuration, translations, logging, updates, generic workers                          | No other project                 |
| engine     | Native geometry, evaluation, tessellation, document codec, feature execution            | api:model, util                  |
| api:model  | Shared document, sketch, parameter, mesh and manufacturing values                       | No application modules           |
| api:import | FileImporter SPI, descriptors, typed options/results, DocumentSource/GcodeSource ports  | api:model                        |
| api:export | ModelExporter and SlicerBackend SPIs, descriptors/options, DocumentSink port            | api:model                        |
| import     | Bounded .jcad/G-code reading, importer discovery and invocation                         | engine, api:import, util         |
| export     | Atomic .jcad writing, STL/3MF writers, exporter discovery, slicer execution/publication | engine, api:export, import, util |
| cli / gui  | Commands and desktop interactions                                                       | engine, import, export, util     |

The API projects live in `api/model`, `api/import` and `api/export`. Their binary and source JARs
retain the `-api` suffix and bundle `META-INF/LICENSE` under MIT. See the
[licensing rationale](../api/READEME.md).

The APIs contain only public contracts and their value types. Concrete format classes,
registries, services and ServiceLoader registrations stay in `import` / `export`.
Engine depends on api/model, but not on the file-I/O APIs or implementations. Export inputs
`ModelView` and `ManufacturingView` live in `dev.voxelbrew.exporter.api`; the concrete
`ModelViews` adapters and `ExportSelection` live in `dev.voxelbrew.exporter`. Shared CAD
placements (`BodyInstance` and `Transform`) stay in `dev.voxelbrew.engine.model`.
`ManufacturingModel` is a detached CAD/manufacturing snapshot, independent of the export API.
The shared classes retain their `dev.voxelbrew.engine.*` package names for source
compatibility, but ship only in `model-api`. Native manufacturing factories moved
to engine's `ManufacturingModels.evaluate/from`; `ManufacturingModel` contains only
the detached value and mesh helpers. Document IDs use the API-owned
`dev.voxelbrew.engine.model.UuidGenerator`.

`verifyApiDependencies` rejects application project dependencies on API compile/runtime
classpaths. The independent fixture compiles import/export providers against only
model-api/import-api/export-api; native feature providers compile separately against engine.
All services are headless.
STL mesh-to-editable-CAD and STEP import remain outside the implemented workflow.

## Public file operations

```java
import dev.voxelbrew.importer.DocumentReader;
import dev.voxelbrew.importer.GcodeReader;
import dev.voxelbrew.exporter.DocumentWriter;
import dev.voxelbrew.exporter.ExporterRegistry;
import dev.voxelbrew.exporter.ExportService;

var document = DocumentReader.load(source);
DocumentWriter.save(document, destination, false);
var preview = GcodeReader.read(gcodeFile);
```

Replace `DocumentStore.load/save` with `DocumentReader.load/DocumentWriter.save`.
Schema version and stable revision fingerprints are exposed by
`dev.voxelbrew.engine.persistence.DocumentCodec`; it also provides in-memory
`encode/decode` and never accesses files. `.jcad` schema 7 retains the optional
`application_version` string at field 18, populated from `Version.version()` on
every save. Existing field numbers, checksums, opaque provider payloads and CAD
fingerprints retain their meanings. Writer metadata is excluded from fingerprints.
Reads enforce
the 16 MiB payload limit before parsing, even if a file grows during reading.
Writes retain explicit overwrite, unsupported-file protection, atomic replacement
and atomic create-if-absent behavior.

Files from older application versions, including legacy files with no writer
metadata, open through the existing schema readers. Opening never rewrites the
source; the next explicit save upgrades the schema and stamps the current writer
version. The document/feature IDs, parameters, print setup and opaque plugin data
are preserved. No Save As copy is required, and normal Save As remains available.
Files declaring a newer application version are rejected with an error naming
the saved and supported versions and asking the user to update VoxelBrew. Explicit
overwrite cannot bypass this check. Malformed writer versions, newer schemas and
unknown fields are also rejected. GUI failures keep the current document open;
CLI commands return a nonzero exit code without writing.

Version comparison is numeric across major/minor/patch components; a final
release follows its `-SNAPSHOT`. IDE/class-directory runs use `0.0.0` for both
saving and comparison, as returned by `Version.version()`; they do not bypass the
newer-file guard. Use a built distribution to open files from a released version.

`BinaryStlExporter`, `ThreeMfExporter`, `ExporterRegistry` and `ExportService` now
live in `dev.voxelbrew.exporter`. Prusa project/configuration writers, the backend,
slicer registry and `SliceService` live in `dev.voxelbrew.exporter.printing`.
`GcodeJob` stays an immutable engine preview model; use `GcodeReader.read` to load it.

Use `ModelViews.from(evaluation)` for design geometry, keeping the owning evaluation open
throughout export. `ModelViews.from(manufacturingModel)` adapts an already detached snapshot
and exposes `ManufacturingView.manufacturingModel()` for project exporters that need the
complete print setup. Selecting bodies, instances, or support solids returns a plain
`ModelView`; it cannot be used as a complete slicer project. `ExportSelection.Contents`
controls model/support/all geometry selection. Format-specific transform serialization
(including 3MF matrix ordering) belongs to the format writer, not the engine placement type.

## Authoring an external provider

Compile against `dev.voxelbrew:import-api:<version>` or `dev.voxelbrew:export-api:<version>`
with `compileOnly` (Maven `provided` scope). Shared MIT model types are available transitively; engine and util are absent from these API dependencies.
Do not bundle host API classes into a plugin JAR. No dependency on import/export, JavaFX,
picocli, Protobuf classes or Dagger is needed. This source migration intentionally has no
old-package shims: recompile existing providers and rename their service resources.

| Extension    | Public interface                                    | JAR service resource                                                  |
| ------------ | --------------------------------------------------- | --------------------------------------------------------------------- |
| Import       | `dev.voxelbrew.importer.api.FileImporter`           | `META-INF/services/dev.voxelbrew.importer.api.FileImporter`           |
| Model export | `dev.voxelbrew.exporter.api.ModelExporter`          | `META-INF/services/dev.voxelbrew.exporter.api.ModelExporter`          |
| Slicing      | `dev.voxelbrew.exporter.api.printing.SlicerBackend` | `META-INF/services/dev.voxelbrew.exporter.api.printing.SlicerBackend` |

Each service file lists public, no-argument provider classes, one per line. Implement the
interface and return a descriptor with a unique ID, supported API version, extensions,
capabilities/result kind and STRING/BOOLEAN/INTEGER/DECIMAL/CHOICE options. Declare defaults
in the descriptor; the host validates options before invoking a provider. Importers return
`ImportResult.Document(CadDocument)` or `ImportResult.Gcode(GcodeJob)`; declare the matching
result kind. A document importer must create actual editable definitions, not disguise
triangles as feature history. Providers must not retain borrowed streams/model views.
Imports use host-owned bounded input (256 MiB maximum, with .jcad's smaller codec limit).
Exports use host-owned output and atomic publication only after success.

`ImporterRegistry`/`ImportService` and `ExporterRegistry`/`ExportService` are host services in
the implementation projects. Trusted JARs are discovered using dedicated, closeable
ServiceLoader class loaders in `~/.voxelbrew/plugins` or `--plugin-dir`. Plugins execute with
the application's permissions. Duplicate IDs retain the first provider; incompatible APIs
and broken providers are diagnosed without disabling unrelated providers. Invocation errors
name the provider. Import never writes its source. Built-in `.jcad` and G-code importers and
STL/3MF exporters use the same discovery mechanism.

`fixtures/exporter-plugin` is an independent Gradle build with published API dependencies
only. It includes `consumer.ProbeImporter` (CSV `width,height,thickness` in a `.vbplate` file,
with a `scale` option) and `consumer.ProbeExporter` (a mesh report with a `label` option).
`./gradlew :engine:buildExporterFixture` publishes engine/util and all three APIs to
`engine/build/repository`, then compiles that consumer. For Maven-local development run
`./gradlew :util:publishToMavenLocal :engine:publishToMavenLocal :api:model:publishToMavenLocal :api:import:publishToMavenLocal :api:export:publishToMavenLocal`.
The API artifacts also publish source JARs. Copy the consumer JAR into a trusted plugin directory.

```text
cli importers --plugin-dir "path with spaces/plugins"
cli import plate.vbplate --format plate --option scale=2 --plugin-dir "path with spaces/plugins" --output plate.jcad
cli import part.gcode --format gcode
cli exporters --plugin-dir "path with spaces/plugins"
cli export plate.jcad --format probe --option label=custom --plugin-dir "path with spaces/plugins" --output report.txt
```

Document imports require a separate `--output`; existing destinations require `--overwrite`.
G-code import prints toolpath counts and rejects `--output`, because it is not editable CAD.
GUI **File → Import…** (`Ctrl+I`) lists document providers and generates option editors from
their descriptors. Reads run on the file worker; success opens an unsaved design, and failure
keeps the current document. Save/Discard/Cancel protects unsaved work before importing. Toolpath
importers are available through the host API and CLI; the GUI import dialog is for documents.

## Distribution and verification

Both API libraries and the implementations are published alongside engine/util and automatically included
in development distributions and bundled images. Installation metadata identifies
`importJar`, `exportJar`, `model-apiJar`, `import-apiJar` and `export-apiJar`; the updater checks their presence and build versions.
Older format-1 installation metadata remains readable for recovery/downgrades.

Focused checks:

```text
./gradlew :api:import:test :api:export:test :import:test :import:integrationTest :export:test :export:integrationTest
```

The compatibility fixture in `export/src/test/test/resources/fixtures/` was
generated by the previously installed `efe2535` build. Its complete file SHA-256 is
`0481466803177edae0ca10582497b7e60b698fe2d15f347f78c0ba679aa80570` and document
fingerprint is `df3bc6b5f8389380bf29db8af9e3618043d57dda4176b669883c560d643f3476`.
Tests verify unchanged legacy definitions and fingerprints after adding writer
metadata, repeat-save byte equality, older-writer upgrades, future-writer rejection,
schema-1 compatibility, corrupted and
oversized inputs, concurrent create races and failed writes preserving destinations.
Existing independent STL/3MF readers, external providers and real-slicer scenarios
exercise the relocated implementations.

Completion requires full `gradlew.bat check` on Windows and `./gradlew check` in a
separate Ubuntu WSL checkout, including GUI interaction and real packaged/extracted
application updates. Local evidence is in `.tools/io-api-*.log` and
`.tools/io-api-qualification.json`; reports remain in each module's `build/reports/tests`.
CI artifacts retain the existing three-day limit. macOS GUI qualification remains deferred.
