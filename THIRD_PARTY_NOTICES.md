# Third-party notices

- **Open CASCADE Technology 7.9.0**, Copyright Open CASCADE SAS and contributors.
  LGPL-2.1 with the Open CASCADE exception. Upstream source:
  https://github.com/Open-Cascade-SAS/OCCT/tree/V7_9_0
  The unmodified source is checksum-pinned and builds shared libraries.
  The bootstrap retains `LICENSE_LGPL_21.txt` and `OCCT_LGPL_EXCEPTION.txt` in
  `.tools/OCCT-7_9_0`; OCCT installs both into
  `engine/build/native/occt-install/share/doc/opencascade`.
  Copies also ship in `third_party/occt/` in this source repository.
  Redistribution must include those notices, corresponding source or the
  required offer, and allow replacement of the shared kernel libraries.
- **Gradle 9.1.0** wrapper, Apache-2.0, https://github.com/gradle/gradle.
  Gradle distribution contains its LICENSE and NOTICE; its license is copied
  beside the wrapper in `gradle/wrapper/LICENSE`.
- **CMake 3.31.6**, BSD-3-Clause, https://github.com/Kitware/CMake/tree/v3.31.6.
  Its downloaded distribution retains the upstream notices.
- **JUnit 5.12.1**, EPL-2.0, test dependency only, https://junit.org/junit5/.
- **Gson 2.15.0**, Apache-2.0, https://github.com/google/gson/tree/gson-parent-2.15.0.
  Parses public release metadata in the updater; upstream license in `third_party/gson/`.
- **Error Prone annotations 2.50.0**, Apache-2.0, https://github.com/google/error-prone/tree/v2.50.0.
  Gson's transitive annotation dependency; upstream license in `third_party/error-prone-annotations/`.
- **Lombok 1.18.48**, MIT, https://projectlombok.org/.
  Compile-only annotation processor; its JAR is not bundled with applications.
  Upstream license: `third_party/lombok/LICENSE`.
- **SLF4J 2.0.20**, MIT, https://www.slf4j.org/.
  Applications bundle the API and JUL provider, preserving VoxelBrew's configured
  logging handlers. Published libraries do not select a backend.
  Upstream license: `third_party/slf4j/LICENSE`.
- **Dagger 2.60.1**, Apache-2.0, https://github.com/google/dagger/tree/dagger-2.60.1.
  Compile-time dependency injection; the runtime and generated component classes
  ship with the applications. The annotation processor and its compiler-only
  dependencies do not. Upstream license: `third_party/dagger/LICENSE.txt`.
- **JSR-330 javax.inject 1**, Apache-2.0, https://github.com/javax-inject/javax-inject.
  Injection annotations and provider interfaces; license in `third_party/javax-inject/`.
- **JSpecify 1.0.0**, Apache-2.0, https://github.com/jspecify/jspecify/tree/v1.0.0.
  Dagger runtime dependency; upstream license in `third_party/jspecify/`.
- **Apache Commons Math 3.6.1**, Apache-2.0,
  https://commons.apache.org/proper/commons-math/. The sketch solver uses its
  singular-value decomposition. Upstream LICENSE and NOTICE accompany app
  images under `third_party/commons-math/`.
- **Protocol Buffers 4.30.2**, BSD-3-Clause, https://github.com/protocolbuffers/protobuf/tree/v30.2.
  Generated document messages are engine implementation details.
- **Apache Commons Compress 1.28.0**, **Commons IO 2.20.0**, **Commons Lang 3.18.0**,
  and **Commons Codec 1.19.0**, Apache-2.0, https://commons.apache.org/.
  Used by the updater's ZIP reader (including Unix permissions and link detection).
  Unmodified upstream LICENSE and NOTICE files extracted from the resolved JARs
  accompany distributions under `third_party/commons-compress/`, `commons-io/`,
  `commons-lang3/`, and `commons-codec/`.
- **Units of Measurement API 2.2** and **Indriya 2.2.4**, BSD-3-Clause,
  https://github.com/unitsofmeasurement/unit-api/tree/2.2 and
  https://github.com/unitsofmeasurement/indriya/tree/2.2.4.
  JSR 385 defines the public units/quantity API; Indriya supplies the implementation.
  Original license texts accompany distributions in `third_party/unit-api/` and
  `third_party/indriya/`.
- **Units of Measurement common library 2.2**, BSD-3-Clause,
  https://github.com/unitsofmeasurement/uom-lib/tree/2.2.
  Indriya runtime dependency; license in `third_party/uom-lib-common/`.
- **Jakarta Dependency Injection API 2.0.1**, Apache-2.0,
  https://github.com/jakartaee/inject/tree/2.0.1.
  Indriya runtime dependency; upstream LICENSE and NOTICE in `third_party/jakarta-inject/`.
