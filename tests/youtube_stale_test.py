"""Run the real YouTube stale-result guard from MainActivity with small substitutes."""
from pathlib import Path
import subprocess, tempfile
root = Path(__file__).resolve().parents[1]
s = (root / 'app/src/main/java/com/satlink/geckoviewtest/MainActivity.java').read_text()
start = s.index('    private JSONArray currentVideoItems')
end = s.index('    private void showCapturedFallback')
guard = s[start:end]
start = s.index('    private static boolean isYouTubeUrl')
end = s.index('    private static boolean looksLikeYouTubeThrottle')
youtube = s[start:end]
test = r'''
import java.util.*;import java.util.concurrent.atomic.AtomicBoolean;
class StaleTest {
 String pendingPageUrl="",lastAnalysisError="";AtomicBoolean engineFailed=new AtomicBoolean();
''' + guard + youtube + r'''
 static void check(boolean v,String m){if(!v)throw new AssertionError(m);}
 static JSONObject item(String id){var o=new JSONObject();o.map.put("videoId",id);return o;}
 public static void main(String[] a){
  var t=new StaleTest();var items=new JSONArray();items.add(item("OLD"));items.add(item("NEW"));items.add(new JSONObject());
  t.pendingPageUrl="https://m.youtube.com/watch?v=NEW";
  check(t.currentVideoItems(items)==null,"Page data shown before the current video was analysed");
  t.lastAnalysisError="failed";var kept=t.currentVideoItems(items);
  check(kept.length()==1 && kept.optJSONObject(0).optString("videoId").equals("NEW"),"Previous video's formats kept after failure");
  t.lastAnalysisError="";t.engineFailed.set(true);check(t.currentVideoItems(items).length()==1,"Engine failure fallback lost current video");
  t.pendingPageUrl="https://site.test/video";check(t.currentVideoItems(items)==items,"Other sites changed");
  System.out.println("PASS YouTube stale guard: no previous-video formats while analysing, only current video id after failure, other sites unchanged");
 }
}
class JSONObject{Map<String,String> map=new HashMap<>();String optString(String k){return map.getOrDefault(k,"");}}
class JSONArray{List<JSONObject> l=new ArrayList<>();void add(JSONObject o){l.add(o);}int length(){return l.size();}JSONObject optJSONObject(int i){return l.get(i);}JSONArray put(JSONObject o){l.add(o);return this;}}
class TextUtils{static boolean isEmpty(String s){return s==null||s.isEmpty();}}
class Uri{String u;static Uri parse(String s){var x=new Uri();x.u=s;return x;}String getQueryParameter(String k){int i=u.indexOf(k+"=");if(i<0)return null;String v=u.substring(i+k.length()+1);int e=v.indexOf('&');return e<0?v:v.substring(0,e);}}
'''
with tempfile.TemporaryDirectory() as tmp:
    target = Path(tmp) / 'StaleTest.java'
    target.write_text(test)
    subprocess.run(['java', str(target)], check=True)
