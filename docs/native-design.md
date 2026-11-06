# M0 native boundary

## Build and binding decision

Use an application-owned JNI adapter and the same unmodified OCCT 7.9.0 source
archive on Windows and Ubuntu. This keeps redistribution, explicit handle
ownership and the required primitives under one reproducible build, without
adopting a broad binding's native packages, pointer API or release schedule.
No third-party Java binding is bundled. The official upstream OCCT source and
build documentation are at
https://github.com/Open-Cascade-SAS/OCCT/tree/V7_9_0.

The only compiled OCCT toolkit roots are `TKBool` and `TKPrim`; OCCT resolves
their modeling/foundation dependencies. Visualization, document frameworks,
exchange, Draw and DE tools are disabled. The Java adapter supports a rectangular
XY face/prism, a through-Z circular subtraction, exact measurements and point
classification. Additional geometry operations require an explicit later change.

Build steps are Gradle tasks, inspectable individually:

```text
bootstrap-native → engine:configureOcct → engine:buildOcct
                 → engine:configureNative → engine:buildNative
                 → engine:nativeIntegrationTest
```

`configureOcct` invokes the pinned CMake with the locked compiler, shared
libraries and selected toolkits. `buildOcct` builds Release and installs under
`engine/build/native/occt-install`. `configureNative` discovers the exact OCCT
version and the Java 21 toolchain's JNI headers. `buildNative` compiles the
bridge and copies its dynamic dependencies into a single runtime directory.
The complete commands are visible with Gradle `--info`.
The copy step reuses byte-identical dependencies so an open desktop does not block
a check merely by locking an unchanged DLL. Changed native libraries still need
to be replaced and continue to invalidate the relevant tests.

## Ownership / failure invariants

- Only `NativeKernel`, a package-private implementation detail, exchanges tokens.
- Each successfully published `Solid` corresponds to one registry entry.
- Every creator validates the B-rep and counts exactly one solid before inserting
  it in the registry. A failed creator inserts nothing.
- Native algorithms operate on retained `shared_ptr<const TopoDS_Shape>` values.
  Cut is explicitly non-destructive and the input is not transferred or closed.
- An operation's Java monitor prevents its owner from closing mid-call. A
  separate registry mutex protects different concurrently used owners.
- `close()` erases one entry and is idempotent. Callers must close all returned
  solids; there is no finalizer or promise that GC releases native memory.
- Native failures become `GeometryException` with the operation name. Invalid
  public dimensions fail before allocating, and JNI repeats numeric validation.
- A missing runtime gives a load diagnostic with OS/architecture and rebuild
  instructions; tests do not swallow that diagnostic or skip the gate.

## Deliberate M0 boundaries

Coordinates and dimensions are millimeters and double precision. Only XY or
parallel offset planes exist at this stage. Faces are not exposed as persistent
identifiers. Through-all is computed from current solid bounds, so M1 can replay
the cut after an upstream dimension edit. Serialization, editable graph identity,
mesh approximation, graphical interaction and print setup are subsequent gates.
