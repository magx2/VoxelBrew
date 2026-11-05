# Persistent custom features

M6 publishes `dev.voxelbrew.engine.feature.FeatureProvider` API 1 from engine.
Trusted JARs use `META-INF/services/dev.voxelbrew.engine.feature.FeatureProvider`
and the same `~/.voxelbrew/plugins` directory as exporters and slicers. Override
it with `--plugin-dir` in either frontend. Load errors, duplicate provider/type
pairs and incompatible APIs are diagnosed independently; the first compatible
registration wins. A dedicated class loader discovers providers for each rebuild.
Assertions and nested service-loading errors from descriptors, codecs or geometry
evaluation are reported as plugin/feature diagnostics. A failed edit keeps the
previous revision and undo history; failed evaluation blocks dependent features
and prevents stale export. Fatal VM errors are not converted to model failures.

API 1 supports numeric parameters and one new solid body named `body`, with
identity `<feature-id>.body`. Built-in cuts and splits can consume that named
output. The descriptor supplies parameter labels, units, defaults and inclusive
bounds for both frontends. The provider validates relationships between fields,
encodes definitions, decodes supported payload versions, and evaluates geometry.
Codecs must be deterministic and perform no native work. The provider closes
intermediate solids; the returned independent `Solid` transfers to engine ownership.
Native pointers, generated Protobuf classes and frontend types are absent from
the contract. This is trusted in-process code, without a plugin sandbox.

Custom definitions were introduced in `.jcad` schema 3; current schema 6 retains
the provider ID, type, payload version and opaque bytes, and older documents remain
readable. Unknown plugins or payload versions remain
savable, with byte-for-byte payload preservation and stable IDs. Their rebuilds
fail with feature-specific diagnostics, dependents are blocked, and stale geometry
cannot be exported or sliced. Install the compatible provider and rebuild to
repair the existing feature. On Windows, close the workbench before replacing or
removing a JAR already loaded by an exporter/slicer. Installing a missing JAR and
using **Reload plugin and rebuild** repairs the open custom feature.
Rebuilding invalidates any previous sliced job, including when plugin updates
leave the stored parameter bytes unchanged; slice again to obtain a current preview.

## Independent spacer example

`fixtures/exporter-plugin` is an independent consumer build against the published
engine artifact, not an application subproject. It includes `consumer.SpacerProvider`:
an analytic cylinder with a through-hole, parameterized by `outerDiameter`,
`innerDiameter` and `height` in millimeters. Its v1 payload is three big-endian
doubles in that order. The fixture also contains deliberately invalid providers
for isolation tests; their listing diagnostics are expected.

```powershell
.\gradlew.bat :engine:buildExporterFixture :cli:installDist :gui:installDist
$plugins = 'fixtures/exporter-plugin/build/libs'
$cli = '.\cli\build\install\cli\bin\cli.bat'
& $cli feature-types --plugin-dir $plugins
& $cli new spacer.jcad
& $cli custom spacer.jcad --id spacer --provider example.spacer --type spacer --parameter height=5 --plugin-dir $plugins
& $cli edit spacer.jcad --feature spacer --field height --value 8 --plugin-dir $plugins
& $cli inspect spacer.jcad --point 0,0,4 --plugin-dir $plugins
& $cli export spacer.jcad --format 3mf --output spacer.3mf --plugin-dir $plugins
.\gui\build\install\gui\bin\gui.bat --open spacer.jcad --plugin-dir $plugins
```

On Ubuntu use `./gradlew` and `cli/build/install/cli/bin/cli` (or the packaged
`VoxelBrewCLI` launcher). The commands/options are identical. A default 20 mm
outer diameter, 4 mm hole and 8 mm height yields bounds `(-10,-10,0)`–`(10,10,8)`
and exact volume `2412.743157956961 mm³`, with an empty hole center.

In the GUI choose **Features…** above the model tree, select **Spacer**, enter
dimensions, and click **Add**. Cancel and invalid values create no operation.
Edit fields in Properties, undo/redo, save/reopen, and export using the normal
workbench actions. Adding requires the timeline end. Choose the custom feature
in history to access **Reload plugin and rebuild** after installing a missing JAR.
For printing, place the centered spacer at `(30,30,0)` using the Print tab or
`place --instance placed --body spacer.body --x 30 --y 30`, supplying `--plugin-dir`
to subsequent CLI print commands as well.

API 1 does not add arbitrary custom input graphs or generated-face support
references. Those require later API/compatibility work. General sketch constraints
are available independently through the built-in M7 sketch engine.
