# File import and export modules

Sliced jobs belong to `CadDocument.gcode()`, keyed by plate ID, with one UTF-8
source line per list element. The Protobuf `Gcode.lines` field and JSON document
export preserve comments, empty lines and all commands. `GcodeJob.lines()` also
retains the input of the standalone G-code importer; `GcodeSource.readLines`
parses an embedded job for preview without needing its original file.
The built-in `gcode` exporter writes the active plate's stored job atomically and
rejects a missing job. It does not run a slicer or regenerate toolpaths.

The `import` and `export` Gradle projects separate file I/O from the CAD engine.
The Java packages are `dev.voxelbrew.importer` and `dev.voxelbrew.exporter`;
`import` is a Java keyword, so it cannot be a package segment.

| Project       | Owns                                                                                   | Production dependencies                                                                  |
| ------------- | -------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------- |
| util          | Configuration, translations, logging, updates, generic workers                         | No other project                                                                         |
| engine        | Native geometry, evaluation, tessellation, document codec, feature execution           | api:model, util                                                                          |
| api:model     | Shared document, sketch, parameter, mesh and manufacturing values                      | No application modules                                                                   |
| api:import    | ImporterProvider SPI, typed dialogs/results, DocumentSource/GcodeSource ports          | api:model                                                                                |
| api:export    | ExporterProvider SPI, Exporter/ExportWindow contracts                                  | api:model                                                                                |
| api:slicer    | Slicer backend, catalog, execution and runtime-provider contracts                      | api:model, api:export                                                                    |
| impl:importer | Bounded .vxb/JSON/G-code reading, importer discovery and invocation                    | engine, api:import, util                                                                 |
| impl:exporter | Atomic .vxb writing, STL/3MF writers, exporter discovery, slicer execution/publication | engine, api:export, api:slicer, impl:importer, util                                      |
| cli / gui     | Commands and desktop interactions                                                      | engine, api:import, api:export, api:slicer, util; impl:importer/impl:exporter at runtime |

The API projects live in `api/model`, `api/import`, `api/export` and `api/slicer`. Their binary and source JARs
retain the `-api` suffix and bundle `META-INF/LICENSE` under MIT. See the
[licensing rationale](../api/READEME.md).

The APIs contain only public contracts and their value types. Concrete format classes,
registries, services and ServiceLoader registrations stay in `impl/importer` / `impl/exporter`.
Engine depends on api/model, but not on the file-I/O APIs or implementations. Export inputs
`ModelView` and `ManufacturingView` are internal helpers in `dev.voxelbrew.exporter`; the concrete
`ModelViews` and `ExportSelections` adapters live in the export implementation. `ExportSelection` is an API-owned value in `dev.voxelbrew.exporter`. Shared CAD
placements (`BodyInstance` and `Transform`) stay in `dev.voxelbrew.engine.model`.
`ManufacturingModel` is a detached CAD/manufacturing snapshot, independent of the export API.
The shared classes retain their `dev.voxelbrew.engine.*` package names for source
compatibility, but ship only in `model-api`. Native manufacturing factories moved
to engine's `ManufacturingModels.evaluate/from`; `ManufacturingModel` contains only
the detached value and mesh helpers. Document IDs use the API-owned
`dev.voxelbrew.engine.model.UuidGenerator`.

`verifyApiDependencies` rejects application project dependencies on API compile/runtime
classpaths. The independent fixture compiles import/export providers against only
model-api/import-api/export-api/slicer-api; native feature providers compile separately against engine.
All services are headless.
STL mesh-to-editable-CAD and STEP import remain outside the implemented workflow.

## Document exporters

The native document extension and format ID are `vxb`. The binary envelope and schema
are unchanged, so existing document contents remain readable through the same codec.
Save As defaults to `.vxb`, while saving an already-open project keeps its current path.

`DocumentSink.save` is replaced by `Exporter<R>.export(CadDocument, ExportWindow)`.
The exporter defines its result type: file exporters return `Path`, while a cloud
exporter can return its own upload receipt or remote resource identifier. Cancellation
completes the returned future with `CancellationException`.

