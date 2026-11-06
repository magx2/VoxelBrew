"""Public packaged-launcher acceptance; stdlib only, no Java installation in child PATH."""
import json
import hashlib
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
for report in ("result.json", "commands.json"):
    (artifacts / report).unlink(missing_ok=True)
workspace = Path(tempfile.mkdtemp(prefix="package with spaces ", dir=artifacts))
name = "VoxelBrew" if kind == "gui" else "VoxelBrewCLI"
if sys.platform == "win32":
    launcher = image / (name + ".exe")
    app = image / "app"
    runtime = image / "runtime"
elif sys.platform == "darwin":
    launcher = image / "Contents/MacOS" / name
    app = image / "Contents/app"
    runtime = image / "Contents/runtime/Contents/Home"
else:
    launcher = image / "bin" / name
    app = image / "lib/app"
    runtime = image / "lib/runtime"
assert launcher.is_file(), launcher


def digest(path):
    checksum = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            checksum.update(chunk)
    return checksum.hexdigest()


def properties(path):
    return dict(line.split("=", 1) for line in path.read_text(encoding="utf-8").splitlines()
                if "=" in line and not line.lstrip().startswith("#"))


def architecture(value):
    return {"amd64": "x64", "x86_64": "x64", "aarch64": "arm64", "arm64": "arm64"}[value.lower()]


version = properties(root / "gradle.properties")["projectVersion"]
installation = properties(app / "voxelbrew-install.properties")
expected_platform = {"win32": "windows", "darwin": "macos", "linux": "linux"}[sys.platform]
expected_platform += "-" + architecture(platform.machine())
assert installation["format"] == "1" and installation["component"] == kind.upper(), installation
assert installation["version"] == version and installation["platform"] == expected_platform, installation
assert installation["engineJar"] == f"engine-{version}.jar", installation
assert installation["mainJar"] == f"{kind}-{version}.jar", installation
assert installation["utilJar"] == f"util-{version}.jar", installation
assert installation["importJar"] == f"import-{version}.jar", installation
assert installation["exportJar"] == f"export-{version}.jar", installation
for module in ("import-api", "export-api"):
    assert installation[module + "Jar"] == f"{module}-{version}.jar", installation
    assert (app / installation[module + "Jar"]).is_file()
for jar in (f"engine-{version}.jar", f"util-{version}.jar", f"import-{version}.jar", f"export-{version}.jar", f"{kind}-{version}.jar"):
    assert (app / jar).is_file(), "Missing application dependency: " + jar

assert list(app.glob("dagger-2.*.jar")), "Dagger runtime is missing"
assert not list(app.glob("dagger-compiler*.jar")), "Annotation processors must not ship at runtime"
assert not list(app.glob("dagger-spi*.jar")), "Compiler SPI must not ship at runtime"
assert not list(app.glob("lombok*.jar")), "Lombok must stay compile-only"
assert len(list(app.glob("slf4j-api-*.jar"))) == 1, "Exactly one SLF4J API must ship"
assert len(list(app.glob("slf4j-jdk14-*.jar"))) == 1, "The configured JUL logging provider must ship"
assert not list(app.glob("jul-to-slf4j*.jar")), "Do not install a recursive JUL/SLF4J bridge"
with zipfile.ZipFile(app / installation["mainJar"]) as main_jar:
    graph = "Cli" if kind == "cli" else "Gui"
    assert f"dev/voxelbrew/{kind}/Dagger{graph}Component.class" in main_jar.namelist(), "Generated dependency graph is missing"

notices = [root / "LICENSE", root / "THIRD_PARTY_NOTICES.md"]
notices += sorted(path for path in (root / "third_party").rglob("*") if path.is_file())
for source in notices:
    relative = source.relative_to(root)
    assert (app / relative).is_file(), "Missing bundled notice: " + str(relative)
    assert digest(app / relative) == digest(source), "Changed bundled notice: " + str(relative)
for notice in ("LICENSE", "ASSEMBLY_EXCEPTION", "ADDITIONAL_LICENSE_INFO"):
    assert (runtime / "legal/java.base" / notice).is_file(), "Missing Java runtime notice: " + notice

