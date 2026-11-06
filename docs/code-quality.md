# Code quality checks

The checks deliberately concentrate on defects. They add no coverage, duplication,
method-length, naming, complexity or style quotas. Spotless remains the formatter,
and the existing Java compiler warnings and test suites remain unchanged.

## Local checks

```text
./gradlew basicChecks
./gradlew spotlessCheck test
./gradlew check
```

Use `gradlew.bat` on Windows. `basicChecks` runs SpotBugs on the eight projects'
production bytecode, with **HIGH confidence** and the **CORRECTNESS** category only.
Generated Protobuf/Dagger code is excluded from findings but remains available
for resolving references. Test bytecode is not analyzed. There is no baseline
that silently suppresses existing findings, and scanner errors fail the task.
Every module's `check` includes its production scan; the regular CI runs it once
in the Ubuntu unit job. Reports are under `<module>/build/reports/spotbugs/`.

SpotBugs Gradle plugin 6.5.13 and analyzer 4.10.5 are pinned. See the
[SpotBugs plugin configuration](https://spotbugs.github.io/spotbugs-gradle-plugin/spotbugs-gradle-plugin/com.github.spotbugs.snom/-spot-bugs-extension/).

## CI policy

The Ubuntu unit job runs `./gradlew spotlessCheck basicChecks test`. Reports are
uploaded with the test artifacts for **3 days**. Integration tests and platform
checks run in their separate jobs; the final **Full check** requires all of them
to succeed. No external analysis service, repository variables or analysis token
is required. See [test suites and CI](testing.md) for the platform scope.
