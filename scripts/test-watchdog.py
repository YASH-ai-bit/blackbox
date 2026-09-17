#!/usr/bin/env python3
"""Opt-in destructive-to-the-app test: force-stop an owned offline fixture, verify runtime cleanup."""
import json
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import time

PROJECT=Path(__file__).resolve().parent.parent
ADB=shutil.which('adb') or str(PROJECT/'.tools/android-sdk/platform-tools/adb.exe')
def adb(command,check=True):
    r=subprocess.run([ADB,'shell',command],capture_output=True,text=True,timeout=30)
    if check and r.returncode:raise RuntimeError(r.stderr or r.stdout)
    return r
def root(command):return adb('su -c '+shlex.quote(command)).stdout.strip()
def snapshot():
    return {'rules':root('ip rule show'),'forward4':root('cat /proc/sys/net/ipv4/ip_forward'),
            'forward6':root('cat /proc/sys/net/ipv6/conf/all/forwarding')}
def main():
    assert 'BB_' not in root('iptables-save'),'Stop/recover the current router before the watchdog test'
    before=snapshot();report={}
    adb('run-as dev.blackbox.router rm -f files/client-test-ready.json')
    log=(PROJECT/'.tools/router-watchdog-fixture.log').open('w',encoding='utf-8')
    fixture=subprocess.Popen([ADB,'shell','am','instrument','-w','-r','-e','clientTests','true','-e','mode','OFFLINE','-e','servicesTest','true','-e','class','dev.blackbox.router.RouterClientTest','dev.blackbox.router.test/androidx.test.runner.AndroidJUnitRunner'],stdout=log,stderr=subprocess.STDOUT,
        creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
    forced=False
    try:
        for _ in range(90):
            if adb('run-as dev.blackbox.router test -f files/client-test-ready.json',False).returncode==0:break
            if fixture.poll() is not None:raise RuntimeError('Fixture exited before ready; inspect its log')
            time.sleep(1)
        else:raise RuntimeError('Fixture did not become ready')
        assert 'BB_' in root('iptables-save')
        adb('am force-stop dev.blackbox.router');forced=True;started=time.monotonic()
        for _ in range(30):
            chains=root('iptables-save')+root('ip6tables-save')
            interfaces=root('ip -o link show')
            if 'BB_' not in chains and not re.search(r'\bbb[0-9a-f]{8}(?:@|:)',interfaces):break
            time.sleep(1)
        report['cleanup_seconds']=round(time.monotonic()-started,1)
        report['owned_firewall_removed']='BB_' not in chains
        report['owned_ap_removed']=not bool(re.search(r'\bbb[0-9a-f]{8}(?:@|:)',interfaces))
        after=snapshot()
        for name in before:report[name+'_restored']=before[name]==after[name]
        assert all(v is True for k,v in report.items() if k!='cleanup_seconds'),report
        print(json.dumps(report))
    finally:
        if not forced:adb('run-as dev.blackbox.router touch files/client-test-stop',False)
        try:fixture.wait(timeout=35)
        except subprocess.TimeoutExpired:fixture.terminate()
        log.close()
        (PROJECT/'device-reports/watchdog-test.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
if __name__=='__main__':main()
