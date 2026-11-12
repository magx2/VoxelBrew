# Physical printer devices

The **Printer** workspace becomes available when at least one plate has stored G-code.
Choose a sliced plate and its target printer. **Upload only** transfers the job;
**Print** uploads and requests a start. Live status includes progress and temperatures
when reported. Pause, resume and cancel apply to the selected device. Closing a window
or editing a project does not stop an already running physical print.

Use **Printers > Configure devices…** to add, edit, remove and test connections.
Each saved printer references one printer profile from the Slicer catalog:

- Klipper / Moonraker uses an HTTP(S) URL and optional API key.
- Bambu LAN uses a host, serial number, model and access code. Enter the model
  such as `Bambu Lab A1`. LAN Only and Developer
  Mode may be required by the device firmware. Cloud accounts are not supported.

Printer names must be unique without regard to case. The Slicer and CLI select a
printer by name; an internal UUID preserves its identity across renames and is not
shown in selectors or command output. A renamed printer remains the target of jobs
sliced for it.

In the Slicer workspace, choose any saved printer device first. Its assigned
printer profile supplies the bed and output settings; profile and bed-size editing
are locked while that device is selected. Set the installed nozzle diameter in the
**Process** tab for either a device or a standalone printer profile. Choose **No device —
G-code only** to edit a standalone printer profile and save the sliced job without
a hardware target. **Configure printers…** in the Slicer panel opens device setup
and refreshes the device list when it closes.

The connection and its profile ID are remembered in user-local `devices/{id}.properties`
beside
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
Set the installed nozzle diameter in the **Slicer** workspace's **Process** tab
before slicing. The diameter is stored with the document's slicer printer snapshot
and used to generate the job; it is not part of the physical device connection.
For OrcaSlicer, the selected diameter must match the machine preset's nozzle.
The Bambu device model must match the sliced package before sending it.
Confirm that the physical printer has the nozzle selected for the slice before
starting the job.

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
