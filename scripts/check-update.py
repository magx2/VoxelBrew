"""Offline release fixture; real packaged Java, gh process adapter and directory replacement."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import sys
import tempfile
import time
import zipfile

kind, image_arg, root_arg, artifacts_arg, archive_arg = sys.argv[1:]
image, root, artifacts = map(Path, (image_arg, root_arg, artifacts_arg))
release_archive = Path(archive_arg)
artifacts.mkdir(parents=True, exist_ok=True)
workspace = Path(tempfile.mkdtemp(prefix="update with spaces ", dir=artifacts)).resolve()
platform = "windows-x64" if os.name == "nt" else "macos-x64" if sys.platform == "darwin" else "linux-x64"
app_relative = Path("app" if os.name == "nt" else "Contents/app" if sys.platform == "darwin" else "lib/app")
runtime_relative = Path("runtime" if os.name == "nt" else "Contents/runtime/Contents/Home" if sys.platform == "darwin" else "lib/runtime")
launcher_relative = Path(image.stem + ".exe" if os.name == "nt" else "Contents/MacOS/" + image.stem if sys.platform == "darwin" else "bin/" + image.name)
install = workspace / "installation" / image.name
payload = workspace / "release" / image.name
shutil.copytree(image, install)
payload.parent.mkdir()
# Use the exact Gradle-produced archive as the release fixture's source. This catches packaging
# errors (such as sibling runtime lock files) that copying an app directory alone cannot expose.
with zipfile.ZipFile(release_archive) as packaged:
    assert packaged.testzip() is None
    for entry in packaged.infolist():
        assert entry.filename.startswith(image.name + "/"), "Unexpected top-level archive entry: " + entry.filename
        target = (payload.parent / entry.filename).resolve()
        assert target.is_relative_to(payload.parent)
        assert not stat.S_ISLNK(entry.external_attr >> 16), "Linked release entry: " + entry.filename
        packaged.extract(entry, payload.parent)
        if os.name != "nt" and not entry.is_dir():
            target.chmod((entry.external_attr >> 16) & 0o777)
old_version = next(line.split("=", 1)[1] for line in (install / app_relative / "voxelbrew-install.properties").read_text().splitlines() if line.startswith("version="))
numeric = list(map(int, old_version.removesuffix("-SNAPSHOT").split(".")))
# Keep the fixture newer than the packaged version, including future release branches.
for index in (2, 1, 0):
    if numeric[index] < 65534:
        numeric[index] += 1
        numeric[index + 1:] = [0] * (2 - index)
        break
else:
    raise AssertionError("No newer release version available for fixture")
new_version = ".".join(map(str, numeric))
marker = payload / app_relative / "voxelbrew-install.properties"
marker.write_text(marker.read_text().replace("version=" + old_version, "version=" + new_version), encoding="utf-8")
metadata = dict(line.split("=", 1) for line in marker.read_text().splitlines() if "=" in line)
for module in ("engine", "util"):
    jar_path = payload / app_relative / metadata[module + "Jar"]
    temporary = jar_path.with_suffix(".new")
    with zipfile.ZipFile(jar_path) as original, zipfile.ZipFile(temporary, "w") as changed:
        for entry in original.infolist():
            content = ("version=" + new_version + "\n").encode() if entry.filename == "voxelbrew-" + module + "-version.properties" else original.read(entry)
            changed.writestr(entry, content)
    temporary.replace(jar_path)
release_dir = workspace / "downloads"
release_dir.mkdir()
asset = image.stem + "-" + new_version + "-" + platform + ".zip"
with zipfile.ZipFile(release_dir / asset, "w", zipfile.ZIP_DEFLATED, compresslevel=1) as archive:
    for file in payload.rglob("*"):
        if file.is_file():
            archive.write(file, file.relative_to(payload.parent).as_posix())
checksum = hashlib.sha256((release_dir / asset).read_bytes()).hexdigest()
(release_dir / "SHA256SUMS").write_text(checksum + "  " + asset + "\n", encoding="utf-8")
fixture = workspace / "gh fixture.py"
fixture.write_text('''import json, os, shutil, sys
from pathlib import Path
args = sys.argv[1:]
root = Path(__file__).parent
with (root / "gh-commands.jsonl").open("a") as log:
    log.write(json.dumps(args) + "\\n")
config = json.loads((root / "release.json").read_text())
if (root / "deny-access").exists():
    sys.stderr.write("fixture authentication denied")
    sys.exit(1)
if args[0] == "api":
    assert args[1:3] == ["--hostname", "github.com"]
    assert args[3] in ["repos/magx2/VoxelBrew/releases/latest", "repos/magx2/VoxelBrew/releases/tags/v" + config["version"]]
    print("v" + config["version"] + "\\nfalse\\nfalse\\n" + config["asset"] + "\\nSHA256SUMS")
else:
    assert args[:3] == ["release", "download", "v" + config["version"]]
    assert args[args.index("--repo") + 1] == "github.com/magx2/VoxelBrew"
    shutil.copyfile(root / "downloads" / args[args.index("--pattern") + 1], args[args.index("--output") + 1])
''', encoding="utf-8")
(workspace / "release.json").write_text(json.dumps({"version": new_version, "asset": asset}), encoding="utf-8")
gh = workspace / ("gh fixture.cmd" if os.name == "nt" else "gh fixture")
if os.name == "nt":
    gh.write_text('@echo off\n"' + sys.executable + '" "' + str(fixture) + '" %*\n', encoding="utf-8")
else:
    import shlex
    gh.write_text("#!/bin/sh\nexec " + shlex.quote(sys.executable) + " " + shlex.quote(str(fixture)) + ' "$@"\n', encoding="utf-8")
    gh.chmod(0o755)
env = dict(os.environ)
for key in ("JAVA_HOME", "JDK_HOME", "JAVA_OPTS", "JDK_JAVA_OPTIONS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH"):
    env.pop(key, None)
env["_JAVA_OPTIONS"] = '-Dvoxelbrew.home="' + str(workspace / "settings") + '"'
env["VOXELBREW_GH"] = str(gh)
env["PATH"] = str(workspace)
classes = workspace / "probe classes"
classes.mkdir()
javac = Path(os.environ["VOXELBREW_TEST_JDK"]) / "bin" / ("javac.exe" if os.name == "nt" else "javac")
subprocess.run([str(javac), "--release", "21", "-encoding", "UTF-8", "-cp", str(install / app_relative / "*"), "-d", str(classes), str(root / "fixtures/updater/UpdateProbe.java")], check=True)
java = install / runtime_relative / "bin" / ("java.exe" if os.name == "nt" else "java")
probe = [str(java), "-cp", str(classes) + os.pathsep + str(install / app_relative / "*"), "UpdateProbe", kind.upper()]
commands = []


def run(command, expected=0):
    result = subprocess.run(list(map(str, command)), env=env, cwd=workspace, capture_output=True, text=True, timeout=120)
    commands.append({"command": list(map(str, command)), "exit": result.returncode, "stdout": result.stdout, "stderr": result.stderr})
    (workspace / "commands.json").write_text(json.dumps(commands, indent=2), encoding="utf-8")
    assert result.returncode == expected, commands[-1]
    return result.stdout


def wait_for(predicate, timeout=40):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(.1)
    raise AssertionError("Timed out waiting for update; evidence: " + str(workspace))


assert "PACKAGE_UTIL_PASS" in run(probe + ["utilities"])

if kind == "cli":
    cli = install / launcher_relative
    assert "Update available" in run([cli, "update", "check"])
    assert not list(install.parent.glob(".VoxelBrew-update-*"))
    # Check-only must never download; explicit versions go to the tag endpoint.
    run([cli, "update", "check", "--version", new_version])
    logged = [json.loads(line) for line in (workspace / "gh-commands.jsonl").read_text().splitlines()]
    assert all(args[0] == "api" for args in logged)
    (workspace / "deny-access").touch()
    run([cli, "update", "check"], expected=1)
    (workspace / "deny-access").unlink()
    (release_dir / "SHA256SUMS").write_text("0" * 64 + "  " + asset + "\n", encoding="utf-8")
    run([cli, "update"], expected=1)
    assert old_version in run([cli, "--version"])
    assert not list(install.parent.glob(".VoxelBrew-update-*"))
    (release_dir / "SHA256SUMS").write_text(checksum + "  " + asset + "\n", encoding="utf-8")
assert "Cancelled prepared update" in run(probe + ["cancel"])
assert not list(install.parent.glob(".VoxelBrew-update-*"))
assert "version=" + old_version in (install / app_relative / "voxelbrew-install.properties").read_text()
holder = subprocess.Popen(probe + ["hold", str(workspace / "holder-ready"), str(workspace / "release-holder")], cwd=workspace, env=env, stdout=subprocess.DEVNULL, stderr=(workspace / "holder.log").open("w"))
try:
    wait_for(lambda: (workspace / "holder-ready").exists())
    output = run([install / launcher_relative, "update"] if kind == "cli" else probe + ["update"])
    status_path = Path(next(line.removeprefix("Status: ") for line in output.splitlines() if line.startswith("Status: ")))
    assert "state=READY" in status_path.read_text()
    assert "version=" + old_version in (install / app_relative / "voxelbrew-install.properties").read_text()
    # The updater must wait for another application instance even after its parent has exited.
    time.sleep(1)
    assert "state=READY" in status_path.read_text()
    (workspace / "release-holder").touch()
    assert holder.wait(timeout=15) == 0
    wait_for(lambda: "state=SUCCESS" in status_path.read_text() or "state=FAILED" in status_path.read_text(), 60)
    assert "state=SUCCESS" in status_path.read_text(), status_path.read_text()
    backups = list(install.parent.glob("." + image.name + "-backup-*"))
    assert len(backups) == 1
    assert "version=" + old_version in (backups[0] / app_relative / "voxelbrew-install.properties").read_text()
    assert "version=" + new_version in (install / app_relative / "voxelbrew-install.properties").read_text()
    if kind == "cli":
        assert new_version in run([install / launcher_relative, "--version"])
        assert "19099.469" in run([install / launcher_relative, "inspect", root / "fixtures/reference-8mm.jcad"])
        assert "Already current" in run([install / launcher_relative, "update", "check"])
        assert "Update available" in run([install / launcher_relative, "update", "check", "--force"])
    else:
        assert new_version in run([java, "-cp", str(install / app_relative / "*"), "dev.voxelbrew.gui.Launcher", "--version"])
finally:
    if holder.poll() is None:
        (workspace / "release-holder").touch()
        holder.wait(timeout=15)
shutil.copy2(status_path, workspace / "status.properties")
shutil.copy2(status_path.with_name("helper.log"), workspace / "helper.log")
result = {"result": "PASS", "component": kind, "platform": platform, "oldVersion": old_version,
          "newVersion": new_version, "workspace": str(workspace), "status": str(workspace / "status.properties"),
          "sourceArchive": release_archive.name, "sourceArchiveSha256": hashlib.sha256(release_archive.read_bytes()).hexdigest(),
          "transport": "offline gh fixture", "runtime": "real packaged JVM", "otherInstanceWait": True}
(artifacts / "update-result.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
# Retain transcripts, status and logs as acceptance evidence, without uploading several JVM copies.
def remove_readonly(function, path, error):
    generated_file = Path(path).resolve()
    assert generated_file.is_relative_to(workspace) and generated_file != workspace
    if not isinstance(error[1], PermissionError):
        raise error[1]
    generated_file.chmod(generated_file.stat().st_mode | 0o200)
    function(path)


for name in ("installation", "release", "downloads", "probe classes"):
    generated = (workspace / name).resolve()
    assert generated.is_relative_to(workspace) and generated != workspace
    shutil.rmtree(generated, onerror=remove_readonly)
print("PACKAGE_UPDATE_PASS", kind, workspace)
