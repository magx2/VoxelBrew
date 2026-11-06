"""Qualify the archive users download, relocated to a path containing spaces."""
from pathlib import Path
import hashlib
import json
import os
import stat
import subprocess
import sys
import zipfile

sys.dont_write_bytecode = True
from test_workspace import temporary_workspace

kind, archive_arg, root_arg, artifacts_arg = sys.argv[1:]
archive, root, artifacts = map(Path, (archive_arg, root_arg, artifacts_arg))
artifacts.mkdir(parents=True, exist_ok=True)
(artifacts / "archive-result.json").unlink(missing_ok=True)
checksum = hashlib.sha256()
with archive.open("rb") as source:
    for chunk in iter(lambda: source.read(1024 * 1024), b""):
        checksum.update(chunk)
extracted_images = root / ".tools/release-extracted"
extracted_images.mkdir(parents=True, exist_ok=True)
destination = temporary_workspace(extracted_images, "release archive with spaces ")
name = "VoxelBrew" if kind == "gui" else "VoxelBrewCLI"
image_name = name + (".app" if sys.platform == "darwin" else "")
launcher = (image_name + "/Contents/MacOS/" + name if sys.platform == "darwin" else
            name + "/bin/" + name if sys.platform != "win32" else name + "/" + name + ".exe")
with zipfile.ZipFile(archive) as contents:
    assert contents.testzip() is None, "Damaged ZIP"
    if sys.platform != "win32":
        assert contents.getinfo(launcher).external_attr >> 16 & stat.S_IXUSR, "Missing launcher execute permission"
    for member in contents.infolist():
        assert member.filename.startswith(image_name + "/"), "Unexpected top-level archive entry: " + member.filename
        target = (destination / member.filename).resolve()
        assert target.is_relative_to(destination), "Unsafe ZIP entry"
        contents.extract(member, destination)
        if os.name != "nt" and not member.is_dir():
            target.chmod((member.external_attr >> 16) & 0o777)
subprocess.run([sys.executable, str(root / "scripts/check-package.py"), kind,
                str(destination / image_name), str(root), str(artifacts)], check=True)
(artifacts / "archive-result.json").write_text(json.dumps({
    "result": "PASS", "archive": str(archive.resolve()), "sha256": checksum.hexdigest(),
    "size_bytes": archive.stat().st_size, "image": str(destination / image_name),
    "package_result": str((artifacts / "result.json").resolve()),
    "unix_launcher_permissions_verified": sys.platform != "win32",
}, indent=2), encoding="utf-8")
print("RELEASE_ARCHIVE_PASS", archive.name)
