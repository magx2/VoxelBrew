"""Anonymous HTTP release fixture; real packaged Java, HTTP downloads and directory replacement."""
import atexit
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import threading
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import sys
import time
import zipfile

sys.dont_write_bytecode = True
from test_workspace import temporary_workspace
from update_status import terminal_status

kind, image_arg, root_arg, artifacts_arg, archive_arg = sys.argv[1:]
image, root, artifacts = map(Path, (image_arg, root_arg, artifacts_arg))
release_archive = Path(archive_arg)
artifacts.mkdir(parents=True, exist_ok=True)
evidence = artifacts / "update-evidence"
workspace = temporary_workspace(root / kind / "build/test-work", "update with spaces ", evidence,
                                excluded=("installation", "release", "downloads", "probe classes"))
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
for module in ("engine", "util", "import", "export", "import-api", "export-api", "slicer-api", "model-api"):
    jar_path = payload / app_relative / metadata[module + "Jar"]
    temporary = jar_path.with_suffix(".new")
    with zipfile.ZipFile(jar_path) as original, zipfile.ZipFile(temporary, "w") as changed:
        for entry in original.infolist():
            content = ("version=" + new_version + "\n").encode() if entry.filename == "voxelbrew-" + module + "-version.properties" else original.read(entry)
            if module == "util" and entry.filename == "META-INF/MANIFEST.MF":
                old_attribute = ("Implementation-Version: " + old_version).encode()
                assert old_attribute in content.splitlines(), "Missing util manifest version"
                content = content.replace(old_attribute, ("Implementation-Version: " + new_version).encode(), 1)
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
request_log = workspace / "http-requests.jsonl"
request_lock = threading.Lock()


