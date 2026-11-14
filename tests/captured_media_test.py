"""Run the real CapturedMediaFilter (junk files, probe, player-frame order) and
DownloadProgressTracker (multi-file yt-dlp progress) against a local HTTP server."""
from pathlib import Path
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
import re, subprocess, tempfile, threading

root = Path(__file__).resolve().parents[1]
base = root / 'app/src/main/java/com/satlink/geckoviewtest'
def strip(name):
    text = re.sub(r'^package .*;\n', '', (base / name).read_text(), flags=re.M)
    return re.findall(r'^import .*;\n', text, flags=re.M), re.sub(r'^import .*;\n', '', text, flags=re.M)
sources, imports = [], set()
for name in ['CapturedMediaFilter.java', 'DownloadProgressTracker.java', 'TorrentSupport.java', 'GeneralFilePolicy.java']:
    found, body = strip(name); imports.update(found); sources.append(body)

class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    def log_message(self, *a): pass
    def reply(self, code, ctype, total, ranged=True):
        self.send_response(code)
        self.send_header('Content-Type', ctype)
        if code == 206: self.send_header('Content-Range', f'bytes 0-0/{total}')
        body = b'x' if code in (200, 206) else b''
        if code == 200: self.send_header('Content-Length', str(total)); body = b'x' * min(total, 1)
        else: self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        if code == 200: self.wfile.write(b'x' * total if total < 1000 else b'')
        else: self.wfile.write(body)
    def do_GET(self):
        p = self.path
        if p.startswith('/movie.mp4'): self.reply(206, 'video/mp4', 900_000_000)
        elif p.startswith('/tiny.mp4'): self.reply(206, 'video/mp4', 146)
        elif p.startswith('/page.mp4'): self.reply(206, 'text/html; charset=utf-8', 5_000_000)
        elif p.startswith('/pixel.mp4'): self.reply(206, 'image/gif', 43)
        elif p.startswith('/norange.mp4'): self.reply(200, 'video/mp4', 300)
        elif p.startswith('/needs-referer.mp4'):
            if self.headers.get('Referer') == 'https://site.test/': self.reply(206, 'video/mp4', 50_000_000)
            else: self.reply(403, 'text/plain', 0)
        else: self.reply(404, 'text/plain', 0)

class Quiet(ThreadingHTTPServer):
    def handle_error(self, request, client_address): pass

server = Quiet(('127.0.0.1', 0), Handler); server.daemon_threads = True
threading.Thread(target=server.serve_forever, daemon=True).start()
url = f'http://127.0.0.1:{server.server_address[1]}'

