# Manufacturing fixtures

These small schema-2 documents are generated from built-in rectangle sketches and
extrusions by `SlicerAcceptanceTest`; geometry is in millimeters. All build items
have translation (20,30,0). They are redistributable under the repository license.

- `shelf.jcad`: leg (0,0,0)–(10,40,10), roof (0,0,10)–(30,40,12), 20 mm projection.
- `recessed-ledge.jcad`: lower shelf (0,0,0)–(30,40,2), leg begins at Z=2,
  otherwise the same roof. Compare support starts above the lower shelf.
- `cube.jcad`: (0,0,0)–(30,30,10), two walls and zero infill initially.

Equal underside regions in **design coordinates**:

| Region | Box | Interior deposition sampling window |
| --- | --- | --- |
| A | X 10–30, Y 0–20, Z 0–12 | X 15–25, Y 3–17, Z 4–6 |
| B | X 10–30, Y 20–40, Z 0–12 | X 15–25, Y 25–35, Z 4–6 |

Add (20,30,0) for build-space inspection. The integration tests commit the
matching blocker/enforcer to the `roof` instance, save, reopen and re-slice.
Actual jobs, projects, input documents and diagnostic logs are retained under
`engine/build/printing-artifacts` and uploaded by CI.