`ExportWindow.show(ExportDialog<R>)` hosts typed, exporter-defined interactions. The
window contract contains no destination, file, credential or cloud-specific fields.
An exporter defines a dialog extending `ExportDialog<R>` with whatever data it
needs, and each frontend registers a handler for that request type. `ExportDialogs`
is the frontend-neutral handler registry; unknown request types fail explicitly.
Registration is local to the host, so one window cannot replace another's handlers.

`ExportDialog<R>.getSettings()` returns confirmed settings after `show` succeeds.
It throws before confirmation or after cancellation/failure. Hosts use
`collectSettings` to clear previous results and record only the accepted value;
`ExportDialogs` handles this lifecycle automatically.

`FileExportDialog` is the built-in file request, with a typed destination and overwrite
response. Desktop rendering retains formula-aware file paths and overwrite confirmation.
CLI uses `FileExportDialog.destination(path, overwrite)` to provide parsed arguments
without a display. File operations still run on the window-owned file worker.

For example, a cloud provider can define `CloudDialog extends ExportDialog<CloudSettings>`
carrying available accounts and projects. Its `Exporter<CloudReceipt>` requests those
settings through `parent.show(new CloudDialog(...))`. Desktop code registers a renderer
with `DesktopExportWindow.register(CloudDialog.class, (owner, request) -> ...)`; it can
construct any JavaFX dialog and use the real owner window. CLI code registers a handler
with `ExportDialogs.register(CloudDialog.class, request -> ...)` to obtain settings from
arguments or console prompts. The provider can request multiple interactions and return
its own receipt; no local file is required. Custom dialogs need a handler for each
supported frontend. This is a hosting contract, not an automatic cross-platform UI renderer.

`VoxelBrewExporter` saves the editable binary document with existing schema, atomic-write,
file-version and newer-file protections. `JsonExporter` writes UTF-8 ProtoJSON for the
complete document schema, including feature definitions, parameters and print setup.
JSON export does not change the active project path, save counter or dirty
state. JSON import restores the complete editable schema through the same validation as VXB. Opaque binary payloads use ProtoJSON base64.
File → Export lists all registered exporters,
then opens the selected exporter's options. Save and Save As always use VXB; they do not
reuse the last export format. VXB and JSON remain available without exportable geometry.
CLI exposes `export --format vxb|json|step --output PATH`.
GUI Save As uses the same VXB exporter. `DocumentOutput` is an API-module
writer port for direct saves and injected tests, not a public plugin contract.
All geometry formats also implement `Exporter`; the separate `ModelExporter` SPI and service registration have been removed.

### Runtime discovery of document exporters

Implement `dev.voxelbrew.exporter.api.ExporterProvider` with a public no-argument
constructor. Return a `ExporterDescriptor` with a unique ID and display name. Its `create(Executor)` method returns your
`Exporter<?>`; use that supplied worker for asynchronous operations and do not shut it
down. The returned future must cover the complete operation.

List the provider's fully qualified class name in the JAR resource:

```text
META-INF/services/dev.voxelbrew.exporter.api.ExporterProvider
```

Copy the JAR into `~/.voxelbrew/plugins` (or the configured plugin directory), then open
File → Export. It rescans the directory and lists provider metadata from the registry,
including newly added JARs. There is no frontend format list to edit. Built-in exporters are supplied by the export runtime; external providers use ServiceLoader, so plugin builds do not need
Dagger or application-module dependencies. Duplicate IDs preserve the first provider,
with built-ins registered first; broken providers produce
diagnostics without hiding healthy providers. File → Save remains the native VXB path.

The same providers are listed by `cli exporters` and invoked by `cli export --format ID`.
`--output` is required only for providers that request a file destination (and mesh exports).
Providers using custom dialog requests still need frontend handlers as described above;
discovery alone does not render an unknown dialog type.

Loaders stay open while a window may be using their classes. Newly added JARs are picked
up immediately on the next Export action; close relevant windows before replacing or
removing loaded JARs, particularly on Windows where JAR files may be locked.

