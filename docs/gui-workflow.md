# Desktop workflow (M3)

VoxelBrew uses Java 21, JavaFX 21.0.13 controls/CSS and a depth-tested 3D
SubScene. The engine and CLI remain headless. Build prerequisites and native
bootstrap commands are in the root README. Run `gradlew.bat :gui:run` on
Windows or `./gradlew :gui:run` on Ubuntu. Gradle arguments can include
`--args="--open fixtures/reference-8mm.jcad --plugin-dir path/to/plugins"`.

`:gui:installDist` creates `gui/build/install/gui`. Its `bin/gui.bat` or
`bin/gui` launcher requires Java 21 and locates the adjacent native libraries.
Only use a distribution built for the destination OS and architecture.

## Workbench layout and menus

The workbench uses a compact desktop layout with browser-inspired rounded chrome:
a document title and menus at the top, grouped modeling tools and workspace tabs,
a model tree on the left, the viewport in the center, Properties on the right,
and history and rebuild status below. The title shows the file name, with its full
path available on hover. Drag the panel dividers to give the model
or inspector more space. The toolbar wraps on smaller windows and is hidden
in the Print workspace. File actions do not take up modeling toolbar space.

- **File**: New, Open, Save, Save As and Export. Save shortcuts are Ctrl+S and
  Ctrl+Shift+S; the document name and unsaved-change dot stay visible at the top.
- **Edit**: Undo, Redo and Parameters. Undo/Redo also remain on the modeling toolbar.
- **View**: Fit, standard camera directions and **Appearance → System / Light / Dark**.
- **Settings**: Language, Updates and **Keyboard shortcuts… (F1)**. Restart after changing the language.

Hover over a button or menu action for a description of its purpose, prerequisites
and relevant shortcuts. Descriptions and menu captions are available in English
and Polish; screen readers can also access action help. Plugin-owned option names
retain their provider wording. Escape closes an open menu before cancelling a tool.
Document menu shortcuts are disabled while a modal editor is open.

The model tree is kept above configuration controls. **New sketch…** is the primary
toolbar action; Rectangle and Circle provide the quick two-point tools. Extrude
and Through cut require a suitable selected profile. The timeline selects operations;
its slider changes rollback independently of Undo/Redo.

## Reference-part demo

For the constraint workspace, choose **New sketch…** or select an existing sketch
and choose **Edit sketch / constraints…** in Properties. The workspace supports
geometric constraints, driving dimensions, point dragging and local undo/redo.
See [the constrained sketch workflow](sketch-constraints.md) for selection rules
and an equivalent headless workflow. The quick rectangle/circle tools below
remain available.
The **More sketch types…** selector provides the M8 creation variants, with
numeric point input and tool-specific settings. See [sketch types](sketch-types.md).

1. Choose **Rectangle**. Click (0,0) and (60,40) in the top viewport, or set
   X=0, Y=0, Width=60, Height=40, Plane offset Z=0. Choose **Finish sketch**.
2. With that profile selected, choose **Extrude**. Enter 5 and press Enter.
3. Choose **Circle**. Click the center and a radius point, or set center
   (10,10), diameter 4, offset 0. Finish the sketch. Choose **Through cut**,
   select the plate body and confirm.
4. Choose **File → Save As** to save a `.jcad` file. Open it again. Select the extrusion in the tree or
   timeline, change distance from 5 to 8 and press Enter or Apply.
5. Move the timeline slider to 3: the cut is rolled back and volume is 19200
   mm³. Return to 4. Undo restores 5 mm thickness; Redo restores 8 mm.
6. Save and choose **File → Export**. Select 3MF, choose a destination and export.
   A default export uses chordal tolerance 0.02 mm and angular tolerance 5°.
7. In the installed CLI, run `cli inspect design.jcad --point 10,10,4`.
   Expected bounds are (0,0,0)–(60,40,8), volume 19099.469035085127 mm³,
   and `contains=false`. Exact tolerances remain 0.001 mm and 0.01 mm³.

Every finished sketch or extrusion creates one operation. Signed extrusion
distances work on XY and parallel offset planes. Drag the gold handle to preview
an extrusion; releasing commits once. Escape discards the preview and creates
no feature or undo entry. Invalid numeric input remains editable and identifies
the affected field. Generated faces are selectable only within the current
evaluated revision; sketches continue to use stable XY-parallel plane references.

## Navigation, editing and files

| Input                           | Action                                                  |
| ------------------------------- | ------------------------------------------------------- |
| Left click                      | Select a sketch profile, body or rendered face          |
| Left drag on empty grid space   | Orbit the model                                         |
| Middle drag                     | Orbit                                                   |
| Shift + middle drag             | Pan                                                     |
| Mouse wheel                     | Zoom                                                    |
| View selector / Fit             | Isometric and all six standard views / frame the model  |
| View cube face / edge / corner  | Snap to one of 26 viewing directions                    |
| Left drag on the view cube      | Orbit the model                                         |
| View cube Home                  | Restore the fitted isometric overview                   |
| Ctrl+N / Ctrl+O                 | New / Open                                              |
| Ctrl+S / Ctrl+Shift+S           | Save / Save As                                          |
| Ctrl+Z / Ctrl+Y or Ctrl+Shift+Z | Undo / Redo                                             |
| Ctrl+E                          | Export the current evaluated model                      |
| Ctrl+Shift+P                    | Edit parameters (document or sketch draft)              |
| Ctrl+1 / Ctrl+2                 | Switch to Design / Print                                |
| K / R / C                       | New constrained sketch / quick rectangle / quick circle |
| E / H / X                       | Extrude / through cut / split body                      |
| F / 0                           | Fit model / isometric view                              |
| 1 / 2 / 3 / 4 / 5 / 6           | Top / front / right / back / left / bottom view         |
| F1                              | Open the translated shortcut reference                  |
| Enter in a dimension            | Apply or commit the entered value                       |
| Escape                          | Cancel the active drawing or extrusion tool             |

