# Construction geometry

The desktop **Construct / Konstrukcja** menu contains twenty commands. Construction
objects are editable history features, not model bodies. They participate in
dependency checking, rollback, undo/redo and `.vxb` persistence, and do not add
material to mesh exports.

Choose references in the inspector. Activate an input field before picking a body
face or edge in the viewport. Origin planes, origin axes, vertices, existing
construction objects and explicit XYZ points are also available in the lists.
**Preview** displays a temporary cyan object. **Apply** rebuilds and commits one
feature; **Cancel** or Escape discards the draft. Select an existing construction
feature in the history or tree and choose **Edit construction** to change its inputs.

| Command                          | Inputs and parameter                                                                                      |
| -------------------------------- | --------------------------------------------------------------------------------------------------------- |
| Local coordinate system          | Origin point, X direction, Y direction; directions must be independent                                    |
| Offset plane                     | Plane and signed offset in mm                                                                             |
| Plane at angle                   | Plane, an axis lying in that plane, angle in degrees                                                      |
| Tangent plane                    | Analytic face and relative position along its U interval; V is the middle of its interval                 |
| Midplane                         | Two planes; parallel planes give the plane halfway between them, intersecting planes give either bisector |
| Perpendicular plane              | Straight edge/axis and point                                                                              |
| Plane through two edges          | Two distinct coplanar straight edges/axes                                                                 |
| Plane through three points       | Three noncollinear points                                                                                 |
| Plane along path                 | Edge and normalized arc-length position from 0 to 1                                                       |
| Axis through cylinder/cone/torus | Analytic rotational face                                                                                  |
| Axis perpendicular to surface    | Analytic face or plane, and a point on that surface                                                       |
| Axis through two planes          | Two nonparallel planes                                                                                    |
| Axis through two points          | Two distinct points                                                                                       |
| Axis through edge                | Straight edge/axis                                                                                        |
| Point at vertex                  | Vertex or existing point                                                                                  |
| Point through two edges          | Intersecting straight edges/axes, extended as lines                                                       |
| Point through three planes       | Three planes with a unique common intersection                                                            |
| Center of circle/sphere/torus    | Circular edge, spherical face or toroidal face                                                            |
| Point at edge and plane          | Straight edge/axis and plane with a unique intersection                                                   |
| Point along path                 | Edge and normalized arc-length position from 0 to 1                                                       |

Coordinate-system outputs expose their origin, three axes and three principal
planes to later construction features. A construction plane can also support a
sketch: choose it from the **New sketch** plane selector. Editing the supporting
construction then moves the sketch and rebuilds its dependent solids.

Geometry calculations live in `engine.construction`; `ConstructionFeature` stores
the tool, references and parameters. `Evaluator` resolves these references against
earlier feature outputs. `Evaluation.construction()` returns immutable detached
results only after a successful replay. Numeric `value` parameters can also be
edited through `ModelSession` and bound through the shared parameter system.
Native curve sampling uses OCCT arc length rather than a tessellated approximation.

Schema **10** stores construction definitions and sketch support references.
Older schemas are rejected; no migration or compatibility reader is provided.
Repository reference fixtures use schema 10.

Body topology references use the selected feature output and its OCCT enumeration.
General persistent topology naming across splits and merges remains unqualified;
missing references are reported as failed features, and their dependents are
blocked. No stale construction or solid geometry is published after failure.