The independent `consumer.DocumentProbeProvider` fixture compiles against the published
API artifacts only. Unit tests install its JAR after opening a registry, rescan, execute
it, and verify that the Dagger-created CLI graph lists and invokes it without loading OCCT.

## Public file operations

### Geometry formats

File Export and `cli export --format ID --output PATH` offer these geometry formats:

| ID     | Output        | Geometry                                                       |
| ------ | ------------- | -------------------------------------------------------------- |
| `stl`  | Binary STL    | Triangles in millimeters                                       |
| `3mf`  | Portable 3MF  | Triangles with units and instance placements                   |
| `obj`  | Wavefront OBJ | Triangles in millimeters, one object per instance              |
| `step` | STEP AP242    | Exact solid boundaries, including analytic curves and surfaces |

STL, 3MF and OBJ use the mesh export options and geometry selection. OBJ applies each
instance transform once and uses global, one-based face indices across objects; its
units are documented in a comment because OBJ has no standard units field. Materials
and textures are not exported.

STEP exports all bodies of the complete, successfully evaluated design in design
coordinates. Print-bed placements, supports and feature history are not included.
It does not tessellate solids or change the active project path or save counter.
The provider receives the configured feature-plugin directory through the optional
`ExporterProvider.create(Executor, Path)` overload; API-only providers can keep
implementing `create(Executor)`. Geometry encoding belongs to the engine; the exporter
owns the destination dialog and atomic publication. Failed or rolled-back evaluations
are rejected before replacing a destination.

`AdditionalFormatsTest` parses OBJ vertices/faces independently and checks placement,
closed topology and volume. A separate OCCT STEP reader executable checks exact STEP
solid counts, bounds, volume and the reference through-hole. This reader uses the same
OCCT kernel, but runs separately from the writer/JNI process. Existing STL/3MF reader
tests continue to cover those formats.

```java
import dev.voxelbrew.importer.DocumentReader;
import dev.voxelbrew.importer.GcodeReader;
import dev.voxelbrew.exporter.DocumentWriter;
import dev.voxelbrew.exporter.ExporterRegistry;


var document = DocumentReader.load(source);
DocumentWriter.save(document, destination, false);
var preview = GcodeReader.read(gcodeFile);
```

Replace `DocumentStore.load/save` with `DocumentReader.load/DocumentWriter.save`.
Schema version and stable revision fingerprints are exposed by
`dev.voxelbrew.engine.persistence.DocumentCodec`; it also provides in-memory
`encode/decode` and never accesses files. `.vxb` schema 7 retains the optional
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

`BinaryStlExporter`, `ThreeMfExporter`, `ExporterRegistry` and `AtomicExport` now
live in `dev.voxelbrew.exporter`. Slicer backends, the registry and `SliceService`
live in `dev.voxelbrew.slicer` (impl/slicer). Prusa project/configuration writers
remain in `dev.voxelbrew.exporter.printing`.
`GcodeJob` stays an immutable engine preview model; use `GcodeReader.read` to load it.

Use `ModelViews.from(evaluation)` for design geometry, keeping the owning evaluation open
throughout export. `ModelViews.from(manufacturingModel)` adapts an already detached snapshot
and exposes `ManufacturingView.manufacturingModel()` for project exporters that need the
complete print setup. Selecting bodies, instances, or support solids returns a plain
`ModelView`; it cannot be used as a complete slicer project. `ExportSelection.Contents`
controls model/support/all geometry selection. Format-specific transform serialization
(including 3MF matrix ordering) belongs to the format writer, not the engine placement type.

## Authoring an external provider

See the [import API authoring guide](../api/import/README.md) for a complete importer,
packaging, typed dialogs and API-version-2 migration.

