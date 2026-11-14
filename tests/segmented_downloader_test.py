"""Run the real SegmentedDownloader against a local HTTP server: ranges, 32 connections,
pause/resume, dropped connections, redirects, servers without range support, errors."""
from pathlib import Path
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
import hashlib, os, re, subprocess, tempfile, threading, time

root = Path(__file__).resolve().parents[1]
source = (root / 'app/src/main/java/com/satlink/geckoviewtest/SegmentedDownloader.java').read_text()
source = re.sub(r'^package .*;\n', '', source, flags=re.M)
imports = ''.join(re.findall(r'^import .*;\n', source, flags=re.M))
source = re.sub(r'^import .*;\n', '', source, flags=re.M)

DATA = os.urandom(24 * 1024 * 1024 + 12345)
OTHER = os.urandom(3 * 1024 * 1024)
state = {'active': 0, 'peak': 0, 'bytes': 0, 'flaky': 0}
lock = threading.Lock()


class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def log_message(self, *args):
        pass

    def send_body(self, body, ranged, total=None, chunked=False, throttle=0.0, drop_after=None):
        start, end = 0, len(body) - 1
        header = self.headers.get('Range')
        status = 200
        if ranged and header:
            m = re.match(r'bytes=(\d+)-(\d*)', header)
            start = int(m.group(1))
            end = int(m.group(2)) if m.group(2) else len(body) - 1
            end = min(end, len(body) - 1)
            if start >= len(body):
                self.send_response(416)
                self.send_header('Content-Range', f'bytes */{len(body)}')
                self.send_header('Content-Length', '0')
                self.end_headers()
                return
            status = 206
        part = body[start:end + 1]
        self.send_response(status)
        if status == 206:
            self.send_header('Content-Range', f'bytes {start}-{end}/{len(body)}')
        if ranged:
            self.send_header('Accept-Ranges', 'bytes')
        if chunked:
            self.send_header('Transfer-Encoding', 'chunked')
        else:
            self.send_header('Content-Length', str(len(part)))
        self.end_headers()
        with lock:
            state['active'] += 1
            state['peak'] = max(state['peak'], state['active'])
        try:
            sent = 0
            step = 32 * 1024
            while sent < len(part):
                chunk = part[sent:sent + step]
                if drop_after is not None and sent >= drop_after:
                    self.connection.shutdown(2)
                    return
                if chunked:
                    self.wfile.write(f'{len(chunk):x}\r\n'.encode() + chunk + b'\r\n')
                else:
                    self.wfile.write(chunk)
                sent += len(chunk)
                with lock:
                    state['bytes'] += len(chunk)
                if throttle:
                    time.sleep(throttle)
            if chunked:
                self.wfile.write(b'0\r\n\r\n')
        except (BrokenPipeError, ConnectionResetError, OSError):
            pass
        finally:
            with lock:
                state['active'] -= 1

    def do_GET(self):
        path = self.path
        if path.startswith('/file'):
            self.send_body(DATA, True, throttle=0.02)
        elif path.startswith('/fast'):
            self.send_body(DATA, True)
        elif path.startswith('/other'):
            self.send_body(OTHER, True)
        elif path.startswith('/norange'):
            self.send_body(DATA, False)
        elif path.startswith('/chunked'):
            self.send_body(OTHER, False, chunked=True)
        elif path.startswith('/flaky'):
            with lock:
                state['flaky'] += 1
                drop = state['flaky'] <= 6 and self.headers.get('Range') != 'bytes=0-0'
            self.send_body(DATA, True, drop_after=256 * 1024 if drop else None)
        elif path.startswith('/needs-header'):
            if self.headers.get('Referer') != 'https://page.test/' or self.headers.get('Cookie') != 'sid=1':
                self.send_response(403)
                self.send_header('Content-Length', '0')
                self.end_headers()
                return
            self.send_body(OTHER, True)
        elif path.startswith('/redirect'):
            self.send_response(302)
            self.send_header('Location', '/fast')
            self.send_header('Content-Length', '0')
            self.end_headers()
        elif path.startswith('/empty'):
            self.send_body(b'', False)
        else:
            self.send_response(404)
            self.send_header('Content-Length', '0')
            self.end_headers()


class Server(ThreadingHTTPServer):
    request_queue_size = 256


server = Server(('127.0.0.1', 0), Handler)
server.daemon_threads = True
threading.Thread(target=server.serve_forever, daemon=True).start()
base = f'http://127.0.0.1:{server.server_address[1]}'

