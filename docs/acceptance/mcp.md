# MCP integration — partial implementation evidence

The MCP connection/settings and basic Designer increment is implemented.
The complete all-workspace plan is **not accepted**. See [remaining work](../mcp.md).

## Automated checks

Windows commands:

```powershell
.\gradlew.bat :gui:compileJava --console=plain
.\gradlew.bat spotlessApply :gui:test :util:test --console=plain
.\gradlew.bat spotlessApply :gui:test --tests '*Mcp*' --console=plain
```

All passed. The latter command was rerun after Dagger wiring changes. Logs:
`.tools/mcp/windows-unit.log` and `.tools/mcp/windows-final.log`. Tests cover
real HTTP initialization and tool execution, token/origin rejection, persistent
configuration and credential rotation, revision conflicts, busy editing and undo
through the live controller. Reports: `gui/build/reports/tests/test/index.html`.

An isolated Ubuntu source snapshot at `/tmp/voxelbrew-mcp-integrated.VGfpAc` passed:

```sh
./gradlew :gui:test --tests '*Mcp*' :util:test --console=plain
```

Log: `/tmp/voxelbrew-mcp-integrated-check.log`. This snapshot includes token
persistence, HTTP tool execution and the final Dagger ownership change.
It does not share Windows build output.

## Pending checks

GUI rendering/gesture checks, real OCCT reference
workflow, real slicer workflow, package/runtime verification and printer protocol
qualification remain pending. No E2E suite, full root check or physical hardware
test was run. This change does not complete a CAD milestone and is not committed
as a completed milestone.
