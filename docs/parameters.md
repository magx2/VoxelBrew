# Named parameters and design configurations

Use **Parameters…** in the workbench's left panel or the sketch editor header.
The manager has **Parameters**, **Configurations**, and **Dimensions** tabs.
All changes stay in a draft until **Apply**. In a sketch they remain part of the
sketch draft until **Finish sketch**; cancelling the sketch discards them too.

## Create and use parameters

1. Add `width`, unit `mm`, expression `60`; add `thickness`, unit `mm`, expression `5`.
2. Give a sketch a driving width dimension and enter `width` in its value field.
   Other dimensions can use formulas such as `width / 2` or `width - 2 mm`.
3. Finish the sketch and extrude with distance `thickness`. Negative expressions
   such as `-thickness` extrude in the opposite direction.
4. Reopen **Parameters…** to change the values. Dependent geometry rebuilds on
   the background worker. The model, selection and properties use that revision.

The workbench inspector also accepts expressions. The manager's **Dimensions**
tab lists every bindable field and its unit; select a row and bind an expression.
Supported targets are constrained-sketch driving dimensions (including angles,
conic rho and text spacing), legacy rectangle/circle dimensions and positions,
parallel sketch-plane offsets, extrusion distances and split-plane offsets.
All sketches sharing a plane use its one offset expression. The origin XY plane
is fixed. Through-cut size and position are driven by their source sketch.

Entering a plain number in a dimension field detaches its expression and makes
that dimension numeric. Clearing a binding in the manager or CLI restores the
stored numeric definition. This can differ from the currently evaluated value.
Renaming a parameter rewrites dependent formulas and bindings while retaining
its stable ID. Removing a parameter that is still referenced is rejected.

## Configurations: different parameter versions

**Default** contains the base expressions. Add a configuration, such as `Large`,
to inherit them. Override `width` with `80` and `thickness` with `8` using the
configuration table or the override field below it. Blank override cells or
**Inherit Default** restore inheritance. **Duplicate active** captures all current
effective expressions as overrides; subsequent edits to the original row do not
change that copy's captured expressions.

Activate a row in the dialog, or choose it from the workbench's configuration
selector. Editing an existing parameter's expression affects the active
configuration. Name, unit and comment are shared across configurations; new
parameters start with a base expression available to all configurations.
Dimension bindings are also shared: configure the named parameter they reference
to vary a dimension between versions. Configurations can be renamed and deleted;
deleting the active one returns to Default. Default cannot be renamed or deleted.

Each manager Apply or workbench configuration switch is one undo transaction.
Undo/redo preserves parameter and feature identities. Timeline rollback stays
independent. Configuration data and the active row are saved inside `.jcad`, not
in application preferences. Save/reopen starts a fresh undo history as usual.

## Expressions and units

Number fields accept Excel-style formulas such as `=1+2/3` and `=$width/2`.
The leading `=` and `$` reference prefix are optional for numeric expressions;
existing formulas such as `width / 2` continue to work.

Formula-enabled fields have an **ƒx** marker and a distinct border in both
palettes. Active formulas have a blue background/border; invalid formulas have
a red border. Hover or focus the field to inspect its result/error tooltip.
The entered expression stays editable instead of being replaced by its result.

Type **`$`** in an ƒx field to see the current parameters. Continue typing to
filter by the beginning of the name: `$foo` shows `foo`, `fooLength`, and other
names starting with `foo`. Matching is case-sensitive, like parameter references.
Use **↑/↓** to choose and **Enter** or **Tab** to insert, or click a name.
Completion replaces only the parameter reference around the caret and preserves
the rest of the expression. Selecting a suggestion does not apply the field or
commit a feature; press Enter again or use the usual Apply/Finish action.
**Escape** closes only the suggestions first. Quoted string literals do not
trigger completion, and an empty list displays “No matching parameters”.

Text fields treat ordinary input literally. Start with `=` to evaluate a formula:

| Input                    | Result (width = 60 mm) |
| ------------------------ | ---------------------- |
| `="Width: " + 5`         | `Width: 5`             |
| `="Width: " + $width`    | `Width: 60`            |
| `="Half: " + ($width/2)` | `Half: 30`             |
| `="Say ""hello"""`       | `Say "hello"`          |
| `="=literal"`            | `=literal`             |

`+` concatenates when either operand is text; otherwise it adds numbers.
Multiplication/division and parentheses retain numeric precedence. Other
arithmetic operators require numbers. Text interpolation uses locale-independent
canonical model values (millimeters/degrees), without unit suffixes or trailing
zeros. Add a suffix explicitly, for example `="Width: " + $width + " mm"`.
This is the CAD expression grammar, not an Excel workbook or cell-reference engine.

Dimension and sketch-text content/font formulas are saved as parameter bindings
in `.jcad`; configuration changes and undo/redo recalculate them. Text creation
height, angle and spacing formulas create driving sketch dimensions. Edit text
through **Edit selected text…** to recover or replace its original formula.
Unchanged text reuses persisted glyph outlines; changing its content/font needs
that font installed (logical fonts such as `SansSerif` are portable). Existing
sketch-text length and geometric validity limits still apply.

Creation coordinates and other tool options evaluate when used; use driving
sketch constraints for persistent relationships. File paths, parameter names and
comments, configuration names, exporter/plugin options and printing settings
evaluate once when applied. They do not acquire persistent parameter bindings.
Release-version identifiers in the updater remain literal.

Units, quantity arithmetic and conversions use JSR 385 (`javax.measure`) with
Indriya. The parser implements the expression grammar below; it does not define
its own dimensional exponents or conversion factors. See [typed unit APIs](units.md).