- **API Guardian 1.1.2**, Apache-2.0, https://github.com/apiguardian-team/apiguardian/tree/r1.1.2.
  Compile-only annotations for Indriya; not added to application runtime dependencies.
- **Protobuf Gradle plugin 0.9.5**, BSD-3-Clause, build dependency only,
  https://github.com/google/protobuf-gradle-plugin/tree/v0.9.5.
- **picocli 4.7.7**, Apache-2.0, https://github.com/remkop/picocli/tree/v4.7.7.
  CLI dependency only.
- **OpenJFX 21.0.13**, Copyright Oracle and contributors, GPL-2.0 with the
  Classpath exception. Only base, graphics and controls are included in the GUI.
  Source: https://github.com/openjdk/jfx21u (tag `21.0.13+1`).
  Upstream license and licensing clarification are retained in
  `third_party/javafx/` and copied into the development GUI distribution.
- **Microsoft Windows SDK C++ 10.0.22621.1** NuGet packages, Microsoft's SDK
  license as included in the archives. Build prerequisite only, downloaded
  unchanged from https://www.nuget.org/packages/Microsoft.Windows.SDK.CPP/10.0.22621.1
  and https://www.nuget.org/packages/Microsoft.Windows.SDK.CPP.x64/10.0.22621.1.
  SDK tools are not part of the application runtime.

- **PrusaSlicer 2.7.2**, AGPL-3.0, external executable (not embedded or redistributed
  in VoxelBrew distributions). Source and bundled notices:
  https://github.com/prusa3d/PrusaSlicer/tree/version_2.7.2.
  Bootstrap downloads the unchanged Windows archive with its licenses, or installs
  Ubuntu's `2.7.2+dfsg-1build2` package. The resolved generic process configuration
  was generated by that version and is committed under VoxelBrew's AGPL-3.0 license.
  The macOS bootstrap extracts the checksum-pinned upstream package locally,
  without installing its printer drivers.

- **OpenJDK 21 runtime**, GPL-2.0 with the Classpath exception. App images contain
  the Java 21 toolchain selected by Gradle, with its `legal/` directory retained by
  jlink. CI uses Eclipse Temurin (https://adoptium.net/temurin/releases/?version=21);
  local Windows qualification uses Amazon Corretto (https://github.com/corretto/corretto-21).
  Runtime version and location are recorded by packaged GUI acceptance.
  No external Java installation is needed by the app images.
- Protocol Buffers and picocli license texts accompany the app images in
  `third_party/protobuf/` and `third_party/picocli/`.

A narrow JNI bridge was selected to keep required operations, ownership and
native loading auditable, and to compile the same kernel on both development
platforms. No third-party Java binding has been accepted or bundled.

- **Bundled printer product images**, AGPL-3.0, from OrcaSlicer contributors,
  Bambu Studio contributors, and PrusaSlicer contributors / Prusa Research.
  Unmodified image files, individual upstream source URLs, checksums and preset
  mappings are in `impl/slicer/src/main/resources/dev/voxelbrew/slicer/printers/`, with
  `photo-sources.json` attribution and the accompanying `PHOTO-LICENSE.txt`.
  Product names and depicted marks belong to their respective owners.

- **TwelveMonkeys ImageIO 3.15.0**, BSD-3-Clause, provides pure Java WebP decoding
  for GUI printer thumbnails. Copyright (c) 2008-2020 Harald Kuhr. Source:
  https://github.com/haraldk/TwelveMonkeys/tree/twelvemonkeys-3.15.0.
  License: `third_party/twelvemonkeys/LICENSE.txt`. The WebP unit-test fixtures
  `small_1x1.webp` and `lossless.transparent.webp` are unmodified files from
  that tag under `imageio/imageio-webp/src/test/resources/webp/`.

# Printer transport dependencies

The GUI MCP listener bundles the official **MCP Java SDK 1.1.5** (MIT),
https://github.com/modelcontextprotocol/java-sdk, and **Eclipse Jetty 12.0.16**
(EPL-2.0 / Apache-2.0), https://github.com/jetty/jetty.project.
Their separately shipped JARs and transitive Reactor, Reactive Streams, Jackson
and Jakarta Servlet dependencies retain their upstream license metadata.

Physical Bambu connections use [JBambuAPI 1.7.0](https://github.com/magx2/JBambuAPI)
under LGPL-2.1, as an unmodified separate JAR. Its source and license are available
from the linked project. It uses Eclipse Paho MQTT 1.2.5 (EPL-1.0 / EDL-1.0)
and Jackson 2.18.2 (Apache-2.0).
FTPS transfer uses Apache Commons Net 3.12.0 and Commons IO 2.20.0 (Apache-2.0).
Their distributions retain embedded license and notice files. Users may replace
the separately shipped library JARs with compatible modified versions.
