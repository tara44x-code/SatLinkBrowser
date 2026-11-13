"""Compare actual SL.Tube option literals, then execute production YouTube routing."""
from pathlib import Path
import re,subprocess,tempfile
root=Path(__file__).resolve().parents[1];base=root/'app/src/main/java/com/satlink/geckoviewtest'
# Retained independent reference snapshots make this test runnable from the delivered project.
ref=root/'tests/reference'
if not all((ref/name).is_file() for name in ['DownloadRequestFactory.kt','VrkaDownloadManager.kt']):
 print('SKIP SL.Tube parity: reference snapshots are not part of this repository');raise SystemExit(0)
expected={}
for name in ['DownloadRequestFactory.kt','VrkaDownloadManager.kt']:
 s=(ref/name).read_text()
 if name.startswith('DownloadRequest'):
  blocks=[s[s.index('    private fun addJsRuntime'):s.index('    fun info')],s[s.index('    fun info'):s.index('    fun download')]]
 else:blocks=[s[s.index('    suspend fun analyze'):s.index('    fun cancelAnalysis')]]
 for block in blocks:
  for key,value in re.findall(r'addOption\("([^"]+)"(?:,\s*"([^"]*)")?\)',block):
   if '$' not in value:expected[key]=value
parts=[]
for name in ['SLTubeAnalysisRequest.java','EngineRuntimeOptions.java','FormatSelectionPolicy.java']:
 s=(base/name).read_text();s=re.sub(r'^package .*;\n|^import .*;\n','',s,flags=re.M);parts.append(s)
s=(base/'MainActivity.java').read_text();routing=s[s.index('    private FormatSelectionPolicy.Score extractionScore'):s.index('    private void stopActiveAnalysis')]
import json
expected_java=''.join('expected.put('+json.dumps(k)+','+json.dumps(v)+');' for k,v in expected.items())
source='''import java.util.*;import java.io.*;
class SLTubeAnalysisTest {
 int captureRequestSerial=1;static final String TAG="test";
 final Context context=new Context();final List<YoutubeDLRequest> executed=new ArrayList<>();final Deque<Object> responses=new ArrayDeque<>();final Map<String,JSONObject> json=new HashMap<>();
 Context getApplicationContext(){return context;}
 static int numberToInt(Object value){return value instanceof Number?((Number)value).intValue():0;}
 void applySessionOptions(YoutubeDLRequest request,String page){request.addOption("--cookies","test-private-cookie-file");}
 YoutubeDLResponse runAnalysisRequest(YoutubeDLRequest request,String id)throws Exception{executed.add(request);Object next=responses.removeFirst();if(next instanceof Exception)throw (Exception)next;return (YoutubeDLResponse)next;}
 JSONObject parseResponseJson(String out){return json.get(out);}
'''+routing+'''
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 static JSONObject response(int height,int count){JSONArray formats=new JSONArray();for(int i=0;i<count;i++)formats.put(new JSONObject().put("format_id","v-"+i).put("url","https://cdn.test/v"+i).put("vcodec","av01").put("acodec","none").put("height",height).put("ext","mp4"));return new JSONObject().put("formats",formats);}
 public static void main(String[] args)throws Exception{
  Context context=new Context();YoutubeDLRequest request=SLTubeAnalysisRequest.create(context,"https://m.youtube.com/watch?v=KxV33MvXzBw");Map<String,String> expected=new LinkedHashMap<>();
'''+expected_java+'''
  check(request.options.equals(expected),"Request differs from actual SL.Tube direct analysis flags: "+request.options.keySet());
  check(!request.options.containsKey("--extractor-args") && !request.options.containsKey("--format") && !request.options.containsKey("--cookies") && !request.options.containsKey("--user-agent"),"Direct request has extra filtering or browser session");
  var direct=new SLTubeAnalysisTest();direct.json.put("high",response(2160,37));direct.responses.add(new YoutubeDLResponse("high"));
  var result=direct.executeYouTubeAnalysis("https://m.youtube.com/watch?v=KxV33MvXzBw");
  check(direct.executed.size()==1 && !result.optBoolean("_satlink_browser_session",true),"Successful direct 4K unexpectedly retried with browser cookies");
  check(result.optJSONArray("formats").length()==37,"Production routing reduced 37 formats");
  var recovery=new SLTubeAnalysisTest();recovery.json.put("low",response(360,1));recovery.json.put("high",response(2160,37));recovery.responses.add(new YoutubeDLResponse("low"));recovery.responses.add(new YoutubeDLResponse("high"));
  result=recovery.executeYouTubeAnalysis("https://m.youtube.com/watch?v=KxV33MvXzBw");
  check(recovery.executed.size()==2 && recovery.executed.get(1).options.containsKey("--cookies"),"Browser-session recovery missing");
  check(result.optBoolean("_satlink_browser_session",false) && result.optJSONArray("formats").length()==37,"Higher recovery response or profile lost");
  var failedRecovery=new SLTubeAnalysisTest();failedRecovery.json.put("low",response(360,1));failedRecovery.responses.add(new YoutubeDLResponse("low"));failedRecovery.responses.add(new IOException("temporary failure"));
  result=failedRecovery.executeYouTubeAnalysis("https://m.youtube.com/watch?v=KxV33MvXzBw");check(!result.optBoolean("_satlink_browser_session",true),"Failed recovery overwrote usable direct response");
  var fragments=FormatSelectionPolicy.score(List.of(new FormatSelectionPolicy.Format("313","","webm","vp9","none",2160)));
  check(fragments.maxHeight==2160,"Fragment-backed format ignored by response selection");
  System.out.println("PASS exact SL.Tube request parity and production routing: anonymous first, 37 synthetic video IDs preserved, 4K retained, session recovery, correct download profile, no forced client/filter, fragment-backed format score");
 }
}
class Context {ApplicationInfo getApplicationInfo(){return new ApplicationInfo();}}
class ApplicationInfo {String nativeLibraryDir="/test/no/native/runtime";}
class YoutubeDLRequest {final Map<String,String> options=new LinkedHashMap<>();YoutubeDLRequest(String url){}void addOption(String k){options.put(k,"");}void addOption(String k,String v){options.put(k,v);}}
class YoutubeDLResponse {final String out;YoutubeDLResponse(String s){out=s;}String getOut(){return out;}int getExitCode(){return 0;}}
class Log {static void i(String tag,String s){}static void w(String tag,String s,Exception e){}}
class JSONObject {final Map<String,Object> values=new HashMap<>();JSONObject put(String k,Object v){values.put(k,v);return this;}Object opt(String k){return values.get(k);}String optString(String k){return optString(k,"");}String optString(String k,String d){Object v=values.get(k);return v==null?d:v.toString();}boolean optBoolean(String k,boolean d){Object v=values.get(k);return v instanceof Boolean?(Boolean)v:d;}JSONArray optJSONArray(String k){return (JSONArray)values.get(k);}}
class JSONArray {final List<JSONObject> rows=new ArrayList<>();JSONArray put(JSONObject v){rows.add(v);return this;}int length(){return rows.size();}JSONObject optJSONObject(int i){return rows.get(i);}}
'''+''.join(parts)
with tempfile.TemporaryDirectory() as tmp:
 p=Path(tmp)/'SLTubeAnalysisTest.java';p.write_text(source);subprocess.run(['java',str(p)],check=True)
