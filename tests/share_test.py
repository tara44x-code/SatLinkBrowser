from pathlib import Path
import re,subprocess,tempfile,xml.etree.ElementTree as ET
root=Path(__file__).resolve().parents[1];base=root/'app/src/main/java/com/satlink/geckoviewtest'
parser=re.sub(r'^package .*;\n','',(base/'SharedUrlParser.java').read_text(),flags=re.M)
s=(base/'MainActivity.java').read_text();start=s.index('    @Override\n    protected void onNewIntent');end=s.index('    private void refreshCurrentPage',start)
methods=s[start:end].replace('@Override','')
imports='\n'.join(re.findall(r'^import .*;',parser,re.M))+'\nimport java.io.File;\n'
parser=re.sub(r'^import .*;\n','',parser,flags=re.M)
source=imports+'''\nclass ShareTest extends Activity {
 Object lastCapturedItems="old",lastPlayerFormats="old";double capturedDurationSeconds=99;
 String latestCookieHeader="old",latestCookieFilePath="old",latestOrigin="old",latestUserAgent="old",preferredPlayerPageUrl="old",pendingPageTitle="old",pendingPageUrl="";
 String latestActivePostUrl="old-post",latestActiveMediaKey="old-media";boolean sharedNavigationPending;int scans=0,closed=0;String opened="";
 Object session=new Object();static class BrowserTab{String url="about:blank";}BrowserTab tab=new BrowserTab();BrowserTab findTab(Object s){return tab;}
 String newTab="";void openInNewTab(String u,boolean remember){newTab=u;}File getCacheDir(){return new File(".");}final java.util.concurrent.Executor worker=r->{};static final String TAG="test";
 void closeCapturePanel(){closed++;sharedNavigationPending=false;}void openUrl(String url){opened=url;}
 void requestMediaScan(){scans++;}String normalizePageUrl(String url){return url;}
'''+methods+'''
 static void check(boolean v,String m){if(!v)throw new AssertionError(m);}
 public static void main(String[] a){
 check(SharedUrlParser.firstHttpUrl("شوف المقطع (https://vm.tiktok.com/AbCd/). نص").equals("https://vm.tiktok.com/AbCd/"),"Short link in caption");
 check(SharedUrlParser.firstHttpUrl("https://cdn.test/video?a=x%2By&sig=ABC%2FDEF").equals("https://cdn.test/video?a=x%2By&sig=ABC%2FDEF"),"Signed query changed");
 check(SharedUrlParser.firstHttpUrl("https://site.test/video_(abc)").endsWith("_(abc)"),"Balanced URL parentheses removed");
 check(SharedUrlParser.firstHttpUrl("javascript:alert(1)").isEmpty(),"Invalid scheme");
 check(SharedUrlParser.firstHttpUrl("https://name:secret@site.test/").isEmpty(),"Embedded credentials accepted");
 var app=new ShareTest();var cold=new Intent(Intent.ACTION_SEND,"شوف https://vm.tiktok.com/AbCd/");
 check(app.handleSharedLink(cold) && app.scans==1 && app.opened.equals("https://vm.tiktok.com/AbCd/"),"Cold share didn't open capture");
 check(app.lastCapturedItems==null && app.latestCookieHeader.isEmpty() && app.sharedNavigationPending,"Old page leaked into new share");
 check(!app.handleSharedLink(cold) && app.scans==1,"Same intent consumed twice");
 app.onNewIntent(new Intent(Intent.ACTION_SEND,"https://www.tiktok.com/@u/video/123"));
 check(app.scans==2 && app.opened.endsWith("/123"),"Warm share not handled");
 var clip=new Intent(Intent.ACTION_SEND,null);clip.clip=new ClipData("https://site.test/video");check(app.handleSharedLink(clip),"ClipData text fallback failed");
 check(!app.handleSharedLink(new Intent("MAIN","https://site.test/")),"Launcher interpreted as share");
 check(!app.handleSharedLink(new Intent(Intent.ACTION_SEND,"No link")),"Missing URL accepted");
 var view=new Intent(Intent.ACTION_VIEW,null);view.data=new Uri("https://site.test/page?a=1");int scansBefore=app.scans;
 check(app.handleViewLink(view) && app.opened.equals("https://site.test/page?a=1") && app.scans==scansBefore,"External link not opened in the empty tab");
 check(!app.handleViewLink(view),"Same external link opened twice");
 app.tab.url="https://old.test/";var second=new Intent(Intent.ACTION_VIEW,null);second.data=new Uri("http://next.test/");app.onNewIntent(second);
 check(app.newTab.equals("http://next.test/") && app.opened.equals("https://site.test/page?a=1"),"External link replaced the open page");
 var odd=new Intent(Intent.ACTION_VIEW,null);odd.data=new Uri("file:///data/x");check(!app.handleViewLink(odd),"Non-web link accepted");
 System.out.println("PASS production share handler: cold/warm intent, caption, short URL, signed query, stale session reset, ClipData, malformed input, single consumption");
 }
}
class Activity {protected void onNewIntent(Intent i){}void setIntent(Intent i){}}
class Uri{String value;Uri(String v){value=v;}public String toString(){return value;}}
class TextUtils{static boolean isEmpty(String v){return v==null||v.isEmpty();}}
class Log{static void w(String a,String b){}static void w(String a,String b,Throwable t){}}
class GeneralFilePolicy{static boolean http(String u){return u!=null&&(u.startsWith("https://")||u.startsWith("http://"));}}
class Intent {static final String ACTION_SEND="SEND",ACTION_VIEW="VIEW",EXTRA_TEXT="TEXT";Uri data;Uri getData(){return data;}void setData(Uri d){data=d;}String action,text;ClipData clip;Intent(String a,String t){action=a;text=t;}String getAction(){return action;}CharSequence getCharSequenceExtra(String k){return text;}ClipData getClipData(){return clip;}void setAction(String a){action=a;}void removeExtra(String k){text=null;}}
class ClipData {String text;ClipData(String s){text=s;}int getItemCount(){return 1;}ClipData getItemAt(int i){return this;}CharSequence getText(){return text;}}
class Toast {static final int LENGTH_LONG=1;static Toast makeText(Object c,String s,int t){return new Toast();}void show(){}}
'''
source+=parser
with tempfile.TemporaryDirectory() as tmp:
 p=Path(tmp)/'ShareTest.java';p.write_text(source);subprocess.run(['java',str(p)],check=True)
ns='{http://schemas.android.com/apk/res/android}'
activity=ET.parse(root/'app/src/main/AndroidManifest.xml').find('./application/activity')
assert activity.get(ns+'exported')=='true' and activity.get(ns+'launchMode')=='singleTop'
assert any(f.find('action').get(ns+'name')=='android.intent.action.SEND' and any(c.get(ns+'name')=='android.intent.category.DEFAULT' for c in f.findall('category')) and any(d.get(ns+'mimeType')=='text/plain' for d in f.findall('data')) for f in activity.findall('intent-filter'))
print('PASS Android share manifest')
