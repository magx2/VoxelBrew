"""Qualify the archive users download, relocated to a path containing spaces."""
from pathlib import Path
import os
import stat
import subprocess
import sys
import tempfile
import zipfile

kind, archive_arg, root_arg, artifacts_arg = sys.argv[1:]
archive, root, artifacts = map(Path, (archive_arg, root_arg, artifacts_arg))
artifacts.mkdir(parents=True, exist_ok=True)
extracted_images = root / ".tools/release-extracted"
extracted_images.mkdir(parents=True, exist_ok=True)
destination = Path(tempfile.mkdtemp(prefix="release archive with spaces ", dir=extracted_images)).resolve()
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
print("RELEASE_ARCHIVE_PASS", archive.name)
