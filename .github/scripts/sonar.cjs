const fs = require("node:fs");
const path = require("node:path");
const { setTimeout: sleep } = require("node:timers/promises");

// Keep this gate deliberately small; coverage, duplication and code smells are advisory.
const blockingTypes = ["BUG", "VULNERABILITY"];
const blockingSeverities = ["BLOCKER", "CRITICAL"];

function analysisPlan(env, event) {
  const main = event.repository?.default_branch || "master";
  if (env.GITHUB_ACTOR === "dependabot[bot]")
    return { analyze: false, reason: "Dependabot runs have no Sonar secret." };
  if (env.GITHUB_EVENT_NAME === "pull_request") {
    const pr = event.pull_request;
    if (!pr?.head?.repo?.full_name || !pr.base?.repo?.full_name)
      throw new Error("Incomplete pull request metadata; cannot determine analysis scope.");
    if (pr.head.repo.full_name !== pr.base.repo.full_name)
      return { analyze: false, reason: "Fork pull requests do not receive Sonar credentials." };
    if (pr.base.ref !== main)
      return { analyze: false, reason: "Sonar runs for pull requests targeting the main branch." };
  } else if (["push", "workflow_dispatch"].includes(env.GITHUB_EVENT_NAME)) {
    if (env.GITHUB_REF !== `refs/heads/${main}`)
      return { analyze: false, reason: "Sonar branch analysis runs on the main branch." };
  } else throw new Error(`Unsupported Sonar event: ${env.GITHUB_EVENT_NAME}`);
  const required = ["SONAR_TOKEN", "SONAR_PROJECT_KEY"];
  if (
    ["sonarcloud.io", "sonarqube.us"].includes(
      new URL(env.SONAR_HOST_URL || "https://sonarcloud.io").hostname,
    )
  )
    required.push("SONAR_ORGANIZATION");
  for (const name of required)
    if (!env[name]?.trim())
      throw new Error(`Configure ${name} before enabling Sonar CI; see docs/code-quality.md.`);
  return {
    analyze: true,
    reason: "Analyze this revision and check new serious bugs/security findings.",
  };
}

function scope(env, event) {
  if (env.GITHUB_EVENT_NAME === "pull_request") {
    if (!Number.isSafeInteger(event.number) || event.number <= 0)
      throw new Error("Missing pull request number.");
    return { pullRequest: String(event.number) };
  }
  const branch = env.SONAR_BRANCH || env.GITHUB_REF_NAME;
  return branch ? { branch, sinceLeakPeriod: "true" } : { sinceLeakPeriod: "true" };
}

function readTask(text, projectKey, host) {
  const values = Object.fromEntries(
    text
      .split(/\r?\n/)
      .filter((line) => line.includes("="))
      .map((line) => {
        const i = line.indexOf("=");
        return [line.slice(0, i), line.slice(i + 1)];
      }),
  );
  if (values.projectKey !== projectKey || !values.ceTaskId)
    throw new Error("Sonar report is missing or belongs to a different project.");
  if (new URL(values.serverUrl).href.replace(/\/$/, "") !== new URL(host).href.replace(/\/$/, ""))
    throw new Error("Sonar report server does not match SONAR_HOST_URL.");
  return values.ceTaskId;
}

async function waitForAnalysis(
  get,
  taskId,
  projectKey,
  { now = Date.now, pause = sleep, timeout = 300000 } = {},
) {
  const deadline = now() + timeout;
  while (now() < deadline) {
    const { task } = await get("api/ce/task", { id: taskId });
    if (!task || task.id !== taskId || task.componentKey !== projectKey)
      throw new Error("Sonar returned an unexpected analysis task.");
    if (task.status === "SUCCESS") {
      if (!task.analysisId) throw new Error("Sonar completed without an analysis ID.");
      return task.analysisId;
    }
    if (!["PENDING", "IN_PROGRESS"].includes(task.status))
      throw new Error(`Sonar analysis did not succeed (${task.status}).`);
    await pause(Math.min(5000, Math.max(0, deadline - now())));
  }
  throw new Error("Timed out waiting for Sonar analysis (5 minutes); findings were not checked.");
}

async function findings(get, projectKey, selection) {
  const all = [];
  for (let page = 1; page <= 20; page++) {
    const result = await get("api/issues/search", {
      componentKeys: projectKey,
      resolved: "false",
      types: blockingTypes.join(","),
      severities: blockingSeverities.join(","),
      ...selection,
      p: String(page),
      ps: "500",
    });
    const total = result.paging?.total ?? result.total;
    if (!Number.isSafeInteger(total) || total < 0 || !Array.isArray(result.issues))
      throw new Error("Sonar returned an invalid findings response.");
    for (const issue of result.issues) {
      if (
        !blockingTypes.includes(issue.type) ||
        !blockingSeverities.includes(issue.severity) ||
        !issue.key
      )
        throw new Error("Sonar did not honor the requested findings filter.");
    }
    all.push(...result.issues);
    if (all.length === total) return all;
    if (!result.issues.length || all.length > total)
      throw new Error("Sonar returned an incomplete findings page.");
  }
  throw new Error("Sonar findings exceed the API paging limit; review the dashboard.");
}