Compile against `dev.voxelbrew:import-api:<version>` or `dev.voxelbrew:export-api:<version>`
with `compileOnly` (Maven `provided` scope). Shared MIT model types are available transitively; engine and util are absent from these API dependencies.
Do not bundle host API classes into a plugin JAR. No dependency on import/export, JavaFX,
picocli, Protobuf classes or Dagger is needed. This source migration intentionally has no
old-package shims: recompile existing providers and rename their service resources.

| Extension | Public interface                              | JAR service resource                                            |
| --------- | --------------------------------------------- | --------------------------------------------------------------- |
| Import    | `dev.voxelbrew.importer.api.ImporterProvider` | `META-INF/services/dev.voxelbrew.importer.api.ImporterProvider` |
| Export    | `dev.voxelbrew.exporter.api.ExporterProvider` | `META-INF/services/dev.voxelbrew.exporter.api.ExporterProvider` |
| Slicing   | `dev.voxelbrew.slicer.api.SlicerBackend`      | `META-INF/services/dev.voxelbrew.slicer.api.SlicerBackend`      |

Each service file lists public, no-argument provider classes, one per line. Import providers
implement `ImporterProvider` (API version 2), advertise a stable ID, name and result kind,
and create an `Importer<? extends ImportResult>` with the supplied host executor.
`importFrom(ImportWindow)` requests typed settings synchronously on the interaction thread,
then schedules reading on that executor. The future covers the complete import. Cancellation
completes with `CancellationException`; a failed import must preserve the source.
Document results contain actual editable definitions; toolpaths remain `ImportResult.Gcode`.

`ImportWindow`, `ImportDialog<R>` and `ImportDialogs` mirror their export counterparts,
including confirmed-settings lifecycle, cancellation and explicit unsupported-dialog errors.
File readers request `FileImportDialog`, which owns extensions and optional typed settings.
The CLI resolves that dialog with `FileImportDialog.source(path, options)`; the desktop
uses a window-local `DesktopImportWindow` renderer. Custom dialogs require a host handler.
The removed stream-based `FileImporter` SPI and its service resource are not supported:
external plugins must recompile and register `ImporterProvider` instead. Reader ports
`DocumentSource` and `GcodeSource` remain in use for direct open and toolpath operations.

`ImporterRegistry`/`ImportService` and `ExporterRegistry` are implementation services.
Trusted JARs are discovered in `~/.voxelbrew/plugins` or `--plugin-dir`. Built-in importers
and exporters are composed by their runtime implementations; standalone registries use the same built-ins.
External providers use ServiceLoader. Duplicate IDs preserve the first provider, with
built-ins first; broken or incompatible import providers generate diagnostics without
hiding healthy ones. Import opens a fresh registry for each menu action, retaining its
class loader until the asynchronous operation completes.

Built-in VXB, JSON and G-code readers bound input to 256 MiB; VXB and decoded JSON
also enforce the codec's 16 MiB payload limit. JSON is strict ProtoJSON: unknown fields,
unsupported schema/writer versions, invalid units and invalid definitions are rejected.
Plugins own their source access and must bound their reads themselves. No importer may
write its source. Exporters own destination requests and safe publication.

`fixtures/exporter-plugin` is an independent Gradle build with published API dependencies
only. It includes `consumer.ProbeImporter` (CSV `width,height,thickness` in a `.vbplate` file,
with a `scale` option) and `consumer.DocumentProbeProvider` (a document report using the unified exporter API).
`./gradlew :engine:buildExporterFixture` publishes engine/util and all four APIs to
`engine/build/repository`, then compiles that consumer. For Maven-local development run
`./gradlew :util:publishToMavenLocal :engine:publishToMavenLocal :api:model:publishToMavenLocal :api:import:publishToMavenLocal :api:export:publishToMavenLocal :api:slicer:publishToMavenLocal`.
The API artifacts also publish source JARs. Copy the consumer JAR into a trusted plugin directory.

```text
cli importers --plugin-dir "path with spaces/plugins"
cli import plate.vbplate --format plate --option scale=2 --plugin-dir "path with spaces/plugins" --output plate.vxb
cli import part.gcode --format gcode
cli exporters --plugin-dir "path with spaces/plugins"
cli export plate.vxb --format document-probe --plugin-dir "path with spaces/plugins" --output report.txt
```

