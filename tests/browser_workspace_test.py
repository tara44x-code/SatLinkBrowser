from pathlib import Path
import subprocess,tempfile,re
root=Path(__file__).resolve().parents[1]
model=re.sub(r'^package .*;\n|^import .*;\n','',(root/'app/src/main/java/com/satlink/geckoviewtest/BrowserWorkspace.java').read_text(),flags=re.M)
source='''import java.net.*;import java.nio.charset.*;import java.util.*;
class WorkspaceTest {
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 public static void main(String[]args){
 var w=new BrowserWorkspace();w.tabs.add(new BrowserWorkspace.Page("https://site.test/a?q=x%2By","عنوان عربي\\tمع فاصل\\nوسطر","{native-state}",0));w.tabs.add(new BrowserWorkspace.Page("about:blank","","",0));w.selected=1;w.engine="DuckDuckGo";w.restoreTabs=false;
 w.visit("https://site.test/a","First",1);w.visit("https://site.test/b","Second",2);w.visit("https://site.test/a","عنوان",3);w.visit("javascript:alert(1)","Bad",4);
 check(w.history.size()==2 && w.history.get(0).time==3,"History duplicate/order/invalid navigation");check(w.matching("عنوان").size()==1 && w.matching("SITE.TEST/B").size()==1,"History search lost Arabic or case-insensitive URL");
 w.rememberClosed("https://site.test/a","A","STATE-A");w.rememberClosed("https://site.test/b","B","STATE-B");
 var copy=BrowserWorkspace.decode(w.encode());check(copy.tabs.size()==2 && copy.selected==1 && !copy.restoreTabs && copy.engine.equals("DuckDuckGo"),"Persistence lost settings/tabs/selection");check(copy.tabs.get(0).title.equals(w.tabs.get(0).title) && copy.tabs.get(0).state.equals("{native-state}") && copy.tabs.get(0).url.endsWith("x%2By"),"Title/state/signed URL changed during serialization");
 check(copy.reopen().url.endsWith("/b") && copy.reopen().state.equals("STATE-A") && copy.reopen()==null,"Closed tab order or state lost");check(copy.search("اختبار + ?").startsWith("https://duckduckgo.com/?q=") && copy.search("اختبار + ?").contains("%2B"),"Search query not encoded");copy.engine="Bing";check(copy.search("x").startsWith("https://www.bing.com/"),"Bing setting not applied");copy.engine="invalid";check(copy.search("x").startsWith("https://www.google.com/"),"Search fallback unsafe");
 check(BrowserWorkspace.decode("corrupt").tabs.isEmpty(),"Invalid version accepted");String encoded=w.encode();var broken=BrowserWorkspace.decode(encoded+"T\\tNOT-BASE64!\\t\\t\\t0\\n");check(broken.tabs.size()==2,"Malformed row destroyed valid persisted tabs");
 check(!BrowserWorkspace.restorable("https://user:pass@site.test/a") && !BrowserWorkspace.restorable("file:///private"),"Unsafe restoration URL accepted");
 for(int i=0;i<1100;i++)w.visit("https://site.test/"+i,"P"+i,i);check(w.history.size()==1000,"History unbounded");for(int i=0;i<30;i++)w.rememberClosed("https://site.test/"+i,"","");check(w.closed.size()==20,"Closed history unbounded");
 check(new BrowserWorkspace.Page("https://site.test","","x".repeat(40000),0).state.isEmpty(),"Oversized native state not bounded");
 copy.history.clear();check(BrowserWorkspace.decode(copy.encode()).history.isEmpty(),"Clear history not persisted");
 System.out.println("PASS production browser workspace: tab/state/selection persistence, corrupt-row recovery, Arabic title/search, signed URLs, closed-tab stack, bounded/clearable history, search engines, URL validation");
 }
}
'''+model
with tempfile.TemporaryDirectory() as tmp:
 p=Path(tmp)/'WorkspaceTest.java';p.write_text(source);subprocess.run(['java',str(p)],check=True)
main=(root/'app/src/main/java/com/satlink/geckoviewtest/MainActivity.java').read_text()
def method(marker):
 start=main.index(marker);at=main.index('{',start);depth=1;end=at+1
 while depth:
  depth+=(main[end]=='{')-(main[end]=='}');end+=1
 return main[start:end]
