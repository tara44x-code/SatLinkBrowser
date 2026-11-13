from pathlib import Path
import re,subprocess,tempfile
base=Path(__file__).resolve().parents[1]/'app/src/main/java/com/satlink/geckoviewtest'
parts=[]
for name in ['GeneralFilePolicy.java','GeneralFileDownloads.java']:
 parts.append(re.sub(r'^package .*;\n|^import .*;\n','',(base/name).read_text(),flags=re.M))
source='''import java.util.*;import java.util.regex.*;import java.net.*;import java.nio.charset.*;import java.io.*;
class GeneralFilesTest {
 static void check(boolean value,String text){if(!value)throw new AssertionError(text);}
 public static void main(String[] args) {
 for(String ext:new String[]{"zip","rar","7z","torrent","exe","msi","apk","apks","xapk","iso","dmg","deb","docx","pdf"})check(GeneralFilePolicy.directFile("https://site.test/file."+ext+"?token=abc%2Bdef"),"Missing "+ext);
 for(String ext:new String[]{"mp4","mkv","ts","webm","mp3","m4a","m3u8","mpd","bin","html"})check(!GeneralFilePolicy.directFile("https://site.test/file."+ext),"Unexpected navigation interception "+ext);
 check(!GeneralFilePolicy.directFile("javascript:alert(1)") && !GeneralFilePolicy.directFile("file:///tmp/file.apk"),"Invalid URL accepted");
 check(!GeneralFilePolicy.directFile("https://site.test/watch?file=installer.apk"),"Page query mistaken for download");
 check(GeneralFilePolicy.media("https://cdn.test/file","video/mp4","") && GeneralFilePolicy.media("https://cdn.test/file","","attachment; filename=video.mkv"),"Media response changed");
 check(!GeneralFilePolicy.directFile("https://cdn.test/file.zip?mime=video%2Fmp4"),"Declared media intercepted");
 check(!GeneralFilePolicy.directFile("https://r1.googlevideo.com/file.zip"),"YouTube stream intercepted");
 check(GeneralFilePolicy.fileName("https://site.test/download","attachment; filename=fallback.apk; filename*=UTF-8''%D8%A8%D8%B1%D9%86%D8%A7%D9%85%D8%AC.apk","application/octet-stream").equals("برنامج.apk"),"Arabic RFC5987 filename lost");
 check(GeneralFilePolicy.fileName("https://site.test/A+B%20C.zip?signature=a%2Fb","","application/zip").equals("A+B C.zip"),"Signed filename decoding changed plus");
 check(GeneralFilePolicy.fileName("https://site.test/","","application/vnd.android.package-archive").equals("download.apk"),"MIME fallback missing");
 check(GeneralFilePolicy.mime("app.apk","application/octet-stream").equals("application/vnd.android.package-archive"),"APK MIME not recognized");
 check(!GeneralFilePolicy.safeName("../../folder/file.apk").contains("/"),"Unsafe output path");
 check(GeneralFilePolicy.safeName("برنامج".repeat(60)+".apk").getBytes(StandardCharsets.UTF_8).length<=180 && GeneralFilePolicy.safeName("برنامج".repeat(60)+".apk").endsWith(".apk"),"Long UTF8 name lost extension");
 String cookies=".site.test\\tTRUE\\t/\\tTRUE\\t0\\tsession\\tABC\\nsite.test\\tFALSE\\t/private\\tTRUE\\t0\\tsecret\\tDEF\\nother.test\\tFALSE\\t/\\tFALSE\\t0\\tother\\tNO\\nsite.test\\tFALSE\\t/\\tTRUE\\t1\\told\\tEXPIRED\\n";
 check(GeneralFilePolicy.cookieHeader(cookies,"https://site.test/private/file.apk",100).equals("secret=DEF; session=ABC"),"Cookie scope/order wrong");
 check(GeneralFilePolicy.cookieHeader(cookies,"https://cdn.site.test/app.apk",100).equals("session=ABC"),"Cookie subdomain scoping wrong");
 check(GeneralFilePolicy.cookieHeader(cookies,"http://site.test/app.apk",100).isEmpty(),"Secure cookie sent over HTTP");
 check(GeneralFilePolicy.cookieHeader(cookies,"https://site.test.fake/app.apk",100).isEmpty(),"Cookie leaked to wrong host");
 var activity=new Activity();var files=new GeneralFileDownloads(activity);String url="https://site.test/app.apk?sig=a%2Bb%2Fc";
 check(files.navigation(url,"https://site.test/page","Browser-UA",cookies),"APK navigation not handled");
 files.navigation(url,"https://site.test/page","Browser-UA",cookies);check(AlertDialog.created==1,"Duplicate dialog");
 AlertDialog.last.accept();check(activity.manager.requests.size()==1,"No native download request");
 var request=activity.manager.requests.get(0);check(request.uri.value.equals(url),"Signed URL altered");
 check(request.mime.equals("application/vnd.android.package-archive") && request.path.equals("SatLink/app.apk"),"APK path/MIME wrong");
 check(request.headers.get("Cookie").equals("session=ABC") && request.headers.get("Referer").equals("https://site.test/page") && request.headers.get("User-Agent").equals("Browser-UA"),"Browser session lost");
 check(request.metered && request.roaming && request.visibility==1,"Native background notification missing");
 files.navigation(url,"https://site.test/page","Browser-UA",cookies);AlertDialog.last.accept();check(activity.manager.requests.get(1).path.equals("SatLink/app (1).apk"),"Running filename collision");
 check(!files.navigation("https://youtube.com/watch?v=abc","","",""),"Watch page intercepted");
 check(!files.navigation("https://cdn.test/movie.mp4","","",""),"Video intercepted");
 Build.VERSION.SDK_INT=28;activity.permission=-1;
 files.navigation("https://site.test/archive.zip","https://site.test/page","",cookies);AlertDialog.last.accept();check(activity.permissionRequest==4093 && activity.manager.requests.size()==2,"Legacy storage didn't defer download");
 activity.permission=0;files.permissionResult(4093,new int[]{0});check(activity.manager.requests.size()==3,"Permission grant didn't continue pending file");
 Build.VERSION.SDK_INT=36;
 check(files.navigation("magnet:?xt=urn:btih:test","","",""),"Magnet not handed off");AlertDialog.last.accept();check(activity.opened!=null && activity.opened.uri.value.startsWith("magnet:"),"Torrent app handoff missing");
 activity.noTorrentApp=true;check(files.navigation("magnet:?xt=urn:btih:copy","","",""),"Magnet not offered again");AlertDialog.last.accept();check(activity.clipboard.clip!=null && activity.clipboard.clip.text.equals("magnet:?xt=urn:btih:copy"),"Magnet link not kept when no torrent app exists");
 for(String extra:new String[]{"game.nsp","disk.vhd","pack.apkm","comic.cbz","font.ttf","movie.srt"})check(GeneralFilePolicy.directFile("https://files.test/"+extra),"Extended type not offered: "+extra);
 check(!GeneralFilePolicy.directFile("https://files.test/clip.mp4"),"Video treated as a general file");
 System.out.println("PASS actual general-file policy/controller: archive/program/APK/torrent, media exclusions, signed URLs, RFC5987 Arabic names, MIME, scoped cookies, duplicate dialog/name, native download/notification, permission continuation, magnet handoff");
 }
}
class Activity extends Context {int permission=0,permissionRequest;DownloadManager manager=new DownloadManager();Intent opened;boolean isFinishing(){return false;}boolean isDestroyed(){return false;}int checkSelfPermission(String s){return permission;}void requestPermissions(String[] p,int c){permissionRequest=c;}ClipboardManager clipboard=new ClipboardManager();boolean noTorrentApp;Object getSystemService(String s){return Context.CLIPBOARD_SERVICE.equals(s)?clipboard:manager;}Resolver getContentResolver(){return new Resolver();}void startActivity(Intent i){if(noTorrentApp)throw new RuntimeException("no app");opened=i;}}
class ClipboardManager{ClipData clip;void setPrimaryClip(ClipData c){clip=c;}}
class ClipData{String text;static ClipData newPlainText(String l,String t){var c=new ClipData();c.text=t;return c;}}
class Context {static String DOWNLOAD_SERVICE="download",CLIPBOARD_SERVICE="clipboard";}
class Manifest {static class permission{static String WRITE_EXTERNAL_STORAGE="storage";}}
class PackageManager{static int PERMISSION_GRANTED=0;}
class Build{static class VERSION{static int SDK_INT=36;}static class VERSION_CODES{static int P=28,Q=29;}}
class Uri{String value;Uri(String s){value=s;}static Uri parse(String s){return new Uri(s);}}
class Environment{static String DIRECTORY_DOWNLOADS="Download";static File getExternalStoragePublicDirectory(String s){return new File("/not-used");}}
class MediaStore{static class Downloads{static Uri EXTERNAL_CONTENT_URI=new Uri("content:test");static String _ID="id",DISPLAY_NAME="name",RELATIVE_PATH="path";}}
class Resolver{Cursor query(Uri u,String[] p,String s,String[] a,String o){return new Cursor(List.of());}}
class Cursor implements AutoCloseable{List<String> rows;int index=-1;Cursor(List<String> r){rows=r;}int getColumnIndexOrThrow(String s){return 0;}boolean moveToNext(){return ++index<rows.size();}boolean moveToFirst(){return !rows.isEmpty();}String getString(int i){return rows.get(index);}public void close(){}}
class DownloadManager{static String COLUMN_TITLE="title";List<Request>requests=new ArrayList<>();long enqueue(Request r){requests.add(r);return requests.size();}Cursor query(Query q){return new Cursor(requests.stream().map(r->r.title).toList());}static class Query{}static class Request{static int VISIBILITY_VISIBLE_NOTIFY_COMPLETED=1;Uri uri;String title,mime,path;int visibility;boolean metered,roaming;Map<String,String>headers=new HashMap<>();Request(Uri u){uri=u;}void setTitle(String v){title=v;}void setDescription(String v){}void setMimeType(String v){mime=v;}void setAllowedOverMetered(boolean v){metered=v;}void setAllowedOverRoaming(boolean v){roaming=v;}void setNotificationVisibility(int v){visibility=v;}void setDestinationInExternalPublicDir(String d,String p){path=p;}void addRequestHeader(String k,String v){headers.put(k,v);}}}
class Intent{static String ACTION_VIEW="VIEW";Uri uri;Intent(String a,Uri u){uri=u;}}
class AlertDialog{static AlertDialog last;static int created;Click positive;Dismiss dismiss;interface Click{void onClick(Object d,int w);}interface Dismiss{void onDismiss(Object d);}void setOnDismissListener(Dismiss d){dismiss=d;}void show(){}void accept(){positive.onClick(null,0);if(dismiss!=null)dismiss.onDismiss(null);}static class Builder{Click positive;Builder(Activity a){}Builder setTitle(String s){return this;}Builder setMessage(String s){return this;}Builder setNegativeButton(String s,Click c){return this;}Builder setPositiveButton(String s,Click c){positive=c;return this;}AlertDialog create(){created++;last=new AlertDialog();last.positive=positive;return last;}}}
class Toast{static int LENGTH_LONG=1;static Toast makeText(Activity a,String s,int t){return new Toast();}void show(){}}
'''+''.join(parts)
with tempfile.TemporaryDirectory() as tmp:
 target=Path(tmp)/'GeneralFilesTest.java';target.write_text(source);subprocess.run(['java',str(target)],check=True)
