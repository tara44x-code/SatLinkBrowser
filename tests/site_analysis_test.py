"""Runs the production generic-site routing with an in-memory engine and JSON fixture API."""
from pathlib import Path
import re,subprocess,tempfile
root=Path(__file__).resolve().parents[1];base=root/'app/src/main/java/com/satlink/geckoviewtest'
s=(base/'MainActivity.java').read_text()
methods=s[s.index('    private JSONObject executeAnalysis(String pageUrl)'):s.index('    private static String compactEngineOutput')]
score=s[s.index('    private FormatSelectionPolicy.Score extractionScore'):s.index('    private JSONObject executeYouTubeAnalysis')]
parts=[]
for name in ['SLTubeAnalysisRequest.java','EngineRuntimeOptions.java','FormatSelectionPolicy.java']:
 parts.append(re.sub(r'^package .*;\n|^import .*;\n','',(base/name).read_text(),flags=re.M))
source='''import java.util.*;import java.io.*;
class SiteAnalysisTest {
 int captureRequestSerial=1;JSONArray lastCapturedItems=new JSONArray();final List<YoutubeDLRequest> executed=new ArrayList<>();final Deque<Object> responses=new ArrayDeque<>();final Map<String,JSONObject> json=new HashMap<>();
 Context getApplicationContext(){return new Context();}
 static int numberToInt(Object v){return v instanceof Number?((Number)v).intValue():0;}
 void applySessionOptions(YoutubeDLRequest r,String url){r.addOption("--cookies","private-browser-session");}
 String strongestManifestUrl(JSONArray rows){for(int i=0;i<rows.length();i++){JSONObject v=rows.optJSONObject(i);if(v.optString("url").endsWith(".m3u8"))return v.optString("url");}return "";}
 static boolean looksLikeAdUrl(String url){return url.contains("doubleclick");}
 YoutubeDLResponse runAnalysisRequest(YoutubeDLRequest r,String id)throws Exception{executed.add(r);Object n=responses.removeFirst();if(n instanceof Exception)throw (Exception)n;return (YoutubeDLResponse)n;}
 JSONObject parseResponseJson(String out){return json.get(out);}
'''+methods+score+'''
 static void check(boolean c,String s){if(!c)throw new AssertionError(s);}
 static JSONObject result(){JSONArray rows=new JSONArray();for(int h:new int[]{360,720,1080})rows.put(new JSONObject().put("format_id","v"+h).put("url","https://cdn.test/"+h).put("vcodec","h264").put("acodec","aac").put("height",h).put("ext","mp4"));return new JSONObject().put("formats",rows);}
 public static void main(String[] args)throws Exception {
 var direct=new SiteAnalysisTest();direct.json.put("ok",result());direct.responses.add(new YoutubeDLResponse("ok"));
 var out=direct.executeAnalysis("https://www.tiktok.com/@u/video/123");
 check(direct.executed.size()==1 && direct.executed.get(0).url.contains("/video/123"),"Original post skipped");
 check(out.optJSONArray("formats").length()==3 && !out.optBoolean("_satlink_browser_session",true),"Formats/profile lost");
 check(!direct.executed.get(0).options.containsKey("--format") && !direct.executed.get(0).options.containsKey("--user-agent"),"Site request has stale filtering/UA");
 var auth=new SiteAnalysisTest();auth.json.put("ok",result());auth.responses.add(new IOException("auth required"));auth.responses.add(new YoutubeDLResponse("ok"));
 out=auth.executeAnalysis("https://site.test/post");check(out.optBoolean("_satlink_browser_session",false) && auth.executed.get(1).options.containsKey("--cookies"),"Session retry missing");
 var embedded=new SiteAnalysisTest();embedded.lastCapturedItems.put(new JSONObject().put("type","video").put("url","https://cdn.test/master.m3u8"));embedded.json.put("ok",result());embedded.responses.add(new IOException("unsupported"));embedded.responses.add(new IOException("unsupported"));embedded.responses.add(new YoutubeDLResponse("ok"));
 out=embedded.executeAnalysis("https://site.test/post");check(embedded.executed.get(2).url.endsWith("master.m3u8") && out.optJSONArray("formats").length()==3,"Master fallback lost variants");
 var passive=new SiteAnalysisTest();passive.lastCapturedItems.put(new JSONObject().put("type","video").put("url","https://cdn.test/low.mp4"));passive.responses.add(new IOException("unsupported"));passive.responses.add(new IOException("unsupported"));
 try {passive.executeAnalysis("https://site.test/post");throw new AssertionError("Unexpected success");}catch(IOException expected){}
 check(passive.executed.size()==2,"Single captured CDN file replaced all passive renditions");
 var generic=new SiteAnalysisTest();generic.json.put("file",new JSONObject().put("url","https://cdn.test/movie.mp4").put("ext","mp4"));generic.responses.add(new YoutubeDLResponse("file"));
 out=generic.executeAnalysis("https://site.test/post");check(out.optString("vcodec").equals("unknown") && out.optString("format_id").equals("best") && out.optString("acodec").isEmpty(),"Generic container disappeared or sound was invented");
 var interrupted=new SiteAnalysisTest();Thread.currentThread().interrupt();try{interrupted.executeAnalysis("https://site.test/");throw new AssertionError("Interrupted request ran");}catch(InterruptedException expected){}finally{Thread.interrupted();}
 check(interrupted.executed.isEmpty(),"Canceled page executed an engine request");
 System.out.println("PASS production site routing: original post, all formats, anonymous profile, session retry, master fallback, passive variant preservation, cancellation");
 }
}
class Context{ApplicationInfo getApplicationInfo(){return new ApplicationInfo();}}class ApplicationInfo{String nativeLibraryDir="/test/no/runtime";}
class TextUtils{static boolean isEmpty(String s){return s==null||s.isEmpty();}}
class YoutubeDLRequest{final String url;final Map<String,String>options=new LinkedHashMap<>();YoutubeDLRequest(String u){url=u;}void addOption(String k){options.put(k,"");}void addOption(String k,String v){options.put(k,v);}}
class YoutubeDLResponse{String out;YoutubeDLResponse(String s){out=s;}String getOut(){return out;}int getExitCode(){return 0;}}
class JSONObject{final Map<String,Object>data=new HashMap<>();JSONObject put(String k,Object v){data.put(k,v);return this;}Object opt(String k){return data.get(k);}String optString(String k){return optString(k,"");}String optString(String k,String d){Object v=data.get(k);return v==null?d:v.toString();}boolean optBoolean(String k,boolean d){Object v=data.get(k);return v instanceof Boolean?(Boolean)v:d;}JSONArray optJSONArray(String k){return (JSONArray)data.get(k);}}
class JSONArray{final List<JSONObject>rows=new ArrayList<>();JSONArray put(JSONObject v){rows.add(v);return this;}int length(){return rows.size();}JSONObject optJSONObject(int i){return rows.get(i);}}
'''+''.join(parts)
with tempfile.TemporaryDirectory() as tmp:
 p=Path(tmp)/'SiteAnalysisTest.java';p.write_text(source);subprocess.run(['java',str(p)],check=True)