native_source = root / "engine/build/native/runtime"
native_libraries = {}
built_app = root / kind / "build/app-image" / image.name / app.relative_to(image)
for source in sorted(path for path in native_source.rglob("*") if path.is_file()):
    relative = source.relative_to(native_source)
    bundled = app / "native" / relative
    assert bundled.is_file(), "Missing native dependency: " + str(relative)
    checksum = digest(source)
    if sys.platform == "darwin":
        # jpackage signs Mach-O libraries. Verify its input and compare relocated
        # signed libraries with the original app image, not the unsigned build.
        prepared = root / kind / "build/package-input/native" / relative
        assert digest(prepared) == checksum, "Mismatched packaging input: " + str(relative)
        checksum = digest(built_app / "native" / relative)
    assert digest(bundled) == checksum, "Mismatched native dependency: " + str(relative)
    native_libraries[str(relative)] = checksum
assert native_libraries, "No built native dependencies to qualify"
env = dict(os.environ)
for key in ("JAVA_HOME", "JDK_HOME", "JAVA_OPTS", "JDK_JAVA_OPTIONS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH",
            "LD_LIBRARY_PATH", "DYLD_LIBRARY_PATH", "DYLD_FALLBACK_LIBRARY_PATH"):
    env.pop(key, None)
env["PATH"] = str(workspace)
env["_JAVA_OPTIONS"] = '-Djava.util.prefs.userRoot="' + str(workspace / "preferences") + '" -Dvoxelbrew.home="' + str(workspace / "settings") + '"'
if kind == "cli":
    env.pop("DISPLAY", None)
    env.pop("WAYLAND_DISPLAY", None)
if sys.platform in ("linux", "darwin") and kind == "gui":
    env["_JAVA_OPTIONS"] += " -Dprism.order=es2 -Dprism.forceGPU=true -Dprism.verbose=true"
transcript = []


def execute(command):
    result = subprocess.run(command, cwd=workspace, env=env, capture_output=True, text=True, timeout=90)
    transcript.append({"command": command, "exit": result.returncode, "stdout": result.stdout, "stderr": result.stderr})
    (artifacts / "commands.json").write_text(json.dumps(transcript, indent=2), encoding="utf-8")
    assert "SLF4J:" not in result.stderr, "Logging provider configuration failed: " + result.stderr
    assert result.returncode == 0, transcript[-1]
    return result.stdout, result.stderr


def run(*args):
    return execute([str(launcher), *map(str, args)])[0]


java = runtime / "bin" / ("java.exe" if sys.platform == "win32" else "java")
assert java.is_file(), "Missing bundled Java launcher: " + str(java)
stdout, stderr = execute([str(java), "-XshowSettings:properties", "-version"])
(workspace / "runtime.txt").write_text(stdout + stderr, encoding="utf-8")
settings = dict(tuple(part.strip() for part in line.split(" = ", 1))
                for line in stderr.splitlines() if " = " in line)
assert settings["java.specification.version"] == "21", settings
assert Path(settings["java.home"]).resolve() == runtime.resolve(), settings
assert architecture(settings["os.arch"]) == architecture(platform.machine()), settings


plugins = workspace / "trusted plugins with spaces"
plugins.mkdir()
shutil.copy2(root / "fixtures/exporter-plugin/build/libs/fixture-exporter-1.0.jar", plugins / "external.jar")
if kind == "gui":
    screenshot = workspace / "packaged-gui.png"
    run("--plugin-dir", plugins, "--smoke-test", "--smoke-output", screenshot)
    assert screenshot.is_file() and screenshot.stat().st_size > 10000
    assert screenshot.with_suffix(".txt").read_text().startswith("GUI_SMOKE_PASS")
else:
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
    "installation": installation, "native_libraries": native_libraries, "notices_verified": len(notices),
    "runtime": {key: settings[key] for key in ("java.version", "java.vendor", "java.home", "os.arch")},
    "java_on_path": False, "headless": kind == "cli"}, indent=2), encoding="utf-8")
print("PACKAGE_PASS", kind, platform.platform(), workspace)
