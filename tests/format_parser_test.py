"""Execute the complete production format parser with small read-only JSON API substitutes."""
from pathlib import Path
import re, subprocess, tempfile
root=Path(__file__).resolve().parents[1]
base=root/'app/src/main/java/com/satlink/geckoviewtest'
s=(base/'MainActivity.java').read_text()
parser=s[s.index('    private FormatCollection parseFormats'):s.index('    private boolean hasStartedCaptureDownload')]
policy=s[s.index('    private static boolean isSupportedModernVideo'):s.index('    private GradientDrawable roundedBackground')]
models=s[s.index('    private static final class FormatCollection'):s.index('    private static String formatBytes')]
selection=(base/'FormatSelectionPolicy.java').read_text();selection=re.sub(r'^package .*;\n|^import .*;\n','',selection,flags=re.M)
source='''import java.util.*;
class FormatParserTest {
 String pendingPageTitle="Title", pendingPageUrl="https://youtube.com/watch?v=current";
 static class TextUtils {static boolean isEmpty(String s){return s==null||s.isEmpty();}}
 static class JSONObject {
  static final Object NULL=new Object(); final Map<String,Object> data=new LinkedHashMap<>();
  JSONObject put(String k,Object v){data.put(k,v);return this;}
  Object opt(String k){return data.get(k);}
  boolean optBoolean(String k,boolean fallback){Object v=data.get(k);return v instanceof Boolean?(Boolean)v:fallback;}
  String optString(String k){return optString(k,"");}
  String optString(String k,String fallback){Object v=data.get(k);return v==null||v==NULL?fallback:String.valueOf(v);}
  JSONArray optJSONArray(String k){Object v=data.get(k);return v instanceof JSONArray?(JSONArray)v:null;}
 }
 static class JSONArray {
  final List<Object> data=new ArrayList<>();
  JSONArray put(Object v){data.add(v);return this;} int length(){return data.size();}
  JSONObject optJSONObject(int i){Object v=i<data.size()?data.get(i):null;return v instanceof JSONObject?(JSONObject)v:null;}
 }
'''+parser+policy+models+'''
 static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
 static JSONObject video(String id,String ext,String codec,int height,boolean audio){return new JSONObject().put("format_id",id).put("url","https://cdn.test/"+id+"."+ext).put("ext",ext).put("vcodec",codec).put("acodec",audio?"mp4a.40.2":"none").put("height",height).put("width",height*16/9).put("fps",60).put("tbr",3000).put("filesize",1000000);}
 public static void main(String[] args){
  FormatParserTest parser=new FormatParserTest(); JSONArray formats=new JSONArray();
  formats.put(video("360","mp4","avc1",360,true));
  formats.put(video("1080","mp4","avc1",1080,false));
  formats.put(video("2160","webm","vp9",2160,false));
  formats.put(video("4320","mp4","av01",4320,false));
  formats.put(video("mkv","mkv","hevc",720,true));
  formats.put(video("ts","ts","avc1",480,true));
  formats.put(new JSONObject().put("format_id","140").put("url","https://cdn.test/audio.m4a").put("ext","m4a").put("vcodec","none").put("acodec","mp4a.40.2").put("abr",128).put("filesize",10000));
  formats.put(video("sb","mhtml","images",9000,false));
  formats.put(new JSONObject().put("format_id","invalid/id").put("vcodec","avc1").put("ext","mp4").put("height",10000));
  var parsed=parser.parseFormats(new JSONObject().put("title","Fixture").put("duration",120).put("formats",formats));
  check(parsed.videos.size()==6,"Lost real containers or included storyboards/unresolved entries");
  check(parsed.videos.get(0).height==4320,"Highest available resolution is not first");
  check(parsed.videos.get(0).videoCodec.equals("AV1"),"8K AV1 was filtered out");
  check(parsed.videos.get(0).directAudioUrl.equals("https://cdn.test/audio.m4a"),"Separate audio was not paired");
  check(parsed.videos.get(0).formatId.equals("4320+140"),"Exact paired audio selector missing");
  check(parsed.videos.stream().filter(v->v.height==2160).findFirst().get().ext.equals("mkv"),"Incompatible WebM + AAC needs MKV");
  check(parsed.audios.size()==4,"MP3 options missing");
  check(parsed.audios.get(0).audioQualityKbps==320,"MP3 conversion targets are not ordered");
  var progressive=parser.parseFormats(new JSONObject().put("duration",60).put("formats",new JSONArray().put(video("18","mp4","avc1",360,true))));
  check(progressive.audios.size()==4,"Progressive video with sound must provide MP3 extraction");
  var tikTok=parser.parseFormats(new JSONObject().put("extractor_key","TikTok").put("webpage_url","https://www.tiktok.com/@u/video/123").put("formats",new JSONArray()
    .put(video("high","mp4","h264",1080,true)).put(video("low","mp4","h264",360,true))
    .put(video("silent","mp4","h265",720,false))
    .put(new JSONObject().put("format_id","audio").put("url","https://cdn.test/background.m4a").put("vcodec","none").put("acodec","aac").put("ext","m4a"))));
  check(tikTok.videos.size()==3 && tikTok.videos.get(0).height==1080 && tikTok.videos.get(2).height==360,"TikTok max/min lost");
  check(tikTok.audios.get(0).directUrl.equals("https://cdn.test/high.mp4") && tikTok.audios.get(0).formatId.equals("high"),"TikTok MP3 substituted background music for full soundtrack");
  check(tikTok.videos.get(1).directAudioUrl==null,"TikTok video-only variant must not receive unrelated music");
  var onlyImages=parser.parseFormats(new JSONObject().put("formats",new JSONArray().put(video("sb","mhtml","images",9000,false))));
  check(onlyImages.videos.isEmpty() && onlyImages.audios.isEmpty(),"Images must not count as downloadable media");
  JSONArray allFormats=new JSONArray();
  for(int i=0;i<37;i++)allFormats.put(video("variant-"+i,i%2==0?"mp4":"webm",i%2==0?"av01":"vp9",i<3?2160:1080,false));
  allFormats.put(new JSONObject().put("format_id","audio").put("url","https://cdn.test/audio.m4a").put("ext","m4a").put("vcodec","none").put("acodec","mp4a.40.2"));
  var all=parser.parseFormats(new JSONObject().put("_satlink_browser_session",false).put("formats",allFormats));
  check(all.videos.size()==37,"Independent yt-dlp IDs collapsed into fewer cards");
  check(all.videos.get(0).height==2160 && !all.videos.get(0).useBrowserSession,"Anonymous 4K profile was not retained");
  check(!all.audios.get(0).useBrowserSession,"MP3 switched the successful profile");
  var fragment=parser.parseFormats(new JSONObject().put("formats",new JSONArray().put(video("fragment-4k","mp4","av01",2160,false).put("url","").put("protocol","http_dash_segments"))));
  check(fragment.videos.size()==1 && fragment.videos.get(0).sourceUrl.contains("youtube.com"),"Fragment-backed format must download by page and ID");
  var estimated=parser.parseFormats(new JSONObject().put("formats",new JSONArray().put(video("approx","mp4","avc1",720,true).put("filesize",0).put("filesize_approx",200000))));
  check(estimated.videos.get(0).sizeEstimated,"filesize_approx must not be presented as exact");
  var protectedRows=parser.parseFormats(new JSONObject().put("formats",new JSONArray().put(video("drm","mp4","avc1",2160,true).put("has_drm",true))));
  check(protectedRows.videos.isEmpty(),"Protected rows must not be download options");
  List<FormatSelectionPolicy.Format> previewOnly=List.of(new FormatSelectionPolicy.Format("https://cdn.test/sb","mhtml","images","none",9000));
  check(!FormatSelectionPolicy.score(previewOnly).hasMedia(),"Storyboard-only extraction reported success");
  var low=FormatSelectionPolicy.score(List.of(new FormatSelectionPolicy.Format("https://cdn.test/low","mp4","avc1","mp4a",360)));
  var high=FormatSelectionPolicy.score(List.of(new FormatSelectionPolicy.Format("https://cdn.test/high","mp4","av01","none",4320)));
  check(high.compareTo(low)>0,"Fallback selection must prefer the highest available video");
  System.out.println("PASS full production parser: 8K > 4K > 1080p, AV1, MKV/TS, audio pairing, four MP3 targets, progressive audio, storyboard exclusion, highest-resolution selection");
 }
}
'''+selection
with tempfile.TemporaryDirectory() as tmp:
    target=Path(tmp)/'FormatParserTest.java';target.write_text(source)
    subprocess.run(['java',str(target)],check=True)
