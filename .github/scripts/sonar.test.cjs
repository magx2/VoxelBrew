const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const http = require("node:http");
const { spawn } = require("node:child_process");
const {
  analysisPlan,
  scope,
  readTask,
  waitForAnalysis,
  findings,
  apiClient,
} = require("./sonar.cjs");

const env = {
  GITHUB_EVENT_NAME: "push",
  GITHUB_REF: "refs/heads/master",
  SONAR_TOKEN: "test-token",
  SONAR_PROJECT_KEY: "project",
  SONAR_ORGANIZATION: "org",
};
const event = {
  repository: { default_branch: "master" },
  number: 7,
  pull_request: {
    head: { repo: { full_name: "owner/repo" } },
    base: { ref: "master", repo: { full_name: "owner/repo" } },
  },
};

test("trusted main and PR analyses require configuration; expected skips are explicit", () => {
  assert.equal(analysisPlan(env, event).analyze, true);
  assert.equal(analysisPlan({ ...env, GITHUB_EVENT_NAME: "pull_request" }, event).analyze, true);
  assert.equal(
    analysisPlan({ ...env, SONAR_HOST_URL: "https://sonar.example", SONAR_ORGANIZATION: "" }, event)
      .analyze,
    true,
  );
  for (const key of ["SONAR_TOKEN", "SONAR_PROJECT_KEY", "SONAR_ORGANIZATION"])
    assert.throws(() => analysisPlan({ ...env, [key]: "" }, event), new RegExp(key));
  assert.equal(
    analysisPlan({ ...env, GITHUB_REF: "refs/heads/topic", SONAR_TOKEN: "" }, event).analyze,
    false,
  );
  assert.equal(
    analysisPlan({ ...env, GITHUB_ACTOR: "dependabot[bot]", SONAR_TOKEN: "" }, event).analyze,
    false,
  );
  const fork = structuredClone(event);
  fork.pull_request.head.repo.full_name = "external/repo";
  assert.equal(
    analysisPlan({ ...env, GITHUB_EVENT_NAME: "pull_request", SONAR_TOKEN: "" }, fork).analyze,
    false,
  );
  fork.pull_request.head.repo = null;
  assert.throws(
    () => analysisPlan({ ...env, GITHUB_EVENT_NAME: "pull_request" }, fork),
    /metadata/,
  );
  assert.throws(
    () => analysisPlan({ ...env, GITHUB_EVENT_NAME: "pull_request_target" }, event),
    /Unsupported/,
  );
});

test("pipeline command publishes evidence and fails for actual blocking API results", async (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "VoxelBrew Sonar gate "));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  fs.mkdirSync(path.join(root, "build/sonar"), { recursive: true });
  let issues = [],
    analysis = "analysis";
  const server = http.createServer((request, response) => {
    if (request.headers.authorization !== "Bearer test-token") {
      response.writeHead(401).end();
      return;
    }
    const url = new URL(request.url, "http://localhost");
    const responses = {
      "/api/ce/task": {
        task: { id: "task", componentKey: "project", status: "SUCCESS", analysisId: "analysis" },
      },
      "/api/project_analyses/search": { analyses: [{ key: analysis }] },
      "/api/issues/search": { total: issues.length, issues },
    };
    response
      .writeHead(200, { "Content-Type": "application/json" })
      .end(JSON.stringify(responses[url.pathname]));
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  t.after(() => new Promise((resolve) => server.close(resolve)));
  const host = `http://127.0.0.1:${server.address().port}`;
  fs.writeFileSync(
    path.join(root, "build/sonar/report-task.txt"),
    `projectKey=project\nserverUrl=${host}\nceTaskId=task\n`,
  );
  const script = path.join(__dirname, "sonar.cjs");
  async function run() {
    return new Promise((resolve, reject) => {
      const child = spawn(process.execPath, [script, "gate"], {
        cwd: root,
        env: {
          ...process.env,
          ...env,
          SONAR_HOST_URL: host,
          GITHUB_EVENT_PATH: "",
          GITHUB_STEP_SUMMARY: "",
          GITHUB_REF_NAME: "master",
          SONAR_BRANCH: "master",
        },
      });
      let output = "";
      child.stdout.on("data", (data) => {
        output += data;
      });
      child.stderr.on("data", (data) => {
        output += data;
      });
      child.on("error", reject);
      child.on("close", (code) => resolve({ code, output }));
    });
  }
  let result = await run();
  assert.equal(result.code, 0, result.output);
  assert.match(result.output, /PASSED/);
  issues = [{ key: "bug", type: "BUG", severity: "BLOCKER" }];
  result = await run();
  assert.equal(result.code, 1, result.output);
  assert.match(result.output, /FAILED/);
  const report = JSON.parse(
    fs.readFileSync(path.join(root, "build/reports/sonar/findings.json"), "utf8"),
  );
  assert.equal(report.issues[0].key, "bug");
  assert.equal(report.analysisId, "analysis");
  assert.ok(!JSON.stringify(report).includes("test-token"));
  issues = [];
  analysis = "newer-analysis";
  result = await run();
  assert.equal(result.code, 1);
  assert.match(result.output, /superseded/);
});

