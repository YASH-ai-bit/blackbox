#!/usr/bin/env python3
"""Install only the pinned iw for ADB diagnostics; run only read-only Wi-Fi queries."""
import argparse
import datetime
import json
from pathlib import Path
import runpy
import shutil
import subprocess
import time

PROJECT = Path(__file__).resolve().parent.parent
PREPARATION = runpy.run_path(str(PROJECT / "scripts/prepare-network-tools.py"))
SANITIZE = runpy.run_path(str(PROJECT / "scripts/probe-device.py"))["sanitize"]
IW_SHA256 = "3314eceb85558bd5c81556b225bd4bb9a77d494d83bbc3c27a3f5eb512a937c3"
REMOTE_DIRECTORY = f"/data/local/tmp/blackbox-diagnostics-{IW_SHA256[:12]}"
REMOTE_BINARY = REMOTE_DIRECTORY + "/iw"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default=shutil.which("adb") or str(PROJECT / ".tools/android-sdk/platform-tools/adb.exe"))
    parser.add_argument("--serial")
    parser.add_argument("--output", type=Path, default=PROJECT / "device-reports/isolated-iw.json")
    args = parser.parse_args()
    base = [args.adb] + (["-s", args.serial] if args.serial else [])
    local_binary = PREPARATION["DESTINATION"] / "aarch64/iw"
    data = local_binary.read_bytes()
    if PREPARATION["sha256"](data) != IW_SHA256:
        raise SystemExit("Local iw checksum differs from the reviewed release payload; nothing installed.")
    PREPARATION["inspect_static_arm64"](data)
    journal = []

    def adb(arguments, require_success=True, timeout=20):
        started = time.monotonic()
        try:
            result = subprocess.run(base + arguments, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout)
            record = {"arguments": arguments, "stdout": SANITIZE(result.stdout), "stderr": SANITIZE(result.stderr),
                      "exit_code": result.returncode, "duration_ms": round((time.monotonic() - started) * 1000), "timed_out": False}
        except subprocess.TimeoutExpired:
            record = {"arguments": arguments, "stdout": "", "stderr": "Timed out", "exit_code": -1,
                      "duration_ms": round((time.monotonic() - started) * 1000), "timed_out": True}
        journal.append(record)
        if require_success and record["exit_code"] != 0:
            raise RuntimeError(f"ADB step failed: {arguments}: {record['stderr']}")
        return record

    def shell(command, require_success=True):
        return adb(["shell", command], require_success)

    def snapshot():
        return {command: shell(command, False) for command in ["ip rule show", "cat /proc/sys/net/ipv4/ip_forward"]}

    report = {"captured_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
              "remote_directory": REMOTE_DIRECTORY, "remote_binary": REMOTE_BINARY, "iw_sha256": IW_SHA256,
              "scope": "ADB-shell diagnostics only. No su, APK installation, AP/DHCP daemon, interface or network-setting changes.",
              "records": journal, "complete": False}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    try:
        if adb(["get-state"])["stdout"].strip() != "device":
            raise RuntimeError("Unlock/authorize the phone and check ADB connectivity")
        if shell("getprop ro.product.cpu.abi")["stdout"].strip() != "arm64-v8a":
            raise RuntimeError("This payload is for arm64-v8a only")
        uid = shell("id -u")["stdout"].strip()
        if not uid.isdecimal():
            raise RuntimeError("Cannot establish ADB shell ownership")
        report["before"] = snapshot()
        shell(f"test ! -L {REMOTE_DIRECTORY}")
        exists = shell(f"test -e {REMOTE_DIRECTORY}", False)
        if exists["exit_code"] == 1:
            shell(f"mkdir -m 700 {REMOTE_DIRECTORY}")
        elif exists["exit_code"] == 0:
            shell(f"test -d {REMOTE_DIRECTORY}")
        else:
            raise RuntimeError("Cannot inspect the target directory")
        if shell(f"stat -c %u {REMOTE_DIRECTORY}")["stdout"].strip() != uid:
            raise RuntimeError("Target directory is not owned by the current ADB shell")
        shell(f"test ! -L {REMOTE_BINARY}")
        installed = shell(f"test -e {REMOTE_BINARY}", False)
        if installed["exit_code"] == 1:
            adb(["push", str(local_binary), REMOTE_BINARY])
        elif installed["exit_code"] != 0:
            raise RuntimeError("Cannot inspect the target binary")
        remote_hash = shell(f"sha256sum {REMOTE_BINARY}")["stdout"].split()[0]
        if remote_hash != IW_SHA256:
            raise RuntimeError("Device-side checksum mismatch; binary will not be executed")
        shell(f"chmod 500 {REMOTE_BINARY}")
        version = shell(f"{REMOTE_BINARY} --version", False)
        report["version"] = version
        if version["exit_code"] == 0:
            report["queries"] = {query: shell(f"{REMOTE_BINARY} {query}", False) for query in ("dev", "phy", "reg get")}
        else:
            report["queries"] = {}
        report["after"] = snapshot()
        report["network_checks_unchanged"] = {
            command: before["exit_code"] == 0 and report["after"][command]["exit_code"] == 0
                     and before["stdout"] == report["after"][command]["stdout"]
            for command, before in report["before"].items()
        }
        report["complete"] = True
    finally:
        args.output.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(f"Installed, hash-verified: {REMOTE_BINARY}")
    print(f"Version: {report['version']['stdout'].strip() or report['version']['stderr'].strip()}")
    for query, result in report["queries"].items():
        print(f"iw {query}: exit {result['exit_code']}, {result['duration_ms']} ms, {result['stderr'].strip() or 'no stderr'}")
    print(f"Sanitized evidence: {args.output}")


if __name__ == "__main__":
    main()