class Releases(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        with request_lock, request_log.open("a", encoding="utf-8") as log:
            log.write(json.dumps({"path": self.path, "authorization": self.headers.get("Authorization"),
                                  "cookie": self.headers.get("Cookie")}) + "\n")
        assert self.headers.get("Authorization") is None
        assert self.headers.get("Cookie") is None
        if (workspace / "deny-access").exists():
            self.send_error(503, "fixture server unavailable")
            return
        if self.path in ("/repos/magx2/VoxelBrew/releases/latest", "/repos/magx2/VoxelBrew/releases/tags/v" + new_version):
            body = json.dumps({"tag_name": "v" + new_version, "draft": False, "prerelease": False,
                               "assets": [{"name": asset}, {"name": "SHA256SUMS"}]}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        for name in (asset, "SHA256SUMS"):
            if self.path == "/magx2/VoxelBrew/releases/download/v" + new_version + "/" + name:
                self.send_response(302)
                self.send_header("Location", "/cdn/" + name)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            if self.path == "/cdn/" + name:
                file = release_dir / name
                self.send_response(200)
                self.send_header("Content-Type", "application/octet-stream")
                self.send_header("Content-Length", str(file.stat().st_size))
                self.end_headers()
                with file.open("rb") as content:
                    shutil.copyfileobj(content, self.wfile)
                return
        self.send_error(404)


server = ThreadingHTTPServer(("127.0.0.1", 0), Releases)
threading.Thread(target=server.serve_forever, daemon=True).start()
atexit.register(server.server_close)
atexit.register(server.shutdown)
fixture_url = "http://127.0.0.1:" + str(server.server_port) + "/"

env = dict(os.environ)
for key in ("JAVA_HOME", "JDK_HOME", "JAVA_OPTS", "JDK_JAVA_OPTIONS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH", "GH_TOKEN", "GITHUB_TOKEN", "GH_ENTERPRISE_TOKEN", "GITHUB_ENTERPRISE_TOKEN"):
    env.pop(key, None)
env["_JAVA_OPTIONS"] = '-Dvoxelbrew.home="' + str(workspace / "settings") + '" -Dvoxelbrew.update.fixtureUrl=' + fixture_url
env["VOXELBREW_GH"] = str(workspace / "gh-does-not-exist")
env["GH_CONFIG_DIR"] = str(workspace / "no-gh-configuration")
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
older_document = workspace / "older writer.vxb"
assert "DOCUMENT_VERSION_PASS " + old_version in run(probe + ["write-document", old_version, older_document])
older_document_bytes = older_document.read_bytes()

if kind == "cli":
    cli = install / launcher_relative
    assert "Update available" in run([cli, "settings", "update", "check"])
    assert not list(install.parent.glob(".VoxelBrew-update-*"))
    # Check-only must never download; explicit versions go to the tag endpoint.
    run([cli, "settings", "update", "check", "--version", new_version])
    logged = [json.loads(line) for line in request_log.read_text(encoding="utf-8").splitlines()]
    assert all(request["path"].startswith("/repos/") for request in logged)
    (workspace / "deny-access").touch()
    run([cli, "settings", "update", "check"], expected=1)
    (workspace / "deny-access").unlink()
    (release_dir / "SHA256SUMS").write_text("0" * 64 + "  " + asset + "\n", encoding="utf-8")
    run([cli, "settings", "update"], expected=1)
    assert old_version in run([cli, "--version"])
    assert not list(install.parent.glob(".VoxelBrew-update-*"))
    (release_dir / "SHA256SUMS").write_text(checksum + "  " + asset + "\n", encoding="utf-8")
assert "Cancelled prepared update" in run(probe + ["cancel"])
assert not list(install.parent.glob(".VoxelBrew-update-*"))
assert "version=" + old_version in (install / app_relative / "voxelbrew-install.properties").read_text()
holder = subprocess.Popen(probe + ["hold", str(workspace / "holder-ready"), str(workspace / "release-holder")], cwd=workspace, env=env, stdout=subprocess.DEVNULL, stderr=(workspace / "holder.log").open("w"))
try:
    wait_for(lambda: (workspace / "holder-ready").exists())
    output = run([install / launcher_relative, "settings", "update"] if kind == "cli" else probe + ["update"])
    status_path = Path(next(line.removeprefix("Status: ") for line in output.splitlines() if line.startswith("Status: ")))
    assert "state=READY" in status_path.read_text()
    assert "version=" + old_version in (install / app_relative / "voxelbrew-install.properties").read_text()
    # The updater must wait for another application instance even after its parent has exited.
    time.sleep(1)
    assert "state=READY" in status_path.read_text()
    (workspace / "release-holder").touch()
    assert holder.wait(timeout=15) == 0
    wait_for(lambda: terminal_status(status_path), 60)
    assert "state=SUCCESS" in status_path.read_text(), status_path.read_text()
    backups = list(install.parent.glob("." + image.name + "-backup-*"))
    assert len(backups) == 1
    assert "version=" + old_version in (backups[0] / app_relative / "voxelbrew-install.properties").read_text()
    assert "version=" + new_version in (install / app_relative / "voxelbrew-install.properties").read_text()
    if kind == "cli":
        assert new_version in run([install / launcher_relative, "--version"])
        assert "19099.469" in run([install / launcher_relative, "designer", "inspect", root / "fixtures/reference-8mm.vxb"])
        assert "Already current" in run([install / launcher_relative, "settings", "update", "check"])
        assert "Update available" in run([install / launcher_relative, "settings", "update", "check", "--force"])
    else:
        assert new_version in run([java, "-cp", str(install / app_relative / "*"), "dev.voxelbrew.gui.Launcher", "--version"])
    assert "DOCUMENT_READ_PASS" in run(probe + ["read-document", older_document])
    assert older_document.read_bytes() == older_document_bytes, "Opening must not modify an older document"
    newer_document = workspace / "newer writer.vxb"
    assert "DOCUMENT_VERSION_PASS " + new_version in run(probe + ["write-document", new_version, newer_document])
    newer_document_bytes = newer_document.read_bytes()
    backup_java = backups[0] / runtime_relative / "bin" / ("java.exe" if os.name == "nt" else "java")
    backup_probe = [backup_java, "-cp", str(classes) + os.pathsep + str(backups[0] / app_relative / "*"), "UpdateProbe", kind.upper()]
    run(backup_probe + ["read-document", newer_document], expected=1)
    assert "newer than supported version " + old_version in commands[-1]["stderr"]
    assert new_version in commands[-1]["stderr"]
    assert newer_document.read_bytes() == newer_document_bytes
finally:
    if holder.poll() is None:
        (workspace / "release-holder").touch()
        holder.wait(timeout=15)
shutil.copy2(status_path, workspace / "status.properties")
shutil.copy2(status_path.with_name("helper.log"), workspace / "helper.log")
result = {"result": "PASS", "component": kind, "platform": platform, "oldVersion": old_version,
          "newVersion": new_version, "workspace": str(workspace), "artifacts": str(evidence), "status": str(evidence / "status.properties"),
          "sourceArchive": release_archive.name, "sourceArchiveSha256": hashlib.sha256(release_archive.read_bytes()).hexdigest(),
          "transport": "anonymous HTTP fixture with CDN redirect", "ghAvailable": False, "credentials": False, "runtime": "real packaged JVM", "otherInstanceWait": True,
          "documentVersions": [old_version, new_version], "olderDocumentReadable": True, "newerDocumentRejectedByBackup": True}
assert all(request["authorization"] is None and request["cookie"] is None
           for request in map(json.loads, request_log.read_text(encoding="utf-8").splitlines()))
(artifacts / "update-result.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
print("PACKAGE_UPDATE_PASS", kind, evidence)
