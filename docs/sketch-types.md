# 2D sketch geometry types (M8)

The inventory below was checked against Autodesk's Fusion Design sketch
documentation on 2026-11-05. It covers geometry creation families and their
construction methods. Modify operations (trim, extend, offset), projection,
patterns and mirror are operations on geometry, rather than additional entity
types, and are outside this increment.

| Fusion family | VoxelBrew creation methods                                             | Autodesk reference                                                                                         |
| ------------- | ---------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------- |
| Line / Point  | Existing Line and Point tools                                          | Retained from M7                                                                                           |
| Rectangle     | 2-point, 3-point, center                                               | [Rectangles](https://help.autodesk.com/cloudhelp/ENU/Fusion-Sketch/files/SKT-SKETCH-CREATE-RECTANGLES.htm) |
| Circle        | Center diameter, 2-point, 3-point, 2-tangent, 3-tangent                | [Circles](https://help.autodesk.com/cloudhelp/ENU/Fusion-Sketch/files/SKT-SKETCH-CREATE-CIRCLES.htm)       |
| Arc           | Center point, 3-point, tangent                                         | [Arcs](https://help.autodesk.com/cloudhelp/ENU/Fusion-Sketch/files/SKT-SKETCH-CREATE-ARCS.htm)             |
| Polygon       | Inscribed, circumscribed, edge                                         | [Polygons](https://help.autodesk.com/cloudhelp/ENU/Fusion-Sketch/files/SKT-SKETCH-CREATE-POLYGONS.htm)     |
| Ellipse       | Center, first axis endpoint, second-axis point                         | [Ellipse](https://help.autodesk.com/cloudhelp/ENU/Fusion-Sketch/files/SKT-CREATE-ELLIPSE.htm)              |
| Slot          | Center-to-center, overall, center point, 3-point arc, center point arc | [Slots](https://help.autodesk.com/cloudhelp/ENU/Fusion-Sketch/files/SKT-SKETCH-CREATE-SLOTS.htm)           |
| Spline        | Fit point; control point (degree 3 or 5); retained cubic Bezier tool   | [Spline creation](https://help.autodesk.com/cloudhelp/ENU/Fusion-Sketch/files/SKT-CREATE-SPLINES.htm)      |
| Conic         | Endpoints, tangent-intersection vertex and rho                         | [Conics](https://help.autodesk.com/cloudhelp/ENU/Fusion-Sketch/files/SKT-CREATE-CONIC-CURVE.htm)           |
| Text          | Baseline text and associative text on a selected curve                 | [Text](https://help.autodesk.com/cloudhelp/ENU/Fusion-Sketch/files/SKT-SKETCH-CREATE-TEXT.htm)             |

## Workbench

Open **New sketch…** or **Edit sketch / constraints…**, then choose
**More sketch types…**. The instruction line gives the exact point order. Click
the canvas, or enter **Next point X/Y** and choose **Add numeric point**.
Tool-specific numeric settings remain editable after an invalid input; choose
**Complete / retry** to retry the same points. Escape discards the active drawing.

For tangent circles, select two or three lines before choosing the tool. Set
the radius for two-line tangency, then click near the desired circle center to
choose among valid solutions. Tangent Arc starts at a selected curve endpoint.
Three-point arcs take start, end, then a point on the arc, so either sweep
direction is available. Polygon Sides sets 3–32 edges; the third Edge Polygon
point chooses which side of the selected edge contains the polygon.

Straight slots use two cap centers, overall ends, or a center and cap center;
the final point determines half-width. Arc slots use a circular centerline and
a final radial width point. Slot edges retain coincident/tangent constraints,
equal caps and parallel or concentric sides. Their arc radii can be dimensioned.

Fit and control splines accept up to 32 points. Use **Closed curve** and
**Complete / retry** (or Enter with canvas focus) to finish them. Clicking the
first point also closes a spline. Control splines support degree 3/5 and show
their control frame. Indexed points are selectable/draggable; the reference
dropdown also exposes `spline.fit[0]` and `spline.control[0]` etc. Open fit splines
use natural cubic interpolation; closed fits use periodic cubic interpolation.
This interpolation is not intended to reproduce Fusion's proprietary fit exactly.

Ellipse first/second radii, conic rho, text height and spacing are available in
**Driving value**, alongside existing dimensions. Angle also applies to a
single ellipse or text entity. Conic rho lies strictly between 0 and 1; 0.5 is
parabolic. The first ellipse axis need not be the longer one.

Text supports a font family, regular/bold/italic style, height, rotation and
additional spacing. Height uses the font's em size; glyph cap heights vary by
font. `SansSerif`, `Serif` and `Monospaced` are portable creation choices.
Select text and choose **Edit selected text…** to change content/font or repair,
replace or detach its path. Persisted glyph outlines keep reopened geometry
unchanged even if the original font is absent. Changing the content requires
the chosen font to be available. Text is limited to 32 code points; shaping,
parameter expressions, SHX strokes, frame alignment and Fusion's full text
formatting palette are not implemented.

Text on Path uses a selected curve. Its click establishes the start offset;
glyphs follow the curve when it changes. The path is removed from the selected
profile boundary, while remaining editable. Open paths reject text extending
beyond their length; closed paths wrap. Glyph outlines stay exact; placement
along a curved path uses a sampled arc-length approximation.

## Solids, identities and files

Every new type participates in the existing sketch transaction, local undo/redo,
Finish/Cancel and document history. Construction curves do not form profiles.
Slots and rectangles include their geometric constraints; dimension and constrain
other generated entities as needed. Advanced tool point snaps seed positions;
use explicit Coincident constraints to retain references to other geometry.

Closed profiles can contain holes, separate regions and nested islands.
Extrusion creates one independent solid per outer region; through-cut applies
all selected regions to the target. The first region retains the existing
`body` output, with additional outputs named from their boundary identities.
Text glyph identities include their character position so editing a preceding
glyph does not renumber all following outlines. Generated topology is still
revision-local; changing topology can add/remove region outputs.

Ellipse, conic, B-spline, arc and Bezier boundaries go to OCCT as exact curves.
Display sampling does not flatten exported solids. Existing STL/3MF tessellation
tolerances and export guards are unchanged. There are at most 64 persisted
entities and 256 constraints per sketch; an evaluated region may contain up to
4096 curve segments and 256 loops. Schema 5 retains indexed references, spline
options, text source/outlines and path identity; schemas 1–4 remain readable.

## CLI

The installed `cli` (`cli.bat` on Windows) uses the same constructors and solver:

```sh
cli new curves.vxb
cli sketch new curves.vxb --id outline
cli sketch draw curves.vxb --sketch outline --id oval --tool ELLIPSE --points 0,0,20,0,0,10
cli sketch constrain curves.vxb --sketch outline --id radius --type MAJOR_RADIUS --refs oval --value 20
cli extrude curves.vxb --id thickness --sketch outline --distance 5
cli edit curves.vxb --feature outline --field constraint:radius --value 25
cli inspect curves.vxb
```

`cli sketch draw --help` lists every tool and option. `--points` is an ordered
list of x,y pairs; `--refs` selects tangent lines/endpoints or a text path.
`--closed`, `--degree`, `--sides`, `--rho`, `--radius`, `--text`, `--font`,
`--style`, `--height`, `--angle` (degrees) and `--spacing` configure the tools.
Raw `sketch entity` supports ELLIPSE, CONIC, FIT_SPLINE and CONTROL_SPLINE in
addition to existing kinds. Raw ellipse angle and arc parameters use radians.

Edit text with `cli sketch text-edit file.vxb --sketch label --id text --text NEW`.
An optional `--font` changes its font and `--path` repairs/replaces a path (an
empty value detaches it). Save failures and rejected invalid constructors leave
the prior file unchanged. See [M8 acceptance](acceptance/M8.md) for checks.