methods='\n'.join(method(x) for x in ['    private void persistBrowserWorkspace','    private void loadSavedBrowserPage','    private boolean restoreBrowserWorkspace','    private void reopenClosedBrowserTab','    private void goForwardInBrowser','    private void refreshCurrentPage'])
harness='''import java.util.*;import java.net.*;import java.nio.charset.*;
class BrowserIntegration {
 BrowserWorkspace browserWorkspace=new BrowserWorkspace();List<BrowserTab>browserTabs=new ArrayList<>();GeckoSession session;Object runtime=new Object();boolean destroyingTabs,restoringWorkspace;String activeScreen="browser";int selected,saves;Prefs prefs=new Prefs();Button refreshPageButton=new Button();View progressBar=new View();static int MODE_PRIVATE=0,TAG=0;
 static class BrowserTab{GeckoSession session;String url="about:blank",title="",savedState="";boolean loading,canGoForward;BrowserTab(GeckoSession s){session=s;}}
 BrowserTab findTab(GeckoSession s){for(var t:browserTabs)if(t.session==s)return t;return null;}GeckoSession createTabSession(){return new GeckoSession();}void selectTab(BrowserTab t){session=t.session;selected=browserTabs.indexOf(t);activeScreen="browser";}void updateTabCount(){}void scheduleBrowserWorkspaceSave(){saves++;}void showBrowserScreen(){activeScreen="browser";}Prefs getSharedPreferences(String s,int mode){return prefs;}
'''+methods+'''
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 public static void main(String[]args){
 var a=new BrowserIntegration();a.session=a.createTabSession();a.session.open(a.runtime);a.browserTabs.add(new BrowserTab(a.session));a.browserWorkspace.tabs.add(new BrowserWorkspace.Page("https://site.test/first","First","VALID_STATE",0));a.browserWorkspace.tabs.add(new BrowserWorkspace.Page("https://site.test/second","Second","CORRUPT_STATE",0));a.browserWorkspace.selected=1;
 check(a.restoreBrowserWorkspace() && a.browserTabs.size()==2 && a.selected==1,"Actual restore lost pages/active index");check(a.browserTabs.get(0).session.restored.equals("VALID_STATE") && a.browserTabs.get(0).session.loads.isEmpty(),"Native session state not restored");check(a.browserTabs.get(1).session.loads.equals(List.of("https://site.test/second")),"Invalid native state did not fall back to original URL");check(!a.restoringWorkspace,"Restore left saves disabled");
 a.persistBrowserWorkspace();var saved=BrowserWorkspace.decode(a.prefs.value);check(saved.tabs.size()==2 && saved.selected==1 && saved.tabs.get(0).state.equals("VALID_STATE"),"Actual persistence lost active page/state");a.browserWorkspace.rememberClosed("https://site.test/closed","Closed","VALID_STATE");a.reopenClosedBrowserTab();check(a.browserTabs.size()==3 && a.session.restored.equals("VALID_STATE") && a.browserWorkspace.closed.isEmpty(),"Actual reopen did not restore/remove last closed snapshot");
 var current=a.findTab(a.session);current.canGoForward=true;a.goForwardInBrowser();check(a.session.forwards==1,"Forward did not affect selected session");current.canGoForward=false;a.goForwardInBrowser();check(a.session.forwards==1,"Forward bypassed capability guard");
 current.loading=true;a.refreshCurrentPage();check(a.session.stops==1 && a.session.reloads==0 && !current.loading && a.refreshPageButton.text.equals("↻"),"Refresh did not stop current loading page");a.refreshCurrentPage();check(a.session.reloads==1,"Idle refresh did not reload");
 a.browserWorkspace.restoreTabs=false;var b=new BrowserIntegration();b.browserWorkspace=BrowserWorkspace.decode(a.browserWorkspace.encode());b.session=b.createTabSession();b.browserTabs.add(new BrowserTab(b.session));check(!b.restoreBrowserWorkspace() && b.browserTabs.size()==1,"Disabled restore still loaded tabs");
 System.out.println("PASS actual browser integration: selected-tab persistence, native state restore and URL fallback, reopen closed tab, restore setting, guarded forward, loading stop versus reload");
 }
}
class GeckoSession{List<String>loads=new ArrayList<>();String restored="";int forwards,stops,reloads;void open(Object r){}void setActive(boolean b){}void restoreState(SessionState s){restored=s.value;}void loadUri(String s){loads.add(s);}void goForward(){forwards++;}void stop(){stops++;}void reload(){reloads++;}static class SessionState{String value;static SessionState fromString(String s){if(!"VALID_STATE".equals(s))return null;var state=new SessionState();state.value=s;return state;}}}
class Prefs{String value;Prefs edit(){return this;}Prefs putString(String k,String v){value=v;return this;}void apply(){}}
class Button{String text;void setText(String s){text=s;}}
class View{static int GONE=8;void setVisibility(int v){}}
class Toast{static int LENGTH_SHORT=0;static Toast makeText(Object a,String b,int c){return new Toast();}void show(){}}
class Log{static void w(int t,String s,Throwable e){}}
'''+model
with tempfile.TemporaryDirectory() as tmp:
 p=Path(tmp)/'BrowserIntegration.java';p.write_text(harness);subprocess.run(['java',str(p)],check=True)