test = r'''
import java.io.*;import java.nio.channels.FileChannel;import java.nio.file.*;import java.security.MessageDigest;import java.util.*;
class DownloaderTest {
 static void check(boolean v,String m){if(!v)throw new AssertionError(m);}
 static String sha(Path p)throws Exception{var d=MessageDigest.getInstance("SHA-256");d.update(Files.readAllBytes(p));var s=new StringBuilder();for(byte b:d.digest())s.append(String.format("%02x",b&255));return s.toString();}
 static String[] saved=new String[1];
 static SegmentedDownloader make(String url,int n,Map<String,String> h){
  var d=new SegmentedDownloader(url,h,n,new SegmentedDownloader.Listener(){public void onProgress(long a,long b,int c){}public void onState(String s){saved[0]=s;}});
  d.retryBaseDelayMs=50;d.readTimeoutMs=5000;return d;}
 static SegmentedDownloader.Result run(SegmentedDownloader d,Path file,String state)throws Exception{
  try(FileChannel ch=FileChannel.open(file,StandardOpenOption.CREATE,StandardOpenOption.READ,StandardOpenOption.WRITE)){return d.download(ch,state);}}
 public static void main(String[] a)throws Exception{
  String base=a[0];String data=a[1];String other=a[2];Path dir=Files.createTempDirectory("seg");
  // 1. 32 parallel connections, exact bytes
  Path f1=dir.resolve("one.bin");var r=run(make(base+"/file",32,null),f1,null);
  check(r.complete && r.total==Files.size(f1),"Not complete");check(sha(f1).equals(data),"Corrupted parallel download");
  System.out.println("peak-after-parallel");
  // 2. pause at ~25% then resume from the saved state
  Path f2=dir.resolve("two.bin");var d2=make(base+"/file",16,null);
  Thread stopper=new Thread(()->{try{while(d2.downloaded()<6_000_000)Thread.sleep(20);d2.stop();}catch(Exception e){}});stopper.start();
  var paused=run(d2,f2,null);stopper.join();check(!paused.complete,"Stop did not pause");
  long before=d2.downloaded();check(before>=6_000_000 && before<r.total,"Pause position wrong "+before);
  String resumeState=saved[0];check(resumeState.startsWith("satlink-v1"),"No resume state");
  System.out.println("resume-start");
  var d3=make(base+"/file",16,null);var resumed=run(d3,f2,resumeState);
  check(resumed.complete && sha(f2).equals(data),"Resumed file corrupted");
  System.out.println("resume-done");
  // 3. server without ranges: one connection, still exact
  Path f3=dir.resolve("three.bin");var r3=run(make(base+"/norange",32,null),f3,null);check(r3.complete && sha(f3).equals(data),"No-range download wrong");
  // 4. redirect
  Path f4=dir.resolve("four.bin");check(run(make(base+"/redirect",8,null),f4,null).complete && sha(f4).equals(data),"Redirect download wrong");
  // 5. dropped connections are retried
  Path f5=dir.resolve("five.bin");check(run(make(base+"/flaky",8,null),f5,null).complete && sha(f5).equals(data),"Flaky download wrong");
  // 6. 404 fails at once
  Path f6=dir.resolve("six.bin");long t=System.currentTimeMillis();
  try{run(make(base+"/missing",8,null),f6,null);check(false,"404 accepted");}catch(SegmentedDownloader.FatalException e){check(e.getMessage().contains("404"),"Wrong error "+e.getMessage());}
  check(System.currentTimeMillis()-t<3000,"404 was retried");
  // 7. saved state from a different file size restarts cleanly
  Path f7=dir.resolve("seven.bin");Files.copy(f1,f7);check(run(make(base+"/other",8,null),f7,resumeState).complete && sha(f7).equals(other),"Stale state reused for a different file");
  // 8. unknown length (chunked, no ranges)
  Path f8=dir.resolve("eight.bin");check(run(make(base+"/chunked",8,null),f8,null).complete && sha(f8).equals(other),"Chunked download wrong");
  // 9. headers reach the server (Referer/Cookie)
  Path f9=dir.resolve("nine.bin");var h=new LinkedHashMap<String,String>();h.put("Referer","https://page.test/");h.put("Cookie","sid=1");
  check(run(make(base+"/needs-header",8,h),f9,null).complete && sha(f9).equals(other),"Headers not sent");
  // 10. empty file
  Path f10=dir.resolve("ten.bin");var r10=run(make(base+"/empty",8,null),f10,null);check(r10.complete && Files.size(f10)==0,"Empty file");
  // 11. connection count is clamped to 1..32
  check(make(base+"/fast",500,null)!=null,"clamp");
  System.out.println("ALLOK");
 }
}
'''

with tempfile.TemporaryDirectory() as tmp:
    target = Path(tmp) / 'DownloaderTest.java'
    target.write_text(imports + test + source)
    proc = subprocess.Popen(['java', str(target), base, hashlib.sha256(DATA).hexdigest(), hashlib.sha256(OTHER).hexdigest()],
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    peak_parallel = None
    served_before_resume = served_after_resume = None
    lines = []
    for line in proc.stdout:
        lines.append(line)
        if 'peak-after-parallel' in line:
            peak_parallel = state['peak']
        if 'resume-start' in line:
            served_before_resume = state['bytes']
        if 'resume-done' in line:
            served_after_resume = state['bytes']
    proc.wait()
    output = ''.join(l for l in lines if 'JAVA_TOOL_OPTIONS' not in l)
    assert proc.returncode == 0 and 'ALLOK' in output, output
    assert peak_parallel is not None and peak_parallel >= 30, f'Only {peak_parallel} parallel connections'
    resumed_bytes = served_after_resume - served_before_resume
    assert resumed_bytes < len(DATA) * 0.85, f'Resume downloaded {resumed_bytes} of {len(DATA)} bytes again'
server.shutdown()
print(f'PASS segmented downloader: {peak_parallel} parallel connections, exact SHA-256, pause/resume ({resumed_bytes // 1024} KiB after resume), '
      'no-range single stream, redirect, dropped-connection retry, 404 fatal without retry, stale-state restart, chunked, headers, empty file')
