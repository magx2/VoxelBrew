# Third-party notices

* **Open CASCADE Technology 7.9.0**, Copyright Open CASCADE SAS and contributors.
  LGPL-2.1 with the Open CASCADE exception. Upstream source:
  https://github.com/Open-Cascade-SAS/OCCT/tree/V7_9_0
  The unmodified source is checksum-pinned and builds shared libraries.
  The bootstrap retains `LICENSE_LGPL_21.txt` and `OCCT_LGPL_EXCEPTION.txt` in
  `.tools/OCCT-7_9_0`; OCCT installs both into
  `engine/build/native/occt-install/share/doc/opencascade`.
  Copies also ship in `third_party/occt/` in this source repository.
  Redistribution must include those notices, corresponding source or the
  required offer, and allow replacement of the shared kernel libraries.
* **Gradle 9.1.0** wrapper, Apache-2.0, https://github.com/gradle/gradle.
  Gradle distribution contains its LICENSE and NOTICE; its license is copied
  beside the wrapper in `gradle/wrapper/LICENSE`.
* **CMake 3.31.6**, BSD-3-Clause, https://github.com/Kitware/CMake/tree/v3.31.6.
  Its downloaded distribution retains the upstream notices.
* **JUnit 5.12.1**, EPL-2.0, test dependency only, https://junit.org/junit5/.
* **Microsoft Windows SDK C++ 10.0.22621.1** NuGet packages, Microsoft's SDK
  license as included in the archives. Build prerequisite only, downloaded
  unchanged from https://www.nuget.org/packages/Microsoft.Windows.SDK.CPP/10.0.22621.1
  and https://www.nuget.org/packages/Microsoft.Windows.SDK.CPP.x64/10.0.22621.1.
  SDK tools are not part of the application runtime.

A narrow JNI bridge was selected to keep required operations, ownership and
native loading auditable, and to compile the same kernel on both development
platforms. No third-party Java binding has been accepted or bundled.
