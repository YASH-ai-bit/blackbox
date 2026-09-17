#!/usr/bin/env python3
"""Opt-in Wi-Fi client test; always restores the Windows connection in finally.
Requires RouterClientTest fixture running. No upstream credentials are read/exported.
"""
import ipaddress
import json
from pathlib import Path
import re
import shutil
import socket
import struct
import subprocess
import time
import urllib.parse
import uuid
import xml.etree.ElementTree as ET

PROJECT=Path(__file__).resolve().parent.parent
ADB=shutil.which('adb') or str(PROJECT/'.tools/android-sdk/platform-tools/adb.exe')
def run(args,timeout=15,check=True):
    r=subprocess.run(args,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=timeout)
    if check and r.returncode:raise RuntimeError(f'{args[0]} failed: {r.stderr or r.stdout}')
    return r
def adb(command):return run([ADB,'shell',command]).stdout
def dns(server,name,expected_rcode=0):
    transaction=uuid.uuid4().int&65535
    query=struct.pack('!6H',transaction,0x100,1,0,0,0)+b''.join(bytes([len(p)])+p.encode() for p in name.split('.'))+b'\0\0\1\0\1'
    with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as s:
        s.settimeout(6);s.connect((server,53));s.send(query);data=s.recv(4096)
    tid,flags,qd,an,_,_=struct.unpack('!6H',data[:12]);assert tid==transaction and flags&0x8000 and flags&15==expected_rcode
    def skip(i):
        while data[i]:
            if data[i]&0xc0==0xc0:return i+2
            i+=data[i]+1
        return i+1
    offset=12
    for _ in range(qd):offset=skip(offset)+4
    answers=[]
    for _ in range(an):
        offset=skip(offset);kind,cls,ttl,size=struct.unpack('!HHIH',data[offset:offset+10]);offset+=10
        if kind==1 and cls==1 and size==4:answers.append(socket.inet_ntoa(data[offset:offset+size]))
        offset+=size
    return answers