In the 2D sketch editor, **V/P/L/R/C/A/S/G** select the selection, point,
line, rectangle, circle, arc, spline and polygon tools. **F** fits the sketch,
**Delete** removes the selected constraint, and **Ctrl+Enter** finishes the sketch
as one document operation. Undo/Redo acts on the sketch draft; Cancel discards it.
The F1 reference and button tooltips show the same bindings in English or Polish.

Letter and view-number shortcuts are inactive in text fields and selection lists;
modified shortcuts use exact modifiers, so Ctrl+C/V and AltGr text entry keep their
normal behavior. Ctrl+Z/Y (or Ctrl+Shift+Z) operates on the focused text field while
typing. Disabled actions cannot be invoked by keyboard, and modal dialogs suspend
underlying workspace shortcuts. On macOS, Command replaces Ctrl (GUI qualification
is still deferred).

The tree, viewport, inspector and timeline share selection. The timeline cursor
is independent of undo/redo. Return to the end before adding features. Broken
inputs remain in the file and show diagnostics; select a valid earlier sketch
or body in the inspector and use **Repair inputs**. A failed rebuild clears
actionable solid geometry. Export is disabled during rebuild, failed evaluation,
rollback, IO or a transient extrusion preview.

One document lives in each window. A dot in its title means unsaved changes.
New, Open and Close offer Save/Discard/Cancel. Workbench dialogs are modal
overlays inside the document window, keeping keyboard focus stable under WSLg.
File dialogs accept an editable path and offer native browsing. Saving and exporting use the export module's atomic
publication. Existing export files require the explicit replacement checkbox.
Camera and selection are transient and are not saved into `.jcad`.

The view cube in the upper-right corner follows the viewport rotation and shows
the X (red), Y (green), and Z (blue) axes. Hover over a face, beveled edge, or
corner to highlight its snap target. Its menu also provides hidden faces and
Fit model; focused cube targets activate with Enter or Space. Navigation keeps
the view selector synchronized and never creates a feature or undo entry.
Dragging the cube, dragging empty grid space with the left mouse button, and
middle-dragging all rotate the scene in the direction of the pointer. Left drags
starting on a body or sketch keep selection behavior; drawing tools and extrusion
handles retain their own input. Release ends grid rotation, including when the
pointer has moved over a body. Shift + middle-drag continues to pan.

## Appearance and exporters

**View → Appearance → System/Light/Dark** is a local user preference, stored as `appearance` in
`~/.voxelbrew/config.properties`, outside the document. The previous Java Preferences
value is migrated when the configuration has no appearance value. System appearance
is checked on launch and window focus. See [shared configuration](shared-utilities.md).
Windows uses AppsUseLightTheme; Linux uses GNOME color-scheme/GTK preferences.
WSL falls back to the Windows preference when Linux has no explicit choice.
Unavailable detection falls back to light. Dialogs use the workbench palette.

Built-in and trusted external exporters use the same descriptors and typed
option editors. Put provider JARs into `~/.voxelbrew/plugins` or pass
`--plugin-dir`. These JARs execute trusted code; there is no plugin sandbox.
Duplicate IDs, incompatible API versions and provider-load failures are
diagnosed independently. See `export-workflow.md` for the SPI consumer fixture.

## Threading and graphics diagnostics

Committed edits occur on the JavaFX thread. A serial background worker coalesces
pending immutable document snapshots. Results carry document identity,
generation, revision and preview sequence; obsolete results are discarded.
The worker closes each evaluation after copying detached mesh data. JavaFX
never receives native handles. File reads, saves and exports also run off the
JavaFX thread. Rebuild progress and failures are visible in the status strip.

Startup requires JavaFX SCENE3D and reports a graphics diagnostic if unavailable.
On Windows, verify an interactive desktop and a functioning Direct3D driver.
On Ubuntu, install GTK/Mesa and run under WSLg or Xvfb. WSL automatically enables
the ES2 pipeline and permits Mesa software 3D. For non-WSL Mesa, use
`JAVA_OPTS='-Dprism.order=es2 -Dprism.forceGPU=true'` with the installed launcher.
This enables real Mesa rasterization; JavaFX's 2D software pipeline does not
qualify as a 3D renderer.

`:gui:run --args="--smoke-test"` renders the real reference part, writes
`gui/build/gui-smoke.png`, prints `GUI_SMOKE_PASS`, and exits. It does not exercise
editing. `:gui:e2eTest` runs actual JavaFX Robot drawing, picking, camera,
file and history interactions. These tests use foreground windows and preserve
the original appearance preference. Screenshots and GUI-created reference
artifacts are written under `gui/build/ui-artifacts`; XML/HTML reports live in
the standard Gradle test-results/reports directories. Run Windows and WSLg
interaction suites sequentially because they share the physical desktop.
UI-neutral controller tests live in `:gui:integrationTest`. See
[test suites](testing.md) for source roots and separate reports.

Manufacturing placement, slicing, supports, installers, macOS qualification and
persistent generated-face naming remain later milestones.