test("scope uses new code for branches and the exact PR number for pull requests", () => {
  assert.deepEqual(scope({ GITHUB_REF_NAME: "master" }, event), {
    branch: "master",
    sinceLeakPeriod: "true",
  });
  assert.deepEqual(scope({ GITHUB_EVENT_NAME: "pull_request" }, event), { pullRequest: "7" });
  assert.throws(() => scope({ GITHUB_EVENT_NAME: "pull_request" }, {}), /number/);
});

test("scanner task metadata cannot redirect credentials or select another project", () => {
  const report = "projectKey=project\r\nserverUrl=https://sonarcloud.io\r\nceTaskId=task\r\n";
  assert.equal(readTask(report, "project", "https://sonarcloud.io/"), "task");
  assert.throws(() => readTask(report, "other", "https://sonarcloud.io"), /project/);
  assert.throws(() => readTask(report, "project", "https://other.example"), /server/);
  assert.throws(
    () => readTask("projectKey=project", "project", "https://sonarcloud.io"),
    /missing/,
  );
});

test("polling waits for the uploaded analysis and rejects failure, cancellation and timeout", async () => {
  let index = 0;
  const get = async () => ({
    task: {
      id: "task",
      componentKey: "project",
      status: ["PENDING", "IN_PROGRESS", "SUCCESS"][index++],
      analysisId: "analysis",
    },
  });
  assert.equal(
    await waitForAnalysis(get, "task", "project", { pause: async () => {} }),
    "analysis",
  );
  for (const status of ["FAILED", "CANCELED", "UNKNOWN"])
    await assert.rejects(
      waitForAnalysis(
        async () => ({ task: { id: "task", componentKey: "project", status } }),
        "task",
        "project",
      ),
      /did not succeed/,
    );
  await assert.rejects(
    waitForAnalysis(
      async () => ({ task: { id: "other", componentKey: "project", status: "SUCCESS" } }),
      "task",
      "project",
    ),
    /unexpected/,
  );
  let now = 0;
  await assert.rejects(
    waitForAnalysis(
      async () => ({ task: { id: "task", componentKey: "project", status: "PENDING" } }),
      "task",
      "project",
      {
        now: () => now,
        pause: async (ms) => {
          now += ms;
        },
        timeout: 10,
      },
    ),
    /Timed out/,
  );
});

test("findings filters serious bugs/security and reads every page", async () => {
  const seen = [];
  const issue = (key) => ({ key, type: "BUG", severity: "CRITICAL" });
  const result = await findings(
    async (route, query) => {
      assert.equal(route, "api/issues/search");
      assert.equal(query.types, "BUG,VULNERABILITY");
      assert.equal(query.severities, "BLOCKER,CRITICAL");
      assert.equal(query.resolved, "false");
      assert.equal(query.sinceLeakPeriod, "true");
      seen.push(query.p);
      return { paging: { total: 2 }, issues: [issue(query.p)] };
    },
    "project",
    { branch: "master", sinceLeakPeriod: "true" },
  );
  assert.equal(result.length, 2);
  assert.deepEqual(seen, ["1", "2"]);
  assert.deepEqual(
    await findings(async () => ({ total: 0, issues: [] }), "project", { pullRequest: "7" }),
    [],
  );
  await assert.rejects(
    findings(async () => ({ total: 1, issues: [] }), "project", {}),
    /incomplete/,
  );
  await assert.rejects(
    findings(async () => ({ issues: [] }), "project", {}),
    /invalid/,
  );
  await assert.rejects(
    findings(
      async () => ({ total: 1, issues: [{ ...issue("a"), type: "CODE_SMELL" }] }),
      "project",
      {},
    ),
    /filter/,
  );
});

test("HTTP errors never become a passing gate and credentials stay in headers", async () => {
  const get = apiClient("https://sonar.example/context", "secret", async (url, options) => {
    assert.equal(url.pathname, "/context/api/ce/task");
    assert.equal(url.search, "?id=abc");
    assert.equal(options.headers.Authorization, "Bearer secret");
    assert.equal(options.redirect, "error");
    return { ok: true, json: async () => ({ task: {} }) };
  });
  await get("api/ce/task", { id: "abc" });
  for (const status of [401, 403, 429, 500]) {
    const client = apiClient("https://sonarcloud.io", "secret", async () => ({
      ok: false,
      status,
    }));
    await assert.rejects(client("api/issues/search", {}), new RegExp(`HTTP ${status}`));
  }
  assert.throws(() => apiClient("http://remote.example", "secret"), /HTTPS/);
  assert.throws(() => apiClient("https://user:password@sonar.example", "secret"), /Invalid/);
  assert.throws(() => apiClient("https://sonarcloud.io", ""), /TOKEN/);
});
