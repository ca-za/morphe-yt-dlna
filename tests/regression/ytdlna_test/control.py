"""Commands to the patched app through its test hooks (TestHooks.java)."""

import json
import time
import uuid

from .device import Device, LogStream

ACTION = "app.ytdlna.TEST"


class Control:
    def __init__(self, device: Device, logs: LogStream, package: str):
        self.device = device
        self.logs = logs
        self.package = package

    def cmd(self, cmd: str, timeout: float = 10, **extras) -> dict:
        command_id = uuid.uuid4().hex[:8]
        since = self.logs.mark()
        args = " ".join(f"--es {k} '{v}'" for k, v in extras.items())
        self.device.sh(f"am broadcast -a {ACTION} -p {self.package} --es cmd {cmd} --es id {command_id} {args}")
        match = self.logs.wait(rf"RESULT {command_id} (\{{.*\}})$", timeout, since)
        if not match:
            raise TimeoutError(f"No answer to test command '{cmd}'")
        return json.loads(match.group(1))

    def status(self) -> dict:
        return self.cmd("status")

    def local(self, action: str, **extras) -> None:
        self.cmd("local", action=action, **extras)

    def local_state(self) -> int:
        """PlaybackState of the app's player: 2 paused, 3 playing, 6 buffering."""
        return self.status().get("local", {}).get("state", -1)

    def wait_status(self, predicate, timeout: float, interval: float = 0.7) -> dict:
        deadline = time.monotonic() + timeout
        status = {}
        while time.monotonic() < deadline:
            status = self.status()
            if predicate(status):
                return status
            time.sleep(interval)
        return status