Document imports require a separate `--output`; existing destinations require `--overwrite`.
G-code import prints toolpath counts and rejects `--output`, because it is not editable CAD.
GUI **File > Import** (`Ctrl+I`) lists document providers, then opens the selected
importer's source dialog. VXB and JSON are built in; file options come from that dialog. Reads run on the file worker; success opens an unsaved design, and failure
keeps the current document. Save/Discard/Cancel protects unsaved work before importing. Toolpath
importers are available through the host API and CLI; the GUI import dialog is for documents.

## Slicer input export

`SlicerBackend` belongs to the MIT `:api:slicer` project under
`dev.voxelbrew.slicer.api`. Extensions compile against `dev.voxelbrew:slicer-api`
and register `META-INF/services/dev.voxelbrew.slicer.api.SlicerBackend`.
Concrete `SlicerRegistry`, `SliceService` and backend adapters live in `impl/slicer`.
See the [slicer API guide](../api/slicer/README.md) for migration and packaging.

Slicing resolves `SlicerBackend.inputFormat()` through the same exporter registry.
`SliceService` supplies headless `FileExportDialog` and built-in `MeshExportDialog`
handlers, waits for export completion, then passes the completed file as
`SlicerBackend.Request.input()`. The backend no longer serializes geometry.
Prusa selects the `prusa-project` exporter and includes solid supports; its 3MF
retains placements and support modifiers. Export failure or cancellation prevents
backend execution, and temporary files are deleted with the job workspace.

Exporter descriptors have no API version field or version compatibility check. The slicer SPI remains version 2. External exporters implement
`ExporterProvider`, not the removed `ModelExporter`; model/manufacturing views and
mesh option metadata remain implementation helpers outside `export-api`.
See the [export developer guide](../api/export/README.md) for migration and examples.

## Distribution and verification

Both API libraries and the implementations are published alongside engine/util and automatically included
in development distributions and bundled images. Installation metadata identifies
`importJar`, `exportJar`, `model-apiJar`, `import-apiJar` and `export-apiJar`; the updater checks their presence and build versions.
Older format-1 installation metadata remains readable for recovery/downgrades.

Focused checks:

```text
./gradlew :api:import:test :api:export:test :impl:importer:test :impl:importer:integrationTest :impl:exporter:test :impl:exporter:integrationTest
```

The compatibility fixture in `impl/exporter/src/test/test/resources/fixtures/` was
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

## Frontend runtime boundary

CLI and GUI compile against `:api:import`, `:api:export` and `:api:slicer`. Their `:impl:importer` and
`:impl:exporter` dependencies are `runtimeOnly`; all five JARs ship in each application
distribution's `lib` directory. The application JARs remain thin JARs.
`verifyFrontendIoDependencies` enforces both compile and runtime classpaths.

`ImportRuntime`, `ExportRuntime` and `SlicerRuntime` are host service-provider contracts, discovered
by ServiceLoader at each application's composition root. The implementations
register `DefaultImportRuntime`, `DefaultExportRuntime` and `DefaultSlicerRuntime`; each frontend's local
Dagger `FileIoModule` injects reader/writer ports, catalog factories, slicing,
the default executable and document-exporter factories. Business objects receive
those individual dependencies, and windows still own and close their catalogs.
Import/export APIs depend only on `api:model`; `api:slicer` depends on `api:model` and `api:export`. None depends on Dagger or engine.

`ImporterCatalog`, `ExporterCatalog` and `SlicerCatalog` expose discovery results
without exposing concrete registries. `Slicing` owns the result, cancellation
publication callbacks and outdated-job exception. Mesh dialogs/options, selection
values and `DocumentOutput` ship in `export-api`, retaining their Java package names.
`SlicerBackend`, `SlicerCatalog`, `Slicing` and `SlicerRuntime` ship in `slicer-api`
under `dev.voxelbrew.slicer.api`. Native selection evaluation is performed by
`ExportSelections.view(selection, document, evaluation, options)` in `export`.
Standalone callers must replace `SliceService.Result/Commit/PublicationGuard/
OutdatedException/DIRECT` references with the corresponding `Slicing` members.
`ImportInvocation` validates provider results without reading files itself.
External format providers retain their existing SPI and service registrations.

