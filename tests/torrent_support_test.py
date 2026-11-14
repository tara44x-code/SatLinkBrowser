"""Run the real TorrentSupport parser and aria2c argument builder."""
from pathlib import Path
import re, subprocess, tempfile

root = Path(__file__).resolve().parents[1]
base = root / 'app/src/main/java/com/satlink/geckoviewtest'
def strip(name):
    text = (base / name).read_text()
    text = re.sub(r'^package .*;\n', '', text, flags=re.M)
    return re.findall(r'^import .*;\n', text, flags=re.M), re.sub(r'^import .*;\n', '', text, flags=re.M)

imports, support = strip('TorrentSupport.java')
imports2, policy = strip('GeneralFilePolicy.java')
test = r'''
class TorrentTest {
 static void check(boolean v,String m){if(!v)throw new AssertionError(m);}
 public static void main(String[] a){
  var p=TorrentSupport.parse("[#2089b0 400.0KiB/33.2MiB(1%) CN:8 SD:3 DL:115.7KiB ETA:4m51s]");
  check(p.done==409600 && p.total==Math.round(33.2*1048576) && p.connections==8 && p.seeds==3 && p.speed==Math.round(115.7*1024),"Readout");
  var u=TorrentSupport.parse("[#ab12 1.0GiB/2.0GiB(50%) CN:40 SD:12 DL:3.5MiB UL:120KiB ETA:5m]");
  check(u.total==2147483648L && u.speed==Math.round(3.5*1048576) && u.upload==122880 && u.connections==40,"Upload readout");
  var meta=TorrentSupport.parse("[#ab12 0B/0B CN:5 SD:0 DL:0B]");check(meta.total==0 && meta.connections==5,"Metadata phase");
  check(TorrentSupport.parse("[#ab12 SEED(0.0) CN:3 SD:0 UL:1KiB]").seeding,"Seeding");
  check(TorrentSupport.parse("Download complete: /x")==null && TorrentSupport.parse(null)==null,"Non-readout lines");
  check(TorrentSupport.size("0B")==0 && TorrentSupport.size("12B")==12 && TorrentSupport.size("1.5TiB")==Math.round(1.5*1099511627776L) && TorrentSupport.size("abc")==-1,"Sizes");
  check(TorrentSupport.filePath("FILE: /dl/SatLink/Ubuntu/disk.iso").equals("/dl/SatLink/Ubuntu/disk.iso"),"FILE line");
  check(TorrentSupport.topLevel("/dl/SatLink/Ubuntu/disk.iso","/dl/SatLink").equals("Ubuntu") && TorrentSupport.topLevel("/dl/SatLink/one.iso","/dl/SatLink/").equals("one.iso") && TorrentSupport.topLevel("/other/x","/dl/SatLink").isEmpty(),"Top level name");
  String hex="magnet:?xt=urn:btih:C12FE1C06BBA254A9DC9F519B335AA7C1367A88A&dn=Big%20Buck%20Bunny&tr=udp%3A%2F%2Ftracker.test%3A80";
  check(TorrentSupport.infoHash(hex).equals("c12fe1c06bba254a9dc9f519b335aa7c1367a88a"),"Hex hash");
  check(TorrentSupport.displayName(hex).equals("Big Buck Bunny"),"Display name "+TorrentSupport.displayName(hex));
  // Same hash in base32 form
  check(TorrentSupport.infoHash("magnet:?xt=urn:btih:YEX6DQDLXISUVHOJ6UM3GNNKPQJWPKEK").equals("c12fe1c06bba254a9dc9f519b335aa7c1367a88a"),"Base32 hash "+TorrentSupport.infoHash("magnet:?xt=urn:btih:YEX6DQDLXISUVHOJ6UM3GNNKPQJWPKEK"));
  check(TorrentSupport.infoHash("magnet:?dn=x").isEmpty() && TorrentSupport.infoHash("https://x").isEmpty(),"Missing hash");
  check(TorrentSupport.displayName("magnet:?xt=urn:btih:"+"a".repeat(40)+"&dn=..%2F..%2Fescape").indexOf('/')<0,"Path in name");
  var args=TorrentSupport.arguments(hex,"/dl/SatLink","/data/dht.dat","/data/cert.pem",1234);
  check(args.get(args.size()-1).equals(hex),"Source last");
  for(String need:new String[]{"--dir=/dl/SatLink","--seed-time=0","--async-dns=false","--enable-dht=true","--dht-file-path=/data/dht.dat","--ca-certificate=/data/cert.pem","--stop-with-process=1234","--continue=true","--bt-save-metadata=true","--file-allocation=none","--max-connection-per-server=16"})
   check(args.contains(need),"Missing "+need);
  check(!TorrentSupport.arguments("x.torrent","/d","/f","",0).stream().anyMatch(s->s.startsWith("--ca-certificate")||s.startsWith("--stop-with-process")),"Empty options added");
  System.out.println("PASS torrent support: aria2c readout (progress, peers, seeds, speed, upload, metadata, seeding), sizes, FILE paths, top-level name, magnet hex/base32 hash, display name, safe names, aria2c options");
 }
}
'''
with tempfile.TemporaryDirectory() as tmp:
    target = Path(tmp) / 'TorrentTest.java'
    target.write_text(''.join(sorted(set(imports + imports2))) + test + support + policy)
    subprocess.run(['java', str(target)], check=True)