function apiClient(host, token, fetcher = fetch) {
  const base = new URL(host.endsWith("/") ? host : `${host}/`);
  if (
    base.protocol !== "https:" &&
    !(base.protocol === "http:" && ["localhost", "127.0.0.1"].includes(base.hostname))
  )
    throw new Error("Sonar must use HTTPS (HTTP is permitted only for local tests).");
  if (base.username || base.password || base.search || base.hash)
    throw new Error("Invalid Sonar server URL.");
  if (!token?.trim()) throw new Error("SONAR_TOKEN is required.");
  return async (route, params) => {
    const url = new URL(route, base);
    url.search = new URLSearchParams(params).toString();
    const response = await fetcher(url, {
      headers: { Authorization: `Bearer ${token}`, Accept: "application/json" },
      redirect: "error",
      signal: AbortSignal.timeout(30000),
    });
    if (!response.ok)
      throw new Error(
        `Sonar ${route} failed (HTTP ${response.status}); findings were not checked.`,
      );
    return response.json();
  };
}

function summary(text, env) {
  if (env.GITHUB_STEP_SUMMARY) fs.appendFileSync(env.GITHUB_STEP_SUMMARY, `${text}\n`);
  console.log(text);
}

async function main(command, env = process.env) {
  const event = env.GITHUB_EVENT_PATH
    ? JSON.parse(fs.readFileSync(env.GITHUB_EVENT_PATH, "utf8"))
    : {};
  if (command === "plan") {
    const plan = analysisPlan(env, event);
    if (env.GITHUB_OUTPUT) fs.appendFileSync(env.GITHUB_OUTPUT, `analyze=${plan.analyze}\n`);
    summary(`Sonar: ${plan.analyze ? "scheduled" : "not run"}. ${plan.reason}`, env);
    return;
  }
  if (command !== "gate") throw new Error("Usage: node .github/scripts/sonar.cjs plan|gate");
  const projectKey = env.SONAR_PROJECT_KEY;
  if (!projectKey?.trim()) throw new Error("SONAR_PROJECT_KEY is required.");
  const host = env.SONAR_HOST_URL || "https://sonarcloud.io";
  const taskId = readTask(fs.readFileSync("build/sonar/report-task.txt", "utf8"), projectKey, host);
  const get = apiClient(host, env.SONAR_TOKEN);
  const analysisId = await waitForAnalysis(get, taskId, projectKey);
  const selection = scope(env, event);
  // Main-branch queries are mutable; reject a superseded analysis rather than inspecting old results.
  async function requireCurrent() {
    if (selection.pullRequest) return;
    const result = await get("api/project_analyses/search", {
      project: projectKey,
      ...(selection.branch ? { branch: selection.branch } : {}),
      ps: "1",
    });
    if (result.analyses?.[0]?.key !== analysisId)
      throw new Error(
        "This Sonar analysis was superseded; rerun the findings check for the current revision.",
      );
  }
  await requireCurrent();
  const issues = await findings(get, projectKey, selection);
  await requireCurrent();
  const dashboard = new URL(`${host.replace(/\/$/, "")}/dashboard`);
  dashboard.search = new URLSearchParams({
    id: projectKey,
    ...(selection.pullRequest
      ? { pullRequest: selection.pullRequest }
      : selection.branch
        ? { branch: selection.branch }
        : {}),
  }).toString();
  const report = {
    analysisId,
    taskId,
    projectKey,
    scope: selection,
    blockingTypes,
    blockingSeverities,
    dashboard: dashboard.href,
    issues,
  };
  const destination = "build/reports/sonar/findings.json";
  fs.mkdirSync(path.dirname(destination), { recursive: true });
  fs.writeFileSync(destination, `${JSON.stringify(report, null, 2)}\n`);
  summary(
    `Sonar basic findings gate: **${issues.length ? "FAILED" : "PASSED"}** — ${issues.length} new critical/blocker bugs or vulnerabilities. [Analysis](${dashboard.href}). Coverage, duplication and code smells are advisory.`,
    env,
  );
  if (issues.length)
    throw new Error(
      `${issues.length} blocking Sonar finding(s); see ${destination} and the analysis dashboard.`,
    );
}

module.exports = { analysisPlan, scope, readTask, waitForAnalysis, findings, apiClient, main };
if (require.main === module)
  main(process.argv[2]).catch((error) => {
    console.error(`Sonar check failed: ${error.message}`);
    process.exitCode = 1;
  });