## Implementation project layout

Concrete file I/O lives in `impl/importer` (`:impl:importer`) and `impl/exporter`
(`:impl:exporter`). Their JARs and Maven artifact IDs are `importer-impl` and
`exporter-impl`, under `dev.voxelbrew`; the public `*-api` artifacts are unchanged.
Java packages remain `dev.voxelbrew.importer` and `dev.voxelbrew.exporter`.
Frontends compile against the APIs and include the implementations at runtime.

Packaged images retain the `importJar` and `exportJar` metadata keys, now pointing
to `importer-impl-<version>.jar` and `exporter-impl-<version>.jar`. Internal
`voxelbrew-import-version.properties` and `voxelbrew-export-version.properties`
identities remain stable so existing update validation continues to check versions.

## Independent slicer configuration

`impl/slicer` (`:impl:slicer`, artifact `slicer-impl`) owns backend execution,
ServiceLoader discovery and atomic job publication. CLI and GUI include it at
runtime. Prusa project and INI serialization stay in `impl/exporter`.

Configure providers separately in `~/.voxelbrew/config.properties`, using forward
slashes for Windows paths (properties files otherwise require escaped backslashes):

```properties
slicer.prusa-slicer.executable=C:/Tools/PrusaSlicer/prusa-slicer-console.exe
slicer.prusa-slicer.profile=C:/Profiles/prusa.ini
slicer.cura.executable=C:/Tools/Cura/CuraEngine.exe
slicer.cura.definition=C:/Profiles/resolved-machine.def.json
slicer.cura.settings=C:/Profiles/cura.properties
```

Restart the application to reload saved configuration. A blank GUI executable field
or omitted CLI `--slicer` uses the selected provider's executable. An explicit path
overrides it for that invocation. CLI `slice --slicer-option definition=PATH
--slicer-option settings=PATH` overrides only that backend's saved options.
Select the backend ID `prusa-slicer` or `cura` in the document's print setup.
The legacy `VOXELBREW_SLICER`/`voxelbrew.slicer` override remains Prusa-only.

Prusa uses pinned 2.7.2 and an optional base INI; document process settings take
precedence. Cura uses CuraEngine 5.x (not the desktop GUI executable), a matching
machine definition JSON, and optional UTF-8 properties containing resolved Cura
setting names/values. Its command-line engine does not evaluate Cura GUI profile
expressions; supply a fully resolved single-extruder definition and settings.
Inherited definitions must be discoverable through `CURA_ENGINE_SEARCH_PATH`.
Document layer height, walls, infill, temperatures and nozzle override that file.
The initial Cura adapter supports Marlin text G-code without support modifiers,
automatic supports, brim or raft; unsupported requests fail before publication.
Cura real-engine, viewer and physical-print qualification remain pending.

Plugins can advertise `configurationOptions()`, return a fresh immutable backend
from `configure(Map<String,String>)`, and supply `executable()`. The registry passes
only `slicer.<provider-id>.*` properties, with that prefix removed. Unknown options
must fail with a diagnostic, without disabling healthy providers. These default
SPI methods preserve existing version-2 providers; those providers still need an
explicit executable unless they implement `executable()`.

Cura command syntax follows the upstream
[command-line implementation](https://github.com/Ultimaker/CuraEngine/blob/5.0/src/Application.cpp).

Printer catalogs are exposed by `dev.voxelbrew.slicer.api.PrinterCatalog` through
`SlicerRuntime.printers(Path)`. The implementation, bundled presets, images and
custom-resource loading live in `impl/slicer`; frontends use the existing
`SlicerRuntime` ServiceLoader provider. The former engine `PrinterCatalog` and
util `PrinterResources` implementation types have moved out of those modules.
