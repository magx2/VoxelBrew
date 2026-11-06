const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { execFileSync } = require("node:child_process");
const {
  Release,
  version,
  replaceVersion,
  releaseVersion,
  assetNames,
  manifest,
  digest,
  cleanupTemporaryDirectory,
} = require("./release.cjs");

test("cleanup logs deletion errors without changing the test outcome", () => {
  const failure = new Error("locked fixture file");
  const messages = [];
  assert.doesNotThrow(() =>
    cleanupTemporaryDirectory(
      "owned test directory",
      () => {
        throw failure;
      },
      (...message) => messages.push(message),
    ),
  );
  assert.match(messages[0][0], /ERROR:.*owned test directory/);
  assert.equal(messages[0][1], failure);
});

function fixture(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "VoxelBrew release tests with spaces "));
  t.after(() => cleanupTemporaryDirectory(root));
  const remote = path.join(root, "remote.git"),
    cwd = path.join(root, "source");
  execFileSync("git", ["init", "--bare", "--initial-branch=master", remote], { stdio: "pipe" });
  fs.mkdirSync(cwd);
  const r = new Release(cwd, "example/VoxelBrew");
  r.git("init", "--initial-branch=master");
  r.configure();
  r.git("config", "core.autocrlf", "false");
  fs.writeFileSync(path.join(cwd, ".gitattributes"), "* text=auto eol=lf\n");
  fs.writeFileSync(
    path.join(cwd, "gradle.properties"),
    "setting=true\nprojectVersion=0.1.0-SNAPSHOT\n",
  );
  fs.writeFileSync(path.join(cwd, "model.txt"), "initial model\n");
  r.git("add", ".");
  r.git("commit", "-m", "Initial model");
  r.git("remote", "add", "origin", remote);
  r.git("push", "origin", "master");
  const base = r.git("rev-parse", "HEAD");
  r.releases = () => [];
  return {
    root,
    remote,
    r,
    base,
    plan: path.join(root, "candidate"),
    assets: path.join(root, "assets"),
  };
}
function clone(f, name) {
  const cwd = path.join(f.root, name);
  execFileSync("git", ["clone", f.remote, cwd], { stdio: "pipe" });
  const r = new Release(cwd, f.r.repo);
  r.configure();
  r.releases = () => [];
  return r;
}
function assets(directory, m) {
  fs.mkdirSync(directory, { recursive: true });
  for (const name of assetNames(m.version))
    fs.writeFileSync(path.join(directory, name), `fixture ${name}`);
  for (const platform of ["windows-x64", "linux-x64", "macos-x64"]) {
    const archives = Object.fromEntries(
      assetNames(m.version)
        .filter((name) => name.endsWith(`-${platform}.zip`))
        .map((name) => [name, digest(path.join(directory, name))]),
    );
    fs.writeFileSync(
      path.join(directory, `platform-${platform}.json`),
      JSON.stringify({ platform, release: m.release, archives }),
    );
  }
}
test("strict version parsing, native limits and branch family", () => {
  assert.equal(version("projectVersion=2.3.4-SNAPSHOT\n").number, "2.3.4");
  assert.equal(
    replaceVersion("a=true\nprojectVersion=0.1.0-SNAPSHOT\n", "0.2.0-SNAPSHOT"),
    "a=true\nprojectVersion=0.2.0-SNAPSHOT\n",
  );
  for (const text of [
    "",
    "projectVersion=01.2.3",
    "projectVersion=1.2.3-rc1",
    "projectVersion=65535.0.0",
    "projectVersion=1.2.3\nprojectVersion=1.2.4",
    "projectVersion=1.2.3\nprojectVersion: 2.3.4",
  ])
    assert.throws(() => version(text));
  assert.throws(() => releaseVersion("master", "projectVersion=1.2.3-SNAPSHOT"));
  assert.throws(() => releaseVersion("release/1.3.x", "projectVersion=1.2.3-SNAPSHOT"));
  assert.throws(() => releaseVersion("release/1.2.x", "projectVersion=1.2.3"));
  assert.equal(releaseVersion("release/1.2.x", "projectVersion=1.2.3-SNAPSHOT").patch, 3);
});
test("prepare atomically branches the old version and advances master minor", (t) => {
  const f = fixture(t),
    m = f.r.prepare("refs/heads/master", f.base);
  assert.equal(m.branch, "release/0.1.x");
  assert.equal(f.r.remote("refs/heads/release/0.1.x"), f.base);
  assert.equal(version(f.r.git("show", `${m.next}:gradle.properties`)).value, "0.2.0-SNAPSHOT");
  assert.throws(() => f.r.prepare("refs/heads/master", f.base), /advanced/);
  assert.throws(() => f.r.prepare("refs/heads/release/0.1.x", m.next), /master/);
});
test("prepare rejects an existing branch without advancing master", (t) => {
  const f = fixture(t);
  f.r.git("push", "origin", `${f.base}:refs/heads/release/0.1.x`);
  assert.throws(() => f.r.prepare("refs/heads/master", f.base), /already exists/);
  assert.equal(f.r.remote("refs/heads/master"), f.base);
});
test("candidate bundles the exact release tree without changing remote refs", (t) => {
  const f = fixture(t);
  f.r.prepare("refs/heads/master", f.base);
  const m = f.r.candidate("release/0.1.x", f.base, f.plan);
  assert.equal(m.version, "0.1.0");
  assert.equal(f.r.remote("refs/heads/release/0.1.x"), f.base);
  assert.equal(f.r.remote("refs/tags/v0.1.0"), "");
  const worker = clone(f, "worker");
  worker.load(f.plan, f.base, "release/0.1.x");
  assert.equal(worker.git("rev-parse", "HEAD"), m.release);
  assert.equal(version(worker.read()).value, "0.1.0");
  assert.throws(() => worker.load(f.plan, "0".repeat(40), "release/0.1.x"), /does not belong/);
  assert.throws(() => worker.load(f.plan, f.base, "master"), /does not belong/);
});
test("master rehearsal is read-only and cannot be published", (t) => {
  const f = fixture(t);
  const m = f.r.candidate("master", f.base, f.plan, true);
  assert.equal(f.r.remote("refs/heads/master"), f.base);
  assert.equal(f.r.remote("refs/heads/release/0.1.x"), "");
  assert.equal(f.r.remote("refs/tags/v0.1.0"), "");
  const worker = clone(f, "worker");
  worker.load(f.plan, f.base, "master");
  assert.throws(() => worker.publish(m, f.assets), /rehearsal/);
});
test("published candidate uses tested commit, patch bump and version-preserving merge-back", (t) => {
  const f = fixture(t);
  const preparation = f.r.prepare("refs/heads/master", f.base);
  f.r.git("checkout", "--detach", f.base);
  fs.writeFileSync(path.join(f.r.cwd, "bugfix.txt"), "fixed\n");
  f.r.git("add", "bugfix.txt");
  f.r.git("commit", "-m", "Release bug fix");
  const source = f.r.git("rev-parse", "HEAD");
  f.r.git("push", "origin", `${source}:refs/heads/release/0.1.x`);
  const m = f.r.candidate("release/0.1.x", source, f.plan);
  const publisher = clone(f, "publisher");
  publisher.load(f.plan, source, "release/0.1.x");
  assets(f.assets, m);
  const calls = [],
    run = publisher.run.bind(publisher);
  publisher.run = (command, args, options) => {
    if (command !== "gh") return run(command, args, options);
    calls.push(args);
    if (args[1] === "upload") assert.equal(publisher.remote(`refs/tags/${m.tag}`), "");
    if (args[1] === "edit") assert.equal(publisher.remote(`refs/tags/${m.tag}`), m.tagObject);
    return "";
  };
  publisher.publish(m, f.assets);
  assert.deepEqual(
    calls.map((args) => args[1]),
    ["create", "upload", "edit"],
  );
  assert.equal(publisher.remote("refs/heads/release/0.1.x"), m.next);
  assert.equal(publisher.remote("refs/heads/master"), preparation.next);
  assert.equal(publisher.git("rev-parse", "v0.1.0^{commit}"), m.release);
  assert.throws(() => publisher.publish(m, f.assets), /advanced/);
  assert.equal(publisher.mergeBack("release/0.1.x", m.next), true);
  assert.equal(version(publisher.read()).value, "0.2.0-SNAPSHOT");
  assert.equal(fs.readFileSync(path.join(publisher.cwd, "bugfix.txt"), "utf8"), "fixed\n");
  assert.equal(publisher.mergeBack("release/0.1.x", m.next), false);
});
test("failed asset upload leaves tag and release branch unchanged", (t) => {
  const f = fixture(t);
  f.r.prepare("refs/heads/master", f.base);
  const m = f.r.candidate("release/0.1.x", f.base, f.plan);
  const publisher = clone(f, "publisher");
  publisher.load(f.plan, f.base, m.branch);
  assets(f.assets, m);
  const run = publisher.run.bind(publisher);
  publisher.run = (command, args, options) => {
    if (command !== "gh") return run(command, args, options);
    if (args[1] === "upload") throw new Error("upload failed");
    return "";
  };
  assert.throws(() => publisher.publish(m, f.assets), /upload failed/);
  assert.equal(publisher.remote(`refs/tags/${m.tag}`), "");
  assert.equal(publisher.remote(`refs/heads/${m.branch}`), f.base);
});
test("concurrent branch changes reject the entire ref transaction", (t) => {
  const f = fixture(t);
  const originalRemote = f.r.remote.bind(f.r);
  f.r.remote = (ref) => (ref === "refs/heads/master" ? "1".repeat(40) : originalRemote(ref));
  assert.throws(
    () =>
      f.r.push([
        { ref: "refs/heads/release/0.1.x", commit: f.base, expected: "" },
        { ref: "refs/heads/master", commit: f.base, expected: f.base },
      ]),
    /Concurrent/,
  );
  assert.equal(originalRemote("refs/heads/release/0.1.x"), "");
});
test("server rejection cannot partially publish branch and tag", (t) => {
  const f = fixture(t);
  f.r.git("tag", "-a", "v0.1.0", "-m", "candidate");
  const hook = path.join(f.remote, "hooks/update");
  fs.writeFileSync(hook, '#!/bin/sh\ncase "$1" in refs/tags/*) exit 1;; esac\n');
  fs.chmodSync(hook, 0o755);
  assert.throws(() =>
    f.r.push([
      { ref: "refs/heads/release/0.1.x", commit: f.base, expected: "" },
      { ref: "refs/tags/v0.1.0", commit: f.r.git("rev-parse", "v0.1.0"), expected: "" },
    ]),
  );
  assert.equal(f.r.remote("refs/heads/release/0.1.x"), "");
  assert.equal(f.r.remote("refs/tags/v0.1.0"), "");
});
test("conflicting code is never auto-merged into master", (t) => {
  const f = fixture(t),
    p = f.r.prepare("refs/heads/master", f.base);
  const change = (ref, contents, branch) => {
    f.r.git("checkout", "--detach", ref);
    fs.writeFileSync(path.join(f.r.cwd, "model.txt"), contents);
    f.r.git("add", "model.txt");
    f.r.git("commit", "-m", "Change model");
    const sha = f.r.git("rev-parse", "HEAD");
    f.r.git("push", "origin", `${sha}:refs/heads/${branch}`);
    return sha;
  };
  const master = change(p.next, "master model\n", "master");
  const source = change(f.base, "release model\n", p.branch);
  assert.throws(() => f.r.mergeBack(p.branch, source), /Merge conflicts/);
  assert.equal(f.r.remote("refs/heads/master"), master);
  assert.equal(f.r.git("status", "--porcelain"), "");
});
test("candidate metadata tampering is rejected", (t) => {
  const f = fixture(t);
  const m = f.r.candidate("master", f.base, f.plan, true);
  fs.writeFileSync(path.join(f.plan, "release.json"), JSON.stringify({ ...m, release: m.next }));
  assert.throws(() => clone(f, "worker").load(f.plan, f.base, "master"), /refs differ/);
});
test("manifest requires all five qualified archives with matching revision and hashes", (t) => {
  const f = fixture(t),
    m = { version: "1.2.3", release: "a".repeat(40) };
  assets(f.assets, m);
  assert.equal(manifest(f.assets, m).length, 10);
  assert.equal(
    fs.readFileSync(path.join(f.assets, "SHA256SUMS"), "utf8").trim().split("\n").length,
    9,
  );
  const file = path.join(f.assets, assetNames(m.version)[0]);
  fs.appendFileSync(file, "changed");
  assert.throws(() => manifest(f.assets, m), /checksum mismatch/);
  fs.unlinkSync(file);
  assert.throws(() => manifest(f.assets, m), /Missing or unexpected/);
});