def main():
    report={};temp=PROJECT/'.tools/client-test';temp.mkdir(exist_ok=True)
    before=run(['netsh','wlan','show','interfaces']).stdout
    original=re.search(r'^\s*Profile\s*:\s*(.+)$',before,re.M).group(1).strip()
    interface=re.search(r'^\s*Name\s*:\s*(.+)$',before,re.M).group(1).strip()
    fixture=json.loads(adb('run-as dev.blackbox.router cat files/client-test-ready.json'))
    name='BLACKBOX-LAB-'+uuid.uuid4().hex[:8];profile_path=temp/'wifi-profile.xml';added=False
    ns='http://www.microsoft.com/networking/WLAN/profile/v1';ET.register_namespace('',ns)
    def child(parent,key,value=None):
        node=ET.SubElement(parent,'{'+ns+'}'+key)
        if value is not None:node.text=value
        return node
    root=ET.Element('{'+ns+'}WLANProfile');child(root,'name',name)
    ssid=child(child(root,'SSIDConfig'),'SSID');child(ssid,'name',fixture['ssid'])
    child(root,'connectionType','ESS');child(root,'connectionMode','manual')
    security=child(child(root,'MSM'),'security');auth=child(security,'authEncryption')
    child(auth,'authentication','WPA2PSK');child(auth,'encryption','AES');child(auth,'useOneX','false')
    key=child(security,'sharedKey');child(key,'keyType','passPhrase');child(key,'protected','false');child(key,'keyMaterial',fixture['password'])
    ET.ElementTree(root).write(profile_path,encoding='utf-8',xml_declaration=True)
    try:
        run(['netsh','wlan','add','profile',f'filename={profile_path}',f'interface={interface}','user=current']);added=True
        profile_path.unlink()
        run(['netsh','wlan','connect',f'name={name}',f'interface={interface}'])
        print('Connecting this computer to the BLACKBOX test network...',flush=True)
        client_ip=None
        for _ in range(30):
            r=run(['powershell','-NoProfile','-Command',"Get-NetIPAddress -AddressFamily IPv4 | Select-Object -ExpandProperty IPAddress"])
            client_ip=next((x.strip() for x in r.stdout.splitlines() if x.strip().startswith('192.168.50.') and x.strip()!=fixture['gateway']),None)
            if client_ip:break
            time.sleep(1)
        assert client_ip,'No BLACKBOX DHCP address received'
        assert ipaddress.ip_address(client_ip) in ipaddress.ip_network('192.168.50.0/24')
        report['dhcp']=True
        report['gateway_ping']=run(['ping','-n','2','-w','1500',fixture['gateway']],check=False).returncode==0
        report['local_dns']=fixture['gateway'] in dns(fixture['gateway'],'router.blackbox')
        assert report['gateway_ping'] and report['local_dns']
        if fixture.get('servicesTest'):
            report['dashboard_dns']=fixture['gateway'] in dns(fixture['gateway'],'dashboard.blackbox')
            response=run(['curl.exe','--noproxy','*','--interface',client_ip,'--max-time','5','--silent',f"http://{fixture['gateway']}:8080/status.json"])
            status=json.loads(response.stdout)
            report['dashboard_http']=status['name']=='BLACKBOX' and status['gateway']==fixture['gateway']
            assert report['dashboard_dns'] and report['dashboard_http']
            report['adblock_dns']=dns(fixture['gateway'],'blocked.blackbox-test.example',expected_rcode=3)==[]
            assert report['adblock_dns']
        if fixture['mode']=='OFFLINE':
            response=run(['curl.exe','--noproxy','*','--interface',client_ip,'--max-time','5','--silent','--output','NUL','http://1.1.1.1/'],timeout=8,check=False)
            report['offline_blocks_internet']=response.returncode!=0
            assert report['offline_blocks_internet']
        if fixture.get('vpnTest'):
            # This real WireGuard interface intentionally has no peer. Internet must stay blocked.
            for state in ['present_without_peer','removed']:
                if state=='removed':
                    adb("run-as dev.blackbox.router sh -c 'echo \"{\\\"removeVpn\\\":true}\" > files/client-test-control.json'")
                    time.sleep(3)
                    assert 'vpn removed' in adb('run-as dev.blackbox.router cat files/client-test-control-result')
                response=run(['curl.exe','--noproxy','*','--interface',client_ip,'--max-time','5','--silent','--output','NUL','http://1.1.1.1/'],timeout=8,check=False)
                report['vpn_'+state+'_blocks_ip']=response.returncode!=0
                try: dns(fixture['gateway'],'example.com'); leaked=True
                except (OSError,AssertionError):leaked=False
                report['vpn_'+state+'_blocks_dns']=not leaked
                assert not leaked and response.returncode!=0,'VPN kill switch allowed fallback'
                assert fixture['gateway'] in dns(fixture['gateway'],'router.blackbox')
        elif fixture['mode']!='OFFLINE':
            addresses=dns(fixture['gateway'],'example.com');assert addresses
            report['internet_dns']=True
            args=['curl.exe','--noproxy','*','--interface',client_ip,'--max-time','12','--silent','--output','NUL','--write-out','%{http_code}','--resolve',f'example.com:443:{addresses[0]}','https://example.com/']
            response=run(args,timeout=15);report['https']=response.stdout.strip()=='200';assert report['https'],response.stdout
            time.sleep(6)
            for policy,expected in [('LAN_ONLY',False),('NORMAL',True)]:
                adb("run-as dev.blackbox.router sh -c 'echo \"{\\\"policy\\\":\\\""+policy+"\\\"}\" > files/client-test-control.json'")
                time.sleep(3)
                assert 'applied' in adb('run-as dev.blackbox.router cat files/client-test-control-result')
                response=run(args,timeout=15,check=False);success=response.returncode==0 and response.stdout.strip()=='200'
                report['policy_'+policy]=success==expected
                assert success==expected,f'Policy {policy} did not enforce expected forwarding'
        print(json.dumps(report),flush=True)
    except Exception as e:
        report['error']=str(e);raise
    finally:
        run(['netsh','wlan','connect',f'name={original}',f'interface={interface}'],check=False)
        for _ in range(20):
            now=run(['netsh','wlan','show','interfaces'],check=False).stdout
            if re.search(r'^\s*Profile\s*:\s*'+re.escape(original)+r'\s*$',now,re.M):report['original_wifi_restored']=True;break
            time.sleep(1)
        if added:run(['netsh','wlan','delete','profile',f'name={name}',f'interface={interface}'],check=False)
        profile_path.unlink(missing_ok=True)
        adb("run-as dev.blackbox.router touch files/client-test-stop")
        (PROJECT/'device-reports/windows-client-test.json').write_text(json.dumps(report,indent=2))
        print('Original Windows Wi-Fi restoration requested; private test profile removed.',flush=True)
if __name__=='__main__':main()
