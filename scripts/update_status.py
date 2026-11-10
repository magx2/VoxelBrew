"""Read a terminal updater status while tolerating transient publication locks."""


def terminal_status(path):
    try:
        status = path.read_text()
    except (FileNotFoundError, PermissionError):
        # Windows may deny reads while the helper publishes the next status.
        return ""
    return status if "state=SUCCESS" in status or "state=FAILED" in status else ""
