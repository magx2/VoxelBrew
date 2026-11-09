# VoxelBrew extension APIs

The main VoxelBrew project is licensed under [AGPL-3.0](../LICENSE).
The developers want users to be able to extend the program with private or
proprietary code whose source is not public. The four shared API modules are
therefore separately licensed under the MIT License:

- `model` (`:api:model`): shared domain values, [MIT license](model/LICENSE).
- `import` (`:api:import`): importer contracts, [MIT license](import/LICENSE).
- `export` (`:api:export`): exporter contracts, [MIT license](export/LICENSE).
- `slicer` (`:api:slicer`): slicing contracts, [MIT license](slicer/LICENSE).

The import and export APIs depend only on the model API. The slicer API depends on the model and export APIs. None of these modules
has a compile or runtime dependency on the AGPL engine, util, or frontends.
The engine depends on the model API; native evaluation and persistence remain
in the AGPL application. Existing model Java package names are retained, but
their classes are supplied by `model-api`, not by the engine JAR.

These licenses apply to the code in their respective module directories.
Both binary and source JARs include `META-INF/LICENSE`; Maven POMs declare MIT.
Published coordinates are `dev.voxelbrew:model-api`, `dev.voxelbrew:import-api`
`dev.voxelbrew:export-api` and `dev.voxelbrew:slicer-api`, with matching `-api` JAR names.

MIT permits proprietary use of these API modules, subject to retaining its
copyright and permission notice. This does not relicense the AGPL application
or grant an exception for extensions that directly depend on engine internals,
including the engine's native feature-provider contract.

Use the published APIs as `compileOnly` dependencies and do not bundle host API
classes into plugin JARs. See [authoring a provider](../docs/file-io.md#authoring-an-external-provider)
for interfaces, service registration and the independent consumer example.
