# Physical printer devices

The **Printer** workspace becomes available when at least one plate has stored G-code.
Choose a sliced plate and a physical device. **Upload only** transfers the job;
**Print** uploads and requests a start. Live status includes progress and temperatures
when reported. Pause, resume and cancel apply to the selected device. Closing a window
or editing a project does not stop an already running physical print.

Use **Printers > Configure devices…** to add, edit, remove and test connections.
These are physical devices, separate from the slicer's hardware presets:

- Klipper / Moonraker uses an HTTP(S) URL and optional API key. The optional model
  field can constrain the device to a slicer preset ID.
- Bambu LAN uses a host, serial number, model and access code. Enter the model
  such as `Bambu Lab A1`, and the installed nozzle diameter. LAN Only and Developer
  Mode may be required by the device firmware. Cloud accounts are not supported.

Credentials are remembered in user-local `devices/{id}.properties` beside
`config.properties`, with owner-only permissions. They are not encrypted at rest;
they are excluded from documents and diagnostic messages. Configuration paths honor
`voxelbrew.home` and `voxelbrew.config`.

## OrcaSlicer packages

Configure **OrcaSlicer (Bambu LAN)** under **Slicer > Configure slicers…** with
OrcaSlicer **2.5.0** and complete machine, process and filament JSON presets exported
from OrcaSlicer. Machine presets must include their inherited values and startup
G-code. Select the matching hardware preset in the Slicer workspace. Use one material
and tool 0; AMS mapping and multi-material assignments are not implemented.

The adapter preserves Orca's sliced `.gcode.3mf` package and extracts plate 1 G-code
for preview. It does not wrap generic Marlin output in a ZIP. Explicit support volumes
are rejected; automatic supports, brim and raft are passed to Orca.
Device model and nozzle must match before sending a Bambu package.

Packages survive VXB/JSON save and reopen and are invalidated alongside G-code.
Files with packages use schema 11; existing text-only files retain schema 10.
The total document payload limit is 128 MiB; each package is limited to 48 MiB.
**File > Export > Sliced printer package** writes the original package bytes.

The API is `dev.voxelbrew:printer-api`, under MIT. Frontends compile against
`:api:printer` and discover `PrinterRuntime` through ServiceLoader. Implementation
and transport libraries remain in `:impl:printer`; the engine has no printer dependency.
OrcaSlicer is an external executable, not redistributed with VoxelBrew.

## Verification boundary

Local protocol fixtures and real slicer tests do not qualify physical hardware.
No Bambu or Klipper device was available during implementation. Device/firmware-specific
upload, start, pause, resume, cancel and actual print qualification must be recorded
separately. A submitted command is not displayed as a completed physical print.
An uncertain start request is never retried automatically.
