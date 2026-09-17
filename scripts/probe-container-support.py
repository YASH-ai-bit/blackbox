#!/usr/bin/env python3
"""Non-persistent namespace probes. Never mounts a filesystem or starts container init."""
import json
from pathlib import Path
import shlex
import shutil
import subprocess

project=Path(__file__).resolve().parent.parent
adb=shutil.which('adb') or str(project/'.tools/android-sdk/platform-tools/adb.exe')
commands={
    'kernel_config': "zcat /proc/config.gz | grep -E 'CONFIG_(NAMESPACES|NET_NS|PID_NS|UTS_NS|IPC_NS|CGROUPS|VETH|BRIDGE)(=| is not set)'",
    'network_namespace':'unshare -n true',
    'mount_namespace':'unshare -m true',
    'pid_namespace':'unshare -p -f true',
    'uts_namespace':'unshare -u true',
    'ipc_namespace':'unshare -i true',
    'selinux':'getenforce',
}
report={}
for name,command in commands.items():
    r=subprocess.run([adb,'shell','su -c '+shlex.quote(command)],capture_output=True,text=True,timeout=20)
    report[name]={'exit_code':r.returncode,'stdout':r.stdout.strip(),'stderr':r.stderr.strip()}
report['full_init_container_supported']=all(report[n]['exit_code']==0 for n in ('network_namespace','mount_namespace','pid_namespace','uts_namespace','ipc_namespace'))
report['scope']='Namespace smoke tests only; no OpenWrt rootfs, daemon, mount, bridge, or persistent change.'
(project/'device-reports/openwrt-prerequisites.json').write_text(json.dumps(report,indent=2))
print(json.dumps(report,indent=2))
