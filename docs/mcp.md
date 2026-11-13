# GUI MCP server

The GUI includes an experimental, authenticated Streamable HTTP endpoint for
editing the live document. Open **Settings → MCP Server**, choose a port, and
select **Apply and start**. Port `0` selects an available port. Copy connection
settings into an MCP client supporting HTTP and an Authorization header.

The server listens on `127.0.0.1` only. Each window owns its listener. Starting
automatically applies to the initial application window. A fixed-port conflict
leaves that listener stopped and displays the error.

## Configuration

Preferences use the normal `~/.voxelbrew/config.properties` file and its existing
path overrides:

```properties
mcp.autoStart=false
mcp.port=0
mcp.maxConnections=4
mcp.idleTimeoutMinutes=30
mcp.activityHistorySize=100
```

Port accepts `0` or `1024–65535`. Maximum connections accepts `1–16`; currently
this bounds simultaneous HTTP request dispatches rather than MCP sessions.
Idle timeout accepts `1–120` minutes and controls the SDK session sweep interval.
History accepts `10–1000` operation summaries; it stays in memory and contains
tool names, completion acceptance and timestamps rather than arguments or secrets.
Reopen the dialog to refresh its activity snapshot.

**Apply and start** validates and saves preferences, then restarts the current
listener. **Stop** closes the listener. **Regenerate token** stops it and replaces
the credential; start it again and copy updated connection settings to clients.
The token is stored separately at `~/.voxelbrew/mcp-token` with owner-only file
permissions. If persistence fails, a window-only token is used and a diagnostic
is logged. The `voxelbrew.home` override relocates this file.

## Live document tools

`document_inspect` returns the editable document, document ID, generation,
revision, dirty state, rebuild state and diagnostics. Every mutation requires
the returned `documentId`, `generation` and `revision`. Inspect again after
each mutation or conflict. Background rebuilding can still be running when a
tool returns; inspect `rebuilding` and `valid` before relying on geometry.

Available tools are `document_new`, `document_open`, `document_save`,
`sketch_rectangle`, `sketch_circle`, `feature_add`, `feature_replace`,
`feature_edit`, `feature_expression`, `feature_remove`, `configuration_activate`,
`history_cursor`, `history_undo` and `history_redo`. Tool discovery supplies their
argument schemas. Rectangle and circle profile IDs are `<sketch-id>.profile`;
coordinates are millimetres. Feature definitions currently cover extrude, cut,
split, edge finish and shell using the engine's record fields.

Edits use the same controller, undo history and automatic viewport rebuild as
GUI edits. Unfinished sketch editors, tool previews and file operations reject
mutations as busy. New/open requires an explicit `discardUnsaved` argument;
save requires explicit `overwrite`.

## Remaining implementation and qualification

This is the connection/settings and basic Designer increment of the MCP plan,
not completion of the all-workspace feature. Remaining work includes:

- Shared printer orchestration and Slicer/Printer tools, including GUI approval
  before physical print start.
- Advanced sketch/constraint, parameter/configuration management, construction,
  plugin, import/export, topology and viewport tools.
- Subscribable state resources, operation IDs/progress/cancellation and strict
  client-session admission/idle expiry.
- Nonblocking listener lifecycle UI, richer definition schemas and translated
  validation diagnostics.
- Rendered GUI acceptance, real-kernel reference-part and real-slicer acceptance.

The unit tests exercise real HTTP initialization, authentication/origin rejection,
configuration persistence, credentials and shared controller edits/conflicts/undo.
These checks do not qualify rendered GUI behavior or physical printer hardware.
