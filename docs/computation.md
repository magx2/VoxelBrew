# CPU computation and threading

VoxelBrew uses a process-wide `CpuWork` pool for independent CPU operations.
The default worker budget is the available processor count capped at eight.
Set the JVM property `voxelbrew.compute.threads` to an integer from 1 to 32 to
override it; `1` performs the same operations serially on the calling worker.
The property is read once, when the pool is first used. No additional dependency
or document schema change is required.

For installed CLI/GUI launchers, `JAVA_TOOL_OPTIONS=-Dvoxelbrew.compute.threads=4`
applies the budget to the application JVM. Remove that environment variable to
restore automatic sizing. The benchmark task has a dedicated Gradle option:

```sh
./gradlew :engine:computeBenchmark -PcomputeThreads=1
./gradlew :engine:computeBenchmark -PcomputeThreads=4
./gradlew :engine:computeBenchmark -PcomputeThreads=8
```

Use `gradlew.bat` on Windows and the native bootstrap from the README. Run these
commands sequentially on an otherwise idle machine. The task uses real OCCT,
checks mesh equivalence, warms native/print scenarios four times and sketch solves
24 times (to allow JVM compilation to settle), and reports nine-sample
median/minimum/maximum times. It exercises 12 bodies with 144 through-holes,
12 placed instances, and a 192-variable constrained sketch. Setup/evaluation is
outside the timed meshing interval. This is an opt-in microbenchmark of the
changed paths, not a claim about whole-document rebuild speed or a CI timing gate.

## Audit and implemented work

| Path                             | Current behavior and reason                                                                                                                                                                                                                                                                                                    |
| -------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Viewport rebuild                 | Completed bodies tessellate and measure volume concurrently. The rebuild owner joins the batch, closes its evaluation and publishes detached data through the existing revision checks.                                                                                                                                        |
| Manufacturing preparation        | Independent bodies tessellate concurrently before immutable mesh maps are assembled in body order. Both CLI and GUI use this engine path.                                                                                                                                                                                      |
| Sketch solve                     | Numerical Jacobian columns use independent perturbed coordinate arrays. Larger matrices (at least 128 variables and 16,384 entries) run concurrently; small sketches stay serial. Matrix entries and the SVD/reduction order stay unchanged.                                                                                   |
| Print preflight                  | Each distinct body's topology is checked once; body checks and each placed instance's transform/footprint/bounds run concurrently. Projected triangles use consistent nonzero winding and one area construction instead of repeated unions. Bed, overlap and support diagnostics are assembled serially in the existing order. |
| Feature history replay           | Sequential: upstream outputs, replacement body identities, shared-plane diagnostics and plugin lifetime depend on ordered evaluation. A future dependency-graph scheduler needs separate ownership/error work.                                                                                                                 |
| OCCT inside one operation        | Remains serial. Tessellation already copies geometry privately; body-level workers avoid multiplying a Java pool by OCCT's internal pool. A single complex body may still benefit from a separately budgeted native mesher later.                                                                                              |
| Export serialization and plugins | Remain synchronous. ZIP/XML streams and external API-1 providers have no concurrent-call contract. Export atomicity is preserved.                                                                                                                                                                                              |
| Slicing                          | PrusaSlicer is an external process with its own computation policy. Java already drains output asynchronously; this change does not add competing slice jobs.                                                                                                                                                                  |
| File operations and GUI updates  | Existing dedicated I/O/rebuild workers and JavaFX publication remain. UI state is never changed by CPU workers.                                                                                                                                                                                                                |

## Ownership, ordering and cancellation

Each batch submits at most the worker budget's number of tasks, distributing
independent indices dynamically. The process-wide fixed pool bounds worker
threads across simultaneous rebuild/preflight calls. It does not use the common
fork/join pool. Nested calls from a CPU worker execute inline to avoid waiting
on the same saturated pool. Threads are daemon threads, named `voxelbrew-compute-*`.

Inputs are immutable or privately copied; outputs occupy distinct indexed slots.
The owner constructs immutable lists/maps after joining, preserving input order.
Native geometry never reaches JavaFX. A failing or interrupted caller waits for
all started callbacks to exit before returning/throwing. Interruption prevents
new indices from starting and restores the caller's interrupt flag; an already
running JNI operation finishes normally. Cancelling a future alone would not
provide this native-resource lifetime guarantee.

Only use `CpuWork` for independent CPU work. Callbacks must not mutate shared
inputs, GUI state or output streams, and must not return owning native handles
without their own explicit failure cleanup. Current callers return detached
meshes/numbers or write independent matrix entries.

See [M11 acceptance](acceptance/M11.md) for measurements and verification.
