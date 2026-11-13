"""Run the actual download request builder and task intent persistence with small API substitutes."""
from pathlib import Path
import re,subprocess,tempfile
root=Path(__file__).resolve().parents[1];base=root/'app/src/main/java/com/satlink/geckoviewtest'
s=(base/'MediaDownloadService.java').read_text()
constants='\n'.join(re.findall(r'^    public static final (?:String|int) (?:EXTRA_\w+|TYPE_\w+|STATE_QUEUED) = .*?;',s,re.M))
builder=s[s.index('    private YoutubeDLRequest buildRequest'):s.index('    private void pauseTask')]
model=s[s.index('    private static final class TaskState'):s.rfind('\n}')]
parts=[]
for name in ['CapturedDownloadPlan.java','EngineRuntimeOptions.java']:
 p=(base/name).read_text();p=re.sub(r'^package .*;\n|^import .*;\n','',p,flags=re.M);parts.append(p)
source='''import java.util.*;import java.util.concurrent.atomic.AtomicBoolean;import java.io.*;import java.nio.file.*;import java.nio.charset.StandardCharsets;import java.net.*;
class DownloadRequestIntegrationTest {
 public static void main(String[] args)throws Exception{MediaDownloadService.test();}
}
class Context {ApplicationInfo getApplicationInfo(){return new ApplicationInfo();}}
class ApplicationInfo {String nativeLibraryDir="/no-native-test-binary";}
class Service extends Context {Context getApplicationContext(){return this;}}
class TextUtils {static boolean isEmpty(String value){return value==null||value.isEmpty();}}
class Uri {final URI uri;Uri(String s){uri=URI.create(s);}static Uri parse(String s){return new Uri(s);}String getPath(){return uri.getPath();}}
class JSONObject {JSONObject(Map<String,Object> map){} public String toString(){return "{}";}}
class YoutubeDLRequest {final List<String> urls;final Map<String,String> options=new LinkedHashMap<>();
 YoutubeDLRequest(String url){urls=List.of(url);}YoutubeDLRequest(List<String> urls){this.urls=urls;}
 void addOption(String name){options.put(name,"");}void addOption(String name,String value){options.put(name,value);}}
class Intent {final Map<String,Object> values=new HashMap<>();Intent(Service s,Class<?> c){}void setAction(String a){}void putExtra(String k,Object v){values.put(k,v);}String getStringExtra(String k){return (String)values.get(k);}int getIntExtra(String k,int d){return values.containsKey(k)?(Integer)values.get(k):d;}long getLongExtra(String k,long d){return values.containsKey(k)?(Long)values.get(k):d;}boolean getBooleanExtra(String k,boolean d){return values.containsKey(k)?(Boolean)values.get(k):d;}}
class MediaDownloadService extends Service {
'''+constants+builder+model+'''
 static boolean isYouTubeLike(String url){return url!=null&&url.contains("youtube.com");}
 static boolean isPinterestLike(String url){return false;}
 static boolean isYouTubeTask(TaskState task){return isYouTubeLike(task.sourceUrl);}
 static boolean isPinterestTask(TaskState task){return false;}
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 static void test()throws Exception {
  MediaDownloadService service=new MediaDownloadService();Path root=Files.createTempDirectory("request-test");
  try {
   TaskState task=new TaskState();task.taskId="id";task.sourceUrl="https://youtube.com/watch?v=id";task.directUrl="https://cdn.test/videoplayback?sig=signed";task.directAudioUrl="";task.outputExt="mp4";task.formatId="137+140";task.mediaType=TYPE_VIDEO;task.userAgent="BrowserUA";task.cookieHeader="SID=test";
   var request=service.buildRequest(task,root.toFile());
   check(request.urls.equals(List.of(task.sourceUrl)),"Analysis format downloads must reopen page");
   check(request.options.get("-f").equals("137+140"),"Exact audio/video pair was modified");
   check(request.options.get("--remote-components").equals("ejs:github"),"SL.Tube EJS setup missing");
   check(request.options.get("--user-agent").equals("BrowserUA"),"Browser session UA lost");
   task.useBrowserSession=false;
   var anonymous=TaskState.fromIntent(task.toIntent(service,"resume"));
   request=service.buildRequest(anonymous,root.toFile());
   check(!anonymous.useBrowserSession,"Successful anonymous profile lost on resume");
   check(!request.options.containsKey("--user-agent") && !request.options.containsKey("--referer") && !request.options.containsKey("--add-header") && !request.options.containsKey("--cookies"),"Anonymous analysis profile became authenticated at download");
   task.useBrowserSession=true;
   task.capturedDirect=true;task.manifest=true;task.directUrl="https://cdn.test/manifest?sig=token";task.formatId="bestvideo[height=2160]+bestaudio/best[height=2160]";
   var restored=TaskState.fromIntent(task.toIntent(service,"resume"));
   check(restored.manifest && restored.capturedDirect,"Manifest flag not retained across pause/resume");
   request=service.buildRequest(restored,root.toFile());
   check(request.urls.equals(List.of(task.directUrl)),"Signed master URL with no extension was lost");
   check(!request.options.containsKey("--load-info-json"),"Manifest incorrectly treated as one HTTP file");
   check(request.options.get("-f").equals(task.formatId),"Selected HLS resolution was modified");
   task.mediaType=TYPE_AUDIO;task.outputExt="mp3";task.audioQuality=320;task.formatId="";
   request=service.buildRequest(task,root.toFile());
   check(request.options.get("--audio-format").equals("mp3") && request.options.get("--audio-quality").equals("320K"),"HLS MP3 extraction options wrong");
   task.manifest=false;task.mediaType=TYPE_VIDEO;task.directUrl="https://cdn.test/file.mkv";task.outputExt="mkv";task.formatId="";task.hasAudio=true;
   request=service.buildRequest(task,root.toFile());
   check(request.options.containsKey("--load-info-json"),"Direct MKV did not use captured plan");
   check(request.options.get("--merge-output-format").equals("mkv"),"MKV target lost");
   System.out.println("PASS production download requests: exact video+audio selector, original page refresh, session UA, EJS, signed HLS master without extension, selected 4K, pause/resume persistence, MP3 extraction, MKV plan");
  } finally {try(var files=Files.list(root)){for(Path p:files.toList())Files.delete(p);}Files.delete(root);}
 }
}
'''+''.join(parts)
with tempfile.TemporaryDirectory() as tmp:
 p=Path(tmp)/'DownloadRequestIntegrationTest.java';p.write_text(source)
 subprocess.run(['java',str(p)],check=True)
