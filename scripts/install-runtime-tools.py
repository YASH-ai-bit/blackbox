#!/usr/bin/env python3
"""Provision checksum-pinned tools for BLACKBOX on an already rooted arm64 phone."""
import argparse
import hashlib
from pathlib import Path
import shlex
import shutil
import subprocess

ROOT=Path(__file__).resolve().parent.parent
HASHES={"iw":"3314eceb85558bd5c81556b225bd4bb9a77d494d83bbc3c27a3f5eb512a937c3",
        "hostapd":"f5b6fd733b71ce7662189cd4c86dc26933225673376bdc1ccfc1ec4999befbe9",
        "dnsmasq":"916c1455f639b175e34f9eb775e0d6dc1570d141c83cd50913ffca68bc92edee"}
def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--serial');a=p.parse_args()
    adb=[shutil.which('adb') or str(ROOT/'.tools/android-sdk/platform-tools/adb.exe')]+(['-s',a.serial] if a.serial else [])
    def run(args):return subprocess.run(adb+args,check=True,capture_output=True,text=True,timeout=30).stdout.strip()
    def root(command):return run(['shell','su -c '+shlex.quote(command)])
    assert 'uid=0(' in root('id')
    assert run(['shell','getprop ro.product.cpu.abi'])=='arm64-v8a'
    for directory in ['/data/adb/blackbox','/data/adb/blackbox/tools']:
        root(f'test ! -L {directory} && mkdir -p {directory} && test "$(stat -c %u {directory})" = 0 && chmod 700 {directory}')
    for name,digest in HASHES.items():
        local=ROOT/f'.tools/router-toolchain/virtualap-v1.8.0/aarch64/{name}'
        assert hashlib.sha256(local.read_bytes()).hexdigest()==digest
        temp=f'/data/local/tmp/blackbox-install-{digest[:12]}'
        target=f'/data/adb/blackbox/tools/{name}'
        run(['shell',f'test ! -L {temp}']);run(['push',str(local),temp])
        assert root(f'sha256sum {temp}').split()[0]==digest
        root(f'test ! -L {target} && cp {temp} {target} && chown 0:0 {target} && chmod 700 {target}')
        assert root(f'sha256sum {target}').split()[0]==digest
        run(['shell',f'rm {temp}'])
        print(f'Installed verified {name} at {target}')
    print(root('/data/adb/blackbox/tools/dnsmasq --version').splitlines()[0])
if __name__=='__main__': main()
