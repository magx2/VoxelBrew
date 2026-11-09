# VoxelBrew extension APIs

The main VoxelBrew project is licensed under [AGPL-3.0](../LICENSE).
The developers want users to be able to extend the program with private or
proprietary code whose source is not public. To support that intent, the two
file-I/O API modules are separately licensed under the MIT License:

- `import` (`:api:import`): [MIT license](import/LICENSE).
- `export` (`:api:export`): [MIT license](export/LICENSE).

These licenses apply to the code in their respective module directories.
Both binary and source JARs include the module's license at `META-INF/LICENSE`.
Published Maven POMs also declare MIT. The JARs and Maven artifact IDs retain
`import-api` and `export-api` to distinguish them from the implementation modules.

MIT permits proprietary use of these API modules, subject to retaining its
copyright and permission notice. This does not relicense the AGPL application,
engine, or other dependencies. The APIs currently expose engine domain types and
depend on the AGPL engine, so the MIT API licenses alone do not establish a blanket
exception for proprietary plugins linked to the application. The developers'
intent above should not be read as such an exception.

Use the published APIs as `compileOnly` dependencies and do not bundle host API
classes into plugin JARs. See [authoring a provider](../docs/file-io.md#authoring-an-external-provider)
for interfaces, service registration and the independent consumer example.
