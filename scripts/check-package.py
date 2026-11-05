"""Public packaged-launcher acceptance; stdlib only, no Java installation in child PATH."""
import json
import math
import os
from pathlib import Path
import platform
import shutil
import struct
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile

kind, image_arg, root_arg, artifacts_arg = sys.argv[1:]
image, root, artifacts = map(Path, (image_arg, root_arg, artifacts_arg))
artifacts.mkdir(parents=True, exist_ok=True)
workspace = Path(tempfile.mkdtemp(prefix="package with spaces ", dir=artifacts))
name = "VoxelBrew" if kind == "gui" else "VoxelBrewCLI"
if sys.platform == "win32":
    launcher = image / (name + ".exe")
    app = image / "app"
elif sys.platform == "darwin":
    launcher = image / "Contents/MacOS" / name
    app = image / "Contents/app"
else:
    launcher = image / "bin" / name
    app = image / "lib/app"
assert launcher.is_file(), launcher
assert (app / "native").is_dir()
assert (app / "third_party/occt/LICENSE_LGPL_21.txt").is_file()
env = dict(os.environ)
for key in ("JAVA_HOME", "JDK_HOME", "JAVA_OPTS", "JDK_JAVA_OPTIONS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH"):
    env.pop(key, None)
env["PATH"] = str(workspace)
env["_JAVA_OPTIONS"] = '-Djava.util.prefs.userRoot="' + str(workspace / "preferences") + '"'
if kind == "cli":
    env.pop("DISPLAY", None)
    env.pop("WAYLAND_DISPLAY", None)
if sys.platform in ("linux", "darwin") and kind == "gui":
    env["_JAVA_OPTIONS"] += " -Dprism.order=es2 -Dprism.forceGPU=true -Dprism.verbose=true"
transcript = []


def run(*args):
    command = [str(launcher), *map(str, args)]
    result = subprocess.run(command, cwd=workspace, env=env, capture_output=True, text=True, timeout=90)
    transcript.append({"command": command, "exit": result.returncode, "stdout": result.stdout, "stderr": result.stderr})
    (artifacts / "commands.json").write_text(json.dumps(transcript, indent=2), encoding="utf-8")
    assert result.returncode == 0, transcript[-1]
    return result.stdout


plugins = workspace / "trusted plugins with spaces"
plugins.mkdir()
shutil.copy2(root / "fixtures/exporter-plugin/build/libs/fixture-exporter-1.0.jar", plugins / "external.jar")
if kind == "gui":
    screenshot = workspace / "packaged-gui.png"
    run("--plugin-dir", plugins, "--smoke-test", "--smoke-output", screenshot)
    assert screenshot.is_file() and screenshot.stat().st_size > 10000
    assert screenshot.with_suffix(".txt").read_text().startswith("GUI_SMOKE_PASS")
else:
    version = next(line.split("=", 1)[1] for line in (root / "gradle.properties").read_text().splitlines()
                   if line.startswith("projectVersion="))
    assert run("--version").strip() == f"VoxelBrew {version} / jcad schema 6"
    document = workspace / "reference.jcad"
    shutil.copy2(root / "fixtures/reference-8mm.jcad", document)
    history = run("history", document)
    run("edit", document, "--feature", "thickness", "--field", "distance", "--value", 5)
    run("edit", document, "--feature", "thickness", "--field", "distance", "--value", 8)
    inspection = run("inspect", document, "--point", "10,10,4")
    assert "19099.469" in inspection and "false" in inspection, inspection
    assert "thickness" in run("history", document) and "thickness" in history
    for format_id in ("stl", "3mf"):
        run("export", document, "--format", format_id, "--output", workspace / ("plate." + format_id))
    data = (workspace / "plate.stl").read_bytes()
    count = struct.unpack_from("<I", data, 80)[0]
    assert len(data) == 84 + 50 * count
    vertices = [struct.unpack_from("<3f", data, 84 + 50 * t + 12 + 12 * v) for t in range(count) for v in range(3)]
    assert all(math.isfinite(c) for v in vertices for c in v)
    for axis, maximum in enumerate((60, 40, 8)):
        assert abs(min(v[axis] for v in vertices)) < .001
        assert abs(max(v[axis] for v in vertices) - maximum) < .001
    with zipfile.ZipFile(workspace / "plate.3mf") as archive:
        model = ET.fromstring(archive.read("3D/3dmodel.model"))
        assert model.attrib["unit"] == "millimeter"
        assert len(model.findall(".//{*}build/{*}item")) == 1
    assert "probe" in run("exporters", "--plugin-dir", plugins)
    report = workspace / "external.txt"
    run("export", document, "--format", "probe", "--plugin-dir", plugins,
        "--option", "label=packaged", "--output", report)
    assert report.read_text().startswith("packaged bodies=1 triangles=")
    assert "example.spacer/spacer" in run("feature-types", "--plugin-dir", plugins)
    spacer = workspace / "custom spacer.jcad"
    run("new", spacer)
    run("custom", spacer, "--id", "spacer", "--provider", "example.spacer", "--type", "spacer",
        "--parameter", "height=5", "--plugin-dir", plugins)
    run("edit", spacer, "--feature", "spacer", "--field", "height", "--value", 8, "--plugin-dir", plugins)
    inspection = run("inspect", spacer, "--point", "0,0,4", "--plugin-dir", plugins)
    assert "volume=2412.743" in inspection and "contains=false" in inspection, inspection
    run("export", spacer, "--format", "3mf", "--output", workspace / "spacer.3mf", "--plugin-dir", plugins)
    with zipfile.ZipFile(workspace / "spacer.3mf") as archive:
        model = ET.fromstring(archive.read("3D/3dmodel.model"))
        assert model.find(".//{*}object").attrib["name"] == "spacer.body"
(artifacts / "result.json").write_text(json.dumps({"result": "PASS", "platform": platform.platform(),
    "architecture": platform.machine(), "image": str(image), "artifacts": str(workspace),
    "java_on_path": False, "headless": kind == "cli"}, indent=2), encoding="utf-8")
print("PACKAGE_PASS", kind, platform.platform(), workspace)