test = r'''
class MediaTest {
 static void check(boolean v,String m){if(!v)throw new AssertionError(m);}
 public static void main(String[] a){
  String b=a[0];
  check(CapturedMediaFilter.tooSmall(146) && !CapturedMediaFilter.tooSmall(0) && !CapturedMediaFilter.tooSmall(5_000_000),"Size rule");
  check(CapturedMediaFilter.manifest("https://x/master.m3u8?t=1","") && CapturedMediaFilter.manifest("https://x/v","application/dash+xml") && !CapturedMediaFilter.manifest("https://x/v.mp4","video/mp4"),"Manifest rule");
  check(CapturedMediaFilter.probe(b+"/movie.mp4",null)==900_000_000L,"Real movie size");
  check(CapturedMediaFilter.probe(b+"/tiny.mp4",null)==CapturedMediaFilter.JUNK,"146-byte placeholder kept");
  check(CapturedMediaFilter.probe(b+"/page.mp4",null)==CapturedMediaFilter.JUNK,"Web page kept");
  check(CapturedMediaFilter.probe(b+"/pixel.mp4",null)==CapturedMediaFilter.JUNK,"Image kept");
  check(CapturedMediaFilter.probe(b+"/norange.mp4",null)==CapturedMediaFilter.JUNK,"Small file without ranges kept");
  check(CapturedMediaFilter.probe(b+"/gone.mp4",null)==CapturedMediaFilter.JUNK,"404 kept");
  check(CapturedMediaFilter.probe(b+"/needs-referer.mp4",null)==CapturedMediaFilter.UNKNOWN,"Protected file hidden instead of kept");
  check(CapturedMediaFilter.probe(b+"/needs-referer.mp4",java.util.Map.of("Referer","https://site.test/"))==50_000_000L,"Headers not sent");
  check(CapturedMediaFilter.probe("http://127.0.0.1:1/x.mp4",null)==CapturedMediaFilter.UNKNOWN,"Network failure hidden");
  var frames=java.util.List.of("https://ads.example/banner","https://topcinemaa.one/watch/","https://cdn.other.net/embed/abc","https://streamwish.to/e/xyz","https://vidhide.com/v/123","http://insecure.test/e/1");
  var ranked=CapturedMediaFilter.rankPlayerFrames(frames,"https://topcinemaa.one/watch/",5);
  check(ranked.get(0).contains("streamwish") && ranked.get(1).contains("vidhide") && ranked.get(2).contains("cdn.other.net"),"Known players not first: "+ranked);
  check(!ranked.contains("https://topcinemaa.one/watch/") && ranked.stream().noneMatch(u->u.startsWith("http://")),"Page or insecure frame kept");
  check(CapturedMediaFilter.rankPlayerFrames(frames,"https://topcinemaa.one/watch/",2).size()==2,"Limit");
  // Progress: video file 0..100 then audio file 0..100 must not stick at 100.
  var t=new DownloadProgressTracker(240L*1024*1024,2);
  t.update(50,"[download]  50.0% of ~ 220.00MiB at 3.10MiB/s ETA 00:55");check(t.percent()==45,"Half video "+t.percent());
  t.update(100,"[download] 100% of 220.00MiB in 01:10");int afterVideo=t.percent();check(afterVideo>=90 && afterVideo<=99,"Video done "+afterVideo);
  t.update(2,"[download]   2.0% of 20.00MiB at 1MiB/s ETA 00:19");check(t.percent()>=afterVideo-1 && t.percent()<=99,"Audio start jumped "+t.percent());
  check(t.downloadedBytes()>=220L*1024*1024 && t.downloadedBytes()<221L*1024*1024,"Downloaded bytes "+t.downloadedBytes());
  t.update(100,"[download] 100% of 20.00MiB");check(t.percent()==99 && t.totalBytes()==240L*1024*1024,"End "+t.percent());
  // The reported case: a 235 MB estimate, the bar read 100% at 51.8 MB.
  var r=new DownloadProgressTracker(235L*1000*1000,2);r.update(100,"[download] 100% of 3.00MiB");r.update(22,"[download]  22.0% of ~ 232.00MiB at 3.10MiB/s");
  check(r.percent()<30,"Stuck at 100% again: "+r.percent());
  // No size in the lines: fall back to file count.
  var n=new DownloadProgressTracker(0,2);n.update(100,"x");n.update(50,"y");check(n.percent()==75,"Count fallback "+n.percent());
  var one=new DownloadProgressTracker(0,1);one.update(40,"");check(one.percent()==40,"Single file "+one.percent());
  check(DownloadProgressTracker.parseSize("[download]  5.0% of ~  1.50GiB at")==Math.round(1.5*1073741824L),"GiB size");
  System.out.println("PASS captured media: 146 B placeholder, web page, image, 404 hidden; real size learned; headers sent; unknown kept; known player frames first; multi-file progress no longer sticks at 100%");
 }
}
'''
with tempfile.TemporaryDirectory() as tmp:
    target = Path(tmp) / 'MediaTest.java'
    target.write_text(''.join(sorted(imports)) + test + ''.join(sources))
    subprocess.run(['java', str(target), url], check=True)
server.shutdown()