| Kind          | Supported values                                                                                   |
| ------------- | -------------------------------------------------------------------------------------------------- |
| Length units  | `mm`, `cm`, `m`, `in`, `ft`                                                                        |
| Angle units   | `deg`, `rad`                                                                                       |
| Dimensionless | `unitless`                                                                                         |
| Operators     | `+`, `-`, `*`, `/`, `^`, unary signs, parentheses                                                  |
| Functions     | `abs`, `sqrt`, `min`, `max`, `floor`, `ceil`, `round`, `sin`, `cos`, `tan`, `asin`, `acos`, `atan` |
| Constants     | `pi`, `e`                                                                                          |

Values are evaluated as doubles in canonical millimeters/degrees, and displayed
in the parameter's selected unit. Unit suffixes work with or without spaces:
`2 in` is 50.8 mm. A final bare number uses the selected unit; bare additive
terms use that unit when compatible. For example, a centimeter parameter with
expression `width + 2` adds 20 mm to a length-valued `width`. Explicit units make
mixed-unit formulas clearest. Products and quotients track dimensions, so
`sqrt(width^2 + height^2)` is a length. Incompatible units are rejected.

Powers associate right to left, and `-2^2` means `-(2^2)`. Trigonometric functions
accept angle quantities or bare numbers interpreted as degrees; inverse trig
returns degrees. Rounding of dimensional quantities operates on their canonical
mm/degree value. `round` uses nearest-even rounding. `min`/`max` accept two
arguments; other functions accept one. Names are case-sensitive, start with an
ASCII letter, and contain only ASCII letters, underscores and dots (pattern
`[a-zA-Z][a-zA-Z_.]*`). The `vb.` prefix is reserved for built-in parameters.
The parameter table groups dotted names by everything before the first dot;
for example, `x.bo` and `x.fo` appear together. Names without a dot appear first.

Unknown names, cycles, invalid syntax, incompatible units, division by zero and
nonfinite results remain editable errors. Formula validity is checked across
all configurations before applying. A numerically valid formula can still make
invalid geometry, such as a zero-width rectangle: the selected variant then
shows diagnostics, clears actionable geometry and disables export until repaired
or another valid variant is activated. Stale worker results cannot replace a
newer document, configuration or preview.

Limits are 128 parameters, 32 named configurations plus Default, 2048 bindings,
64-character parameter names, 80-character configuration names, 512-character
expressions, 256 expression atoms and 32 levels of expression/dependency
evaluation nesting. No scripting or external expression execution is used.

## CLI

After `:cli:installDist`, use `cli/build/install/cli/bin/cli` on Linux or
`cli\build\install\cli\bin\cli.bat` on Windows. The examples below use `cli` with
that `bin` directory on PATH. For an existing reference
document whose feature IDs are `plate` and `thickness`:

```sh
cli parameters set part.jcad --name width --unit mm --expression 60
cli parameters set part.jcad --name thickness --unit mm --expression 5
cli parameters bind part.jcad --feature plate --field width --expression width
cli edit part.jcad --feature thickness --field distance --expression thickness
cli configurations add part.jcad --name Large
cli configurations set part.jcad --name Large --parameter width --expression 80
cli configurations set part.jcad --name Large --parameter thickness --expression 8
cli configurations activate part.jcad --name Large
cli parameters list part.jcad
cli configurations list part.jcad
cli inspect part.jcad
```

For constrained sketches, bind `--field constraint:<dimension-id>`; for example
`constraint:width` on a converted rectangle. `parameters list` prints bindings.
Sketch text also accepts `--field text:<entity-id>:content` or
`text:<entity-id>:font` with a leading-`=` expression. Quote expressions containing
`$` so your shell does not expand parameter references before passing them to CLI.
Use `parameters rename --name width --to plateWidth`, or `parameters remove`.
`configurations add --copy-active` duplicates the active expressions; rename and
remove subcommands take `--name`, with `--to` for renaming. All commands take the
document path. An empty nondefault override restores inheritance. Existing
numeric `edit --value` remains supported and detaches the expression.

Syntax, reference and unit errors do not overwrite the document. Geometrically
invalid but editable definitions follow the existing CLI policy: save the new
definition, report the failed rebuild and return nonzero. Export requires a
complete valid active configuration at the timeline end.

## Fusion comparison and scope

Expression fields show their result type: `ƒx` marks numeric expressions and
`ƒs` marks string expressions, including paths, names and sketch text.

Autodesk documents [configured user parameters](https://help.autodesk.com/cloudhelp/ENU/Fusion-Configurations/files/CFG-USER-PARAMETERS.htm)
and a broader [set of configurable aspects](https://help.autodesk.com/cloudhelp/ENU/Fusion-Configurations/files/CFG-CONFIGURABLE-ASPECTS.htm).
VoxelBrew implements named numeric parameters and parameter configurations in
one design, including use from both sketches and 3D feature dimensions. The
[Fusion parameter reference](https://help.autodesk.com/cloudhelp/ENU/Fusion-Model/files/GUID-76272551-3275-46C4-AE4D-10D58B408C20.htm)
informed the separate expression/unit/value presentation.

This increment does not add feature suppression, material/appearance variants,
text-valued parameters, configuration tables imported from spreadsheets, cloud
version history, print-profile variants, or expression bindings into opaque
external feature-provider payloads. Formulas use the documented grammar above;
Fusion files and every Fusion expression function are not imported.

Schema 6 retains schema 1–5 reading. Generated meshes remain disposable;
parameter definitions and configuration overrides remain editable. See
[M9 acceptance](acceptance/M9.md) for validation evidence.
