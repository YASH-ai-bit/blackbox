#!/usr/bin/env python3
"""Read-only ADB preflight. Only sanitized output is written; no credentials/config files are read."""
import argparse
import datetime
import json
from pathlib import Path
import re
import shlex
import shutil
import subprocess

PROJECT = Path(__file__).resolve().parent.parent

def sanitize(text):
    text = re.sub(r"(?im)\b(ssid|password|(?:wpa_)?passphrase|(?:wpa_)?psk|identity|private[_ -]?key|token|secret)\s*[:= ].*", r"\1=[redacted]", text)
    text = re.sub(r"(?i)(?:[0-9a-f]{2}:){5}[0-9a-f]{2}", "[MAC]", text)
    text = re.sub(r"(?<![\d.])(?:\d{1,3}\.){3}\d{1,3}(?![\d.])", "[IP]", text)
    text = re.sub(r"(?i)(?<![\w:])(?:[a-f0-9]{0,4}:){2,}[a-f0-9:.]*(?:%[\w.-]+)?", "[IPv6]", text)
    return "".join(c for c in text if c in "\n\t" or ord(c) >= 32)[:64000]

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    default_adb = shutil.which("adb") or str(PROJECT / ".tools/android-sdk/platform-tools/adb.exe")
    parser.add_argument("--adb", default=default_adb)
    parser.add_argument("--serial", help="Use when multiple devices are attached; serial is never saved")
    parser.add_argument("--request-root", action="store_true", help="May trigger a root-manager approval prompt")
    parser.add_argument("--output", type=Path, default=PROJECT / "device-reports/adb-preflight.json")
    args = parser.parse_args()
    base = [args.adb] + (["-s", args.serial] if args.serial else [])
    def shell(command):
        try:
            result = subprocess.run(base + ["shell", command], capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=25)
            return {"command": command, "exit_code": result.returncode, "stdout": sanitize(result.stdout), "stderr": sanitize(result.stderr)}
        except subprocess.TimeoutExpired:
            return {"command": command, "exit_code": -1, "stdout": "", "stderr": "Timed out"}
    device = subprocess.run(base + ["get-state"], capture_output=True, text=True, timeout=15)
    if device.returncode or device.stdout.strip() != "device":
        raise SystemExit("ADB device unavailable. Unlock phone, enable USB debugging, accept trust prompt, or select --serial.")
    commands = [
        "getprop ro.product.model", "getprop ro.product.device", "getprop ro.product.cpu.abi",
        "getprop ro.build.version.release", "getprop ro.build.version.sdk", "getprop ro.board.platform",
        "getprop ro.build.flavor", "getprop ro.build.display.id", "getprop ro.boot.flash.locked",
        "getprop ro.boot.vbmeta.device_state", "uname -a", "id", "getenforce", "command -v su",
        "ip link show", "ip addr show", "ip route show table all", "ip rule show",
        "cat /proc/sys/net/ipv4/ip_forward", "ls /sys/class/ieee80211", "ls /sys/class/net",
    ]
    records = [shell(command) for command in commands]
    root = None
    if args.request_root:
        root = shell("su -c id")
        records.append(root)
    use_root = root is not None and root["exit_code"] == 0 and root["stdout"].startswith("uid=0(")
    for binary in ["ip", "iw", "iptables", "iptables-nft", "nft", "hostapd", "dnsmasq", "wg"]:
        discovery = f'command -v {binary} || {{ for d in /system/bin /system/xbin /vendor/bin /vendor/bin/hw /odm/bin /product/bin /sbin /data/adb/magisk; do if [ -x "$d/{binary}" ]; then echo "$d/{binary}"; exit 0; fi; done; exit 1; }}'
        found = shell("su -c " + shlex.quote(discovery) if use_root else discovery)
        records.append(found)
        if found["exit_code"] != 0:
            continue
        path = found["stdout"].strip().splitlines()[0]
        if not re.fullmatch(r"/[A-Za-z0-9_./+-]+", path):
            continue
        for flags in ({"iw": ["dev", "phy"], "iptables": ["--version"], "nft": ["--version"]}.get(binary, [])):
            command = shlex.quote(path) + " " + flags
            records.append(shell("su -c " + shlex.quote(command) if use_root else command))
    # dumpsys wifi may contain sensitive fields; retain only specific capability/role evidence.
    wifi = subprocess.run(base + ["shell", "dumpsys wifi"], capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=25)
    evidence = []
    for line in wifi.stdout.splitlines():
        text = line.strip()
        if text.startswith("STA + AP Concurrency Supported:"):
            evidence.append(text)
        elif text.startswith("mApInterfaceName:") and not text.endswith("null"):
            evidence.append(text)
        elif text.startswith("mCurrentSoftApInfoMap"):
            frequencies = re.findall(r"frequency=\s*(\d+)", text)
            if frequencies:
                evidence.append("Active SoftAP frequencies (MHz): " + ", ".join(frequencies))
        elif text.startswith("mWifiInfo") and "Supplicant state: COMPLETED" in text:
            frequency = re.search(r"Frequency: (\d+)MHz", text)
            evidence.append("Connected STA frequency (MHz): " + (frequency.group(1) if frequency else "unknown"))
    report = {
        "captured_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "scope": "Read-only ADB shell. App UID and root shell permissions differ. No networking changes.",
        "root_granted": use_root,
        "wifi_evidence": evidence,
        "records": records,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(f"Sanitized preflight saved to {args.output}")
    print("\n".join(evidence) or "No framework Wi-Fi concurrency evidence accessible")
    print("Root: " + ("verified" if use_root else "not available or not requested"))

if __name__ == "__main__":
    main()
