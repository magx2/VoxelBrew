"""Owned test workspaces: keep small evidence, remove scratch data, never mask test results."""
import atexit
import logging
import os
from pathlib import Path
import shutil
import stat
import sys
import tempfile

LOG = logging.getLogger("voxelbrew.tests.cleanup")
EVIDENCE_SUFFIXES = {".json", ".jsonl", ".log", ".txt", ".properties", ".png", ".vxb", ".jcad", ".stl", ".3mf"}


def cleanup(path):
    """Remove only the supplied test-owned path, without following directory links."""
    path = Path(path).absolute()

    def retry_readonly(function, filename, error):
        try:
            if not isinstance(error[1], PermissionError):
                raise error[1]
            # Never chmod a link's target. rmtree itself does not traverse links/junctions.
            if not Path(filename).is_symlink():
                os.chmod(filename, os.stat(filename, follow_symlinks=False).st_mode | stat.S_IWUSR)
            function(filename)
        except OSError:
            LOG.exception("Could not delete test temporary path: %s", filename)

    try:
        if path.is_symlink():
            path.unlink()
        elif path.exists():
            shutil.rmtree(path, onerror=retry_readonly)
    except OSError:
        LOG.exception("Could not clean test temporary directory: %s", path)


def finish_workspace(workspace, evidence=None, excluded=()):
    try:
        if evidence is not None:
            # Exclude large copied runtimes/downloads; preserve diagnostics even on failed runs.
            for parent, directories, files in os.walk(workspace, followlinks=False):
                relative = Path(parent).relative_to(workspace)
                directories[:] = [name for name in directories if name not in excluded
                                   and (Path(parent) / name).resolve() == (Path(parent) / name).absolute()]
                for name in files:
                    source = Path(parent) / name
                    if source.suffix in EVIDENCE_SUFFIXES and not source.is_symlink():
                        destination = evidence / relative / name
                        destination.parent.mkdir(parents=True, exist_ok=True)
                        shutil.copy2(source, destination)
    except OSError:
        LOG.exception("Could not preserve test evidence from %s", workspace)
    finally:
        cleanup(workspace)


def temporary_workspace(parent, prefix, evidence=None, excluded=()):
    parent = Path(parent)
    parent.mkdir(parents=True, exist_ok=True)
    workspace = Path(tempfile.mkdtemp(prefix=prefix, dir=parent)).resolve()
    # Registered immediately, so setup failures also clean up. Later registrations close servers first.
    atexit.register(finish_workspace, workspace, evidence, excluded)
    return workspace


if __name__ == "__main__":
    for argument in sys.argv[1:]:
        cleanup(argument)
