from pathlib import Path
import re,subprocess,tempfile
root=Path(__file__).resolve().parents[1];base=root/'app/src/main/java/com/satlink/geckoviewtest'
source=(base/'MainActivity.java').read_text()
start=source.index('    private void refreshDownloads()');end=source.index('    private View generalDownloadCard',start)
refresh=source[start:end]
catalog=re.sub(r'^package .*;\n|^import .*;\n','',(base/'GeneralDownloadCatalog.java').read_text(),flags=re.M)
java='''import java.util.*;import java.io.*;import java.net.*;
class CatalogTest {
 String activeScreen="downloads";boolean activityForeground=true;String DOWNLOAD_SERVICE="download";ListView downloadsList=new ListView();Runnable generalDownloadsRefresh=()->{};DownloadManager manager;Resolver resolver=new Resolver();
 Object getSystemService(String x){return manager;}Resolver getContentResolver(){return resolver;}void addMessageCard(ListView l,String s){l.addView(new View(s));}String safeMessage(Throwable x){return x.toString();}String mimeForName(String s){return "file";}
 View generalDownloadCard(GeneralDownloadCatalog.Entry e){return new View("native:"+e.name+":"+e.state());}View downloadFileCard(String n,long z,String m,Uri u,File f){return new View("media:"+n);}
'''+refresh+'''
 static void check(boolean v,String m){if(!v)throw new AssertionError(m);}
 static Map<String,Object> row(long id,String name,int status,long downloaded,long total,String local){var r=new HashMap<String,Object>();r.put("_id",id);r.put("title",name);r.put("status",(long)status);r.put("bytes_so_far",downloaded);r.put("total_size",total);r.put("local_uri",local);r.put("lastmod",id);r.put("mime","application/octet-stream");r.put("reason",(long)404);return r;}
 public static void main(String[]args){
 DownloadManager dm=new DownloadManager();dm.rows.add(row(1,"game.zip",2,3_000_000_000L,6_000_000_000L,"file:///storage/emulated/0/Download/SatLink/game.zip"));dm.rows.add(row(2,"app.apk",8,10,10,"file:///storage/emulated/0/Download/SatLink/app.apk"));dm.rows.add(row(3,"disc.iso",1,0,-1,"file:///storage/emulated/0/Download/SatLink/disc.iso"));dm.rows.add(row(4,"setup.exe",4,4,8,"file:///storage/emulated/0/Download/SatLink/setup.exe"));dm.rows.add(row(5,"failed.rar",16,4,8,"file:///storage/emulated/0/Download/SatLink/failed.rar"));
 var entries=GeneralDownloadCatalog.read(dm);check(entries.size()==5,"Filtered nonmedia/status rows");check(entries.get(0).name.equals("failed.rar"),"History order wrong");var zip=entries.stream().filter(x->x.id==1).findFirst().get();check(zip.percent()==50 && zip.downloaded==3_000_000_000L && zip.active(),"64-bit progress broken");var iso=entries.stream().filter(x->x.id==3).findFirst().get();check(iso.percent()==-1 && iso.active(),"Unknown total represented as zero percent");var apk=entries.stream().filter(x->x.id==2).findFirst().get();check(apk.complete() && !apk.active(),"Completed status wrong");
 var names=GeneralDownloadCatalog.representedNames(entries);check(names.equals(Set.of("app.apk","game.zip","disc.iso","setup.exe","failed.rar")),"Merge lost native destination names");check(GeneralDownloadCatalog.openUri(dm,zip)==null && dm.openCalls==0,"Opened incomplete file");check(GeneralDownloadCatalog.openUri(dm,apk).toString().equals("content://downloads/2") && dm.openCalls==1,"Completed open did not use DownloadManager URI");check(GeneralDownloadCatalog.read(dm).size()==5,"Existing native rows not recovered on repeated read");
 CatalogTest app=new CatalogTest();app.manager=dm;app.resolver.rows.add(new HashMap<>(Map.of("_id",90L,"display","app.apk","size",10L,"mime","application/octet-stream")));app.resolver.rows.add(new HashMap<>(Map.of("_id",91L,"display","video.mp4","size",20L,"mime","video/mp4")));app.resolver.rows.add(new HashMap<>(Map.of("_id",92L,"display","game.zip","size",3_000_000_000L,"mime","application/zip")));app.refreshDownloads();check(app.downloadsList.cards.size()==6,"Native/MediaStore merge duplicated or omitted files");check(app.downloadsList.cards.stream().anyMatch(x->x.text.startsWith("native:game.zip")),"Current ZIP missing in actual UI refresh");check(app.downloadsList.cards.stream().anyMatch(x->x.text.equals("media:video.mp4")),"Existing video card lost");check(app.downloadsList.posts==1,"Live polling not scheduled");
 dm.rows.get(0).put("status",8L);app.refreshDownloads();check(app.downloadsList.cards.stream().anyMatch(x->x.text.equals("native:game.zip:اكتمل التنزيل")),"Completion did not update UI");check(app.downloadsList.posts==1,"Duplicate polling chains");app.activityForeground=false;app.refreshDownloads();check(app.downloadsList.posts==0,"Hidden/background refresh continued scheduling");
 check(GeneralDownloadCatalog.remove(dm,apk) && dm.removed==2,"Cancel/delete did not use native ID");check(GeneralDownloadCatalog.read(dm).size()==4,"Deleted request still listed");
 dm.rows.add(row(6,"",8,0,0,"file:///storage/emulated/0/Download/SatLink/%D9%85%D9%84%D9%81.zip"));check(GeneralDownloadCatalog.read(dm).get(0).name.equals("ملف.zip"),"Fallback Unicode filename lost");
 System.out.println("PASS production native catalog and actual refreshDownloads: ZIP/ISO/EXE/APK/all states, existing requests, 6GB totals, live progress/completion, unknown totals, MediaStore merge without duplicates, preserved video card, native open/remove ID, lifecycle-bounded polling");
 }
}
'''+catalog+'''
class DownloadManager{static final int STATUS_PENDING=1,STATUS_RUNNING=2,STATUS_PAUSED=4,STATUS_SUCCESSFUL=8,STATUS_FAILED=16;static final String COLUMN_ID="_id",COLUMN_TITLE="title",COLUMN_MEDIA_TYPE="mime",COLUMN_LOCAL_URI="local_uri",COLUMN_BYTES_DOWNLOADED_SO_FAR="bytes_so_far",COLUMN_TOTAL_SIZE_BYTES="total_size",COLUMN_STATUS="status",COLUMN_REASON="reason",COLUMN_LAST_MODIFIED_TIMESTAMP="lastmod";static class Query{}List<Map<String,Object>>rows=new ArrayList<>();int openCalls;long removed;Cursor query(Query q){return new Cursor(rows);}Uri getUriForDownloadedFile(long id){openCalls++;return Uri.parse("content://downloads/"+id);}int remove(long...ids){removed=ids[0];return rows.removeIf(x->x.get("_id").equals(ids[0]))?1:0;}}
class Cursor implements AutoCloseable{List<Map<String,Object>>rows;List<String>keys=new ArrayList<>();int pos=-1;Cursor(List<Map<String,Object>>r){rows=r;for(var row:r)for(var k:row.keySet())if(!keys.contains(k))keys.add(k);}int getColumnIndex(String k){return keys.indexOf(k);}int getColumnIndexOrThrow(String k){int i=getColumnIndex(k);if(i<0)throw new RuntimeException(k);return i;}boolean moveToNext(){return ++pos<rows.size();}long getLong(int i){var x=rows.get(pos).get(keys.get(i));return x==null?0:((Number)x).longValue();}String getString(int i){var x=rows.get(pos).get(keys.get(i));return x==null?null:x.toString();}public void close(){}}
class Uri{URI uri;static Uri parse(String x){var u=new Uri();u.uri=URI.create(x==null?"":x);return u;}String getPath(){return uri.getPath();}String getLastPathSegment(){String p=getPath();return p==null?null:p.substring(p.lastIndexOf('/')+1);}public String toString(){return uri.toString();}}
class ContentUris{static Uri withAppendedId(Uri u,long id){return Uri.parse(u+"/"+id);}}
class Build{static class VERSION{static int SDK_INT=30;}static class VERSION_CODES{static int Q=29;}}
class MediaStore{static class Downloads{static Uri EXTERNAL_CONTENT_URI=Uri.parse("content://media/downloads");static String _ID="_id",DISPLAY_NAME="display",SIZE="size",MIME_TYPE="mime",DATE_ADDED="date",RELATIVE_PATH="path";}}
class Environment{static String DIRECTORY_DOWNLOADS="Download";static File getExternalStoragePublicDirectory(String s){return new File("/nonexistent-test-downloads");}}
class Resolver{List<Map<String,Object>>rows=new ArrayList<>();Cursor query(Uri u,String[]p,String s,String[]a,String o){return new Cursor(rows);}}
class View{String text;View(String s){text=s;}}
class ListView{List<View>cards=new ArrayList<>();int posts;void removeAllViews(){cards.clear();}void addView(View v){cards.add(v);}void removeCallbacks(Runnable r){posts=0;}void postDelayed(Runnable r,long ms){posts++;}}
'''
with tempfile.TemporaryDirectory() as temp:
 p=Path(temp)/'CatalogTest.java';p.write_text(java);subprocess.run(['java',str(p)],check=True)
assert 'GeneralDownloadCatalog.openUri(manager, item)' in source
assert 'GeneralDownloadCatalog.remove(manager, item)' in source
assert 'downloadsList.removeCallbacks(generalDownloadsRefresh)' in source
