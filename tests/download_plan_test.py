"""Run the actual download-plan and runtime-option code under a JDK with tiny API stubs."""
from pathlib import Path
import tempfile, subprocess, re
root=Path(__file__).resolve().parents[1]
base=root/'app/src/main/java/com/satlink/geckoviewtest'
parts=[]
imports={'import java.util.*;','import java.io.*;','import java.nio.file.*;'}
for filename in ['CapturedDownloadPlan.java','EngineRuntimeOptions.java']:
    s=(base/filename).read_text()
    imports.update(re.findall(r'^import java\..*;$',s,re.M))
    s=re.sub(r'^package .*;\n|^import .*;\n','',s,flags=re.M)
    parts.append(s)
source='\n'.join(sorted(imports))+'\n'+ '\n'.join(parts)+'''
class Context {
 final ApplicationInfo info=new ApplicationInfo();
 ApplicationInfo getApplicationInfo(){return info;}
}
class ApplicationInfo {String nativeLibraryDir;}
class YoutubeDLRequest {
 final Map<String,String> options=new LinkedHashMap<>();
 void addOption(String name,String value){options.put(name,value);}
}
class DownloadPlanTest {
 static void check(boolean condition,String message){if(!condition) throw new AssertionError(message);}
 @SuppressWarnings("unchecked")
 public static void main(String[] args) throws Exception {
  String video="https://r1.googlevideo.com/videoplayback?itag=137&mime=video%2Fmp4&sig=signed";
  String audio="https://r1.googlevideo.com/videoplayback?itag=140&mime=audio%2Fmp4&sig=signed";
  var root=CapturedDownloadPlan.create("id","Title",video,audio,"137","mp4","H.264","AAC",false,false,"UA","https://youtube.com/watch?v=id");
  var formats=(List<Map<String,Object>>)root.get("formats");
  check(formats.size()==2,"Adaptive source must contain both streams");
  check(formats.get(0).get("format_id").equals("137"),"Preserve selected video format");
  check(formats.get(0).get("url").equals(video),"Do not replace signed video URL with failed page extraction");
  check(formats.get(0).get("acodec").equals("none"),"Video-only stream is not muxed audio");
  check(formats.get(1).get("ext").equals("m4a"),"Compatible captured audio extension");
  check(formats.get(1).get("vcodec").equals("none"),"Audio-only stream must not become video");
  root=CapturedDownloadPlan.create("id","Title","https://cdn.test/movie.mp4","","","mp3","H.264","AAC",true,false,"","");
  formats=(List<Map<String,Object>>)root.get("formats");
  check(formats.size()==1,"Progressive file needs no invented second audio URL");
  check(formats.get(0).get("ext").equals("mp4"),"MP3 extraction input remains MP4");
  check(formats.get(0).get("acodec").equals("mp4a.40.2"),"Progressive audio is available to extraction");
  root=CapturedDownloadPlan.create("id","Title",audio,"","","mp3","","AAC",true,true,"","");
  formats=(List<Map<String,Object>>)root.get("formats");
  check(formats.get(0).get("vcodec").equals("none"),"Captured audio remains audio");
  try {CapturedDownloadPlan.create("id","Title","blob:unsafe","","","mp4","","",false,false,"","");throw new AssertionError("blob accepted");} catch(IllegalArgumentException expected){}
  Path temp=Files.createTempDirectory("runtime-test-");
  try {
   Context context=new Context();context.info.nativeLibraryDir=temp.toString();
   YoutubeDLRequest request=new YoutubeDLRequest();EngineRuntimeOptions.apply(context,request);
   check(!request.options.containsKey("--js-runtimes"),"Missing runtime must not be advertised as a PATH command");
   File executable=temp.resolve("libqjs.so").toFile();executable.createNewFile();executable.setExecutable(true);
   request=new YoutubeDLRequest();EngineRuntimeOptions.apply(context,request);
   check(request.options.get("--js-runtimes").equals("quickjs:"+executable.getAbsolutePath()),"QuickJS must use extracted binary path");
   check(request.options.get("--remote-components").equals("ejs:github"),"Updated engine can request matching challenge scripts");
   executable.delete();
  } finally {Files.delete(temp);}
  System.out.println("PASS download plans: adaptive merge, direct audio, progressive MP3 input, signed URLs, native QuickJS path");
 }
}
'''
main_at=source.index('class DownloadPlanTest {')
header_end=max(match.end() for match in re.finditer(r'^import .*;',source,re.M))
source=source[:header_end]+'\n'+source[main_at:]+'\n'+source[header_end:main_at]
with tempfile.TemporaryDirectory() as tmp:
    target=Path(tmp)/'DownloadPlanTest.java';target.write_text(source)
    subprocess.run(['java',str(target)],check=True)
