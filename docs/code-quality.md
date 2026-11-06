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

## Sonar analysis and the CI findings gate

SonarScanner for Gradle 7.5.0.8588 analyzes owned Java sources in all eight modules,
the three test source sets, Gradle build scripts and GitHub workflow/scripts.
Generated files, downloaded dependencies, native sources, wrapper files and local
agent configuration are outside this scan. `sonarClasses` compiles all source sets
without running native or desktop tests; `sonar` depends on it. JUnit reports
already available in the checkout are imported. No artificial coverage is reported.

The Ubuntu job in `check.yml` runs Sonar after its local checks and then runs
`node .github/scripts/sonar.cjs gate`. The gate waits up to five minutes for the
uploaded compute task and checks **unresolved new BUG/VULNERABILITY findings with
CRITICAL or BLOCKER severity**. A finding, failed/canceled analysis, timeout,
authentication failure or malformed response makes the job and final **Full check**
fail. It reads every findings page and checks the main-branch analysis has not
been superseded. Runs for the same branch/PR are serialized within this workflow.
The summary links to Sonar; machine-readable evidence is saved in
`build/reports/sonar/findings.json` and uploaded with the test artifacts for **3 days**.

This is VoxelBrew's intentionally narrow **CI findings gate**, not Sonar's default
"Sonar way" quality gate. Sonar may show that default gate as failing for advisory
coverage/duplication/code-smell conditions while VoxelBrew's basic gate passes.
Do not require the separate Sonar default-gate status in branch protection if you
want this relaxed policy; require **Full check** instead. This does not require
a paid custom Sonar gate. The full set of findings stays visible on the dashboard.

Analysis runs on the main branch and same-repository PRs targeting it, compatible
with SonarQube Cloud's basic branch support. Fork PRs, Dependabot runs and other
branches get an explicit **Sonar not run** summary and still run local checks.
The workflow never uses `pull_request_target` to expose secrets to fork code.
Missing Sonar configuration on an eligible run is an error, not a silent skip.

## One-time SonarQube Cloud setup

1. Import the public `magx2/VoxelBrew` repository into your SonarQube Cloud organization.
   Set its main branch to `master`, disable automatic analysis (CI supplies compiled
   Java bytecode), and select the project's new-code definition. An initial main
   analysis establishes the baseline; review its serious findings before relying
   on later PR comparisons.
2. Add GitHub repository variables **SONAR_PROJECT_KEY** and **SONAR_ORGANIZATION**
   using the exact keys displayed by Sonar. The project key need not equal the repository name.
3. Add repository secret **SONAR_TOKEN** with analysis/browse access to this project.
   Never commit the token or paste it into a command argument. The optional variable
   **SONAR_HOST_URL** defaults to `https://sonarcloud.io` (EU).
4. Push when ready. The ordinary CAD check uploads analysis and checks its findings;
   setup and a live analysis are not proven merely by passing local script tests.

A compatible self-hosted SonarQube URL can be supplied through `SONAR_HOST_URL`;
organization is optional there. Its Web API must support `api/ce/task`,
`api/issues/search` with `sinceLeakPeriod`, and `api/project_analyses/search`.
The local fixtures exercise these responses but do not qualify every server version.

For a local authenticated analysis, set the same environment variables securely,
run `./gradlew sonar`, then `node .github/scripts/sonar.cjs gate`. Set `SONAR_BRANCH`
to the analyzed branch if analyzing a non-default branch on a supporting plan.
Ordinary `check` never uploads source or needs a Sonar account/token.

References: [Gradle scanner](https://docs.sonarsource.com/sonarqube-cloud/analyzing-source-code/scanners/sonarscanner-for-gradle),
[GitHub Actions integration](https://docs.sonarsource.com/sonarqube-cloud/analyzing-source-code/ci-based-analysis/github-actions-for-sonarcloud),
[quality gates and plan limits](https://docs.sonarsource.com/sonarqube-cloud/standards/managing-quality-gates/managing-custom-quality-gates).
