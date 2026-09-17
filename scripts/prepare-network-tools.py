#!/usr/bin/env python3
"""Stage pinned arm64 tools locally. Does not install an APK or execute any staged binary."""
import hashlib
import json
from pathlib import Path
import struct
import tarfile
import urllib.request
import zipfile

PROJECT = Path(__file__).resolve().parent.parent
DESTINATION = PROJECT / ".tools/router-toolchain/virtualap-v1.8.0"
RELEASE_COMMIT = "720f2cb51f3db58cef37708f8712780e53cad5b8"
APK_NAME = "VirtualAP-v1.8.0-2026-09-16.apk"
APK_URL = f"https://github.com/ravindu644/VirtualAP/releases/download/v1.8.0/{APK_NAME}"
# GitHub release-asset digest observed and independently checked before extraction.
APK_SHA256 = "5283178db14cb115e6ddffa2521e1a9e16b29e63eb06b11c79b35d12faf8cfbe"
SOURCES = {
    "iw": ("Droidspaces/iw-vap", "f9081ee3c6c092e88da00d3959af7add56538295"),
    "hostapd": ("Droidspaces/hostapd-vap", "fadc5497548ba253ee3a652799a551d059df212b"),
    "dnsmasq": ("Droidspaces/dnsmasq-vap", "02edbd5d1341112902543671a231d47f374a2778"),
}


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def download(url, destination):
    if destination.exists():
        return destination.read_bytes()
    request = urllib.request.Request(url, headers={"User-Agent": "BLACKBOX-tool-provenance"})
    with urllib.request.urlopen(request, timeout=90) as response:
        data = response.read(64 * 1024 * 1024 + 1)
    if len(data) > 64 * 1024 * 1024:
        raise ValueError("Unexpectedly large download")
    destination.write_bytes(data)
    return data


def inspect_static_arm64(data):
    if len(data) < 64 or data[:7] != b"\x7fELF\x02\x01\x01":
        raise ValueError("Expected an ELF64 little-endian executable")
    elf_type, machine = struct.unpack_from("<HH", data, 16)
    if machine != 183 or elf_type not in (2, 3):
        raise ValueError("Expected an AArch64 executable, not a host/other-ABI binary")
    offset = struct.unpack_from("<Q", data, 32)[0]
    entry_size, count = struct.unpack_from("<HH", data, 54)
    if entry_size < 56 or count == 0 or offset + entry_size * count > len(data):
        raise ValueError("Invalid ELF program-header table")
    for index in range(count):
        header = offset + index * entry_size
        kind = struct.unpack_from("<I", data, header)[0]
        if kind == 3:  # PT_INTERP
            raise ValueError("Unexpected dynamic interpreter; Android portability not established")
        if kind == 2:  # PT_DYNAMIC
            dynamic_offset = struct.unpack_from("<Q", data, header + 8)[0]
            dynamic_size = struct.unpack_from("<Q", data, header + 32)[0]
            if dynamic_offset + dynamic_size > len(data):
                raise ValueError("Invalid ELF dynamic segment")
            for entry in range(dynamic_offset, dynamic_offset + dynamic_size, 16):
                tag = struct.unpack_from("<q", data, entry)[0]
                if tag == 0:
                    break
                if tag == 1:  # DT_NEEDED
                    raise ValueError("Unexpected external shared-library dependency")
    return {"format": "ELF64 little-endian", "architecture": "AArch64", "type": elf_type,
            "interpreter": None, "shared_library_dependencies": []}


def main():
    DESTINATION.mkdir(parents=True, exist_ok=True)
    apk_path = DESTINATION / APK_NAME
    apk = download(APK_URL, apk_path)
    if sha256(apk) != APK_SHA256:
        raise SystemExit("Release checksum mismatch. No binaries extracted.")
    tool_directory = DESTINATION / "aarch64"
    tool_directory.mkdir(exist_ok=True)
    source_directory = DESTINATION / "sources"
    source_directory.mkdir(exist_ok=True)
    tools = {}
    with zipfile.ZipFile(apk_path) as archive:
        for name, (repository, commit) in SOURCES.items():
            member = f"assets/bin/aarch64/{name}"
            if archive.getinfo(member).file_size > 16 * 1024 * 1024:
                raise ValueError(f"Unexpected binary size: {name}")
            binary = archive.read(member)
            elf = inspect_static_arm64(binary)
            (tool_directory / name).write_bytes(binary)
            source_url = f"https://codeload.github.com/{repository}/tar.gz/{commit}"
            source_path = source_directory / f"{name}-{commit}.tar.gz"
            source = download(source_url, source_path)
            licenses = {}
            license_directory = DESTINATION / "licenses" / name
            license_directory.mkdir(parents=True, exist_ok=True)
            source_root = f"{repository.split('/')[-1]}-{commit}"
            with tarfile.open(source_path, "r:gz") as source_archive:
                license_filenames = ("COPYING", "COPYING-v3", "README", "hostapd/README") if name == "hostapd" else ("COPYING", "COPYING-v3")
                for filename in license_filenames:
                    try:
                        license_member = source_archive.getmember(f"{source_root}/{filename}")
                    except KeyError:
                        if filename == "COPYING":
                            raise ValueError(f"Missing primary license for {name}")
                        continue
                    if not license_member.isfile() or license_member.size > 1024 * 1024:
                        raise ValueError(f"Unexpected license member for {name}")
                    with source_archive.extractfile(license_member) as license_file:
                        license_data = license_file.read()
                    (license_directory / filename.replace("/", "_")).write_bytes(license_data)
                    licenses[filename] = sha256(license_data)
            tools[name] = {
                "release_member": member, "bytes": len(binary), "sha256": sha256(binary), "elf": elf,
                "source_repository": f"https://github.com/{repository}", "source_commit": commit,
                "source_archive_url": source_url, "source_archive_sha256": sha256(source),
                "source_archive": str(source_path.relative_to(PROJECT)),
                "license_sha256": licenses,
            }
            print(f"Staged {name}: {len(binary)} bytes, AArch64 static, SHA256 {sha256(binary)}")
    manifest = {
        "release": "VirtualAP v1.8.0", "release_commit": RELEASE_COMMIT,
        "release_url": APK_URL, "release_sha256": APK_SHA256, "tools": tools,
        "scope": "Local development artifacts only. Not bundled in BLACKBOX. hostapd/dnsmasq not installed or started on the phone.",
        "verification_limits": "Release checksum, source pins, recipe review and ELF checks; not an independent reproducible build or a complete source security audit.",
    }
    (DESTINATION / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(f"Provenance: {DESTINATION / 'manifest.json'}")


if __name__ == "__main__":
    main()
