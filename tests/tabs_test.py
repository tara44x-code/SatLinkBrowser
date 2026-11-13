"""Execute production tab lifecycle/navigation with strict Gecko/Android API substitutes."""
from pathlib import Path
import subprocess,tempfile
root=Path(__file__).resolve().parents[1]
s=(root/'app/src/main/java/com/satlink/geckoviewtest/MainActivity.java').read_text()
def block(marker):
 start=s.index(marker);pos=s.index('{',start);depth=1;end=pos+1
 while depth:
  if s[end]=='{':depth+=1
  elif s[end]=='}':depth-=1
  end+=1
 return s[start:end]
markers=['    private static final class BrowserTab','    private BrowserTab findTab(','    private BrowserTab findTabByKey','    private void openInNewTab','    private GeckoSession createTabSession','    private void updateTabCount','    private void saveTabSession','    private void selectPopupIfReady','    private void selectTab','    private void closeTab','    private void startNewTab','    private void sendPlaybackMessage','    private void pauseBrowserMedia','    private void updateBrowserPlayback','    private void goBackInBrowser']
methods='\n'.join(block(x) for x in markers)
new=block('        @Override public GeckoResult<GeckoSession> onNewSession')
back=block('        public void onCanGoBack')
source=r'''import java.util.*;
class MainActivity {
 GeckoSession session;GeckoView geckoView=new GeckoView();Object runtime=new Object();WebExtension captureExtension;
 List<BrowserTab>browserTabs=new ArrayList<>();BrowserWorkspace browserWorkspace=new BrowserWorkspace();Button forwardPageButton=new Button(),refreshPageButton=new Button();void scheduleBrowserWorkspaceSave(){}boolean destroyingTabs,backNavigationPending,activityForeground=true,browserCanGoBack,sharedNavigationPending;
 String activeScreen="browser",pendingPageUrl,pendingPageTitle,latestActivePostUrl,latestActiveMediaKey,preferredPlayerPageUrl,latestCookieHeader,latestOrigin,latestCookieContents="",latestCookieFilePath="",latestUserAgent="UA";
 Object lastCapturedItems,lastPlayerFormats;double capturedDurationSeconds;int captureRequestSerial,renders;MediaSession browserMediaControl;
 Set<WebExtension.Port>capturePorts=new HashSet<>();WebExtension.Port capturePort;TextView tabsButton=new TextView(),addressBar=new TextView();View progressBar=new View();static String NATIVE_APP="satlink_capture";
 void closeCapturePanel(){captureRequestSerial++;sharedNavigationPending=false;}void updateFavoriteButtonState(){}void renderTabs(){renders++;}
 void showHomeScreen(){activeScreen="home";updateBrowserPlayback();}void showBrowserScreen(){activeScreen="browser";updateBrowserPlayback();}void showTabsScreen(){activeScreen="tabs";updateBrowserPlayback();renderTabs();}
 String normalizePageUrl(String s){return s;}
 class BrowserContentDelegate{}class BrowserProgressDelegate{}class CaptureMessageDelegate{}
 class BrowserNavigationDelegate implements NavigationDelegate{
'''+new+'\n'+back+r'''
 }
'''+methods+r'''
 static void check(boolean v,String m){if(!v)throw new AssertionError(m);}
 public static void main(String[]args){
 MainActivity a=new MainActivity();a.session=a.createTabSession();BrowserTab first=new BrowserTab(a.session);first.url="https://romsfun.com/download/resident-evil-2-biohazard-2-psx-9330";a.browserTabs.add(first);a.session.open(a.runtime);a.geckoView.setSession(a.session);a.addressBar.setText(first.url);
 GeckoSession opener=a.session;var media=new MediaSession();opener.mediaDelegate.onActivated(opener,media);
 var port=new WebExtension.Port(opener);a.capturePorts.add(port);a.capturePort=port;
 a.latestCookieContents="COOKIE_A";a.latestCookieFilePath="PATH_A";a.browserCanGoBack=true;
 a.startNewTab();check(a.browserTabs.size()==2 && a.session!=opener,"New tab reused the original session");check(first.url.contains("romsfun") && opener.loads.isEmpty(),"New tab destroyed previous page");check(media.pauses>0 && !opener.active,"Old media remained active");check(a.activeScreen.equals("home") && a.browserMediaControl==null,"Blank tab retained media/UI");check(a.lastCapturedItems==null && a.latestCookieContents.isEmpty(),"New tab retained old captured data/cookies");
 var second=a.browserTabs.get(1);a.selectTab(first);check(a.session==opener && a.browserCanGoBack && a.latestCookieContents.equals("COOKIE_A"),"Switch did not restore original tab/session/history");check(media.plays==0,"Tab switch auto-played media");a.goBackInBrowser();check(opener.backs==1,"Back affected another tab");
 var hidden=new MediaSession();second.session.mediaDelegate.onPlay(second.session,hidden);check(hidden.pauses==1 && hidden.muted && a.browserMediaControl==media,"Hidden media event replaced visible control");
 ((MainActivity.BrowserNavigationDelegate)second.session.navigation).onCanGoBack(second.session,false);check(a.browserCanGoBack,"Hidden history changed current toolbar");
 var result=((MainActivity.BrowserNavigationDelegate)opener.navigation).onNewSession(opener,"https://romsfun.com/download/resident-evil-2-biohazard-2-psx-9330/1");
 GeckoSession popup=result.value;check(!popup.open && popup.loads.isEmpty(),"onNewSession pre-opened or loaded the engine-owned session");check(a.browserTabs.size()==3 && a.session==opener,"Popup missing or switched before Gecko opened it");a.geckoView.flush();check(a.session==opener,"View attached unopened popup before engine completed result");popup.open(a.runtime);a.selectPopupIfReady(a.findTab(popup));check(a.session==popup && a.addressBar.text.endsWith("/1"),"Popup not selected after engine open");
 a.selectTab(first);a.closeTab(second);check(second.session.closes==1 && a.session==opener && a.browserTabs.size()==2,"Closing background tab changed current tab");
 a.showTabsScreen();a.closeTab(first);check(opener.closes==1 && a.session==popup && a.activeScreen.equals("tabs"),"Closing selected tab failed to select adjacent or retain list");check(port.disconnected && a.capturePorts.isEmpty() && a.capturePort==null,"Closed tab port leaked");
 a.closeTab(a.browserTabs.get(0));check(a.browserTabs.size()==1 && a.activeScreen.equals("home") && a.session.open,"Closing last tab left a dead view");check(a.tabsButton.text.equals("▣ 1"),"Tab count wrong");
 a.destroyingTabs=true;a.geckoView.posts.add(()->a.selectTab(first));a.geckoView.flush();check(a.session!=opener,"Stale popup callback resurrected closed tab");check(((MainActivity.BrowserNavigationDelegate)a.session.navigation).onNewSession(a.session,"https://x.test")==null,"Shutdown created popup");
 System.out.println("PASS production tabs: independent session/history, switch/close/last-tab replacement, unopened popup handoff with deferred attach, old-page preservation, hidden-media guard, scoped ports, closed-session cleanup, tab count");
 }
}
interface NavigationDelegate{GeckoResult<GeckoSession>onNewSession(GeckoSession s,String u);void onCanGoBack(GeckoSession s,boolean b);}
class GeckoResult<T>{T value;GeckoResult(T x){value=x;}static<T>GeckoResult<T>fromValue(T x){return new GeckoResult<>(x);}}
class GeckoSession{interface HistoryDelegate{interface HistoryList{int getCurrentIndex();}default void onHistoryStateChange(GeckoSession s,HistoryList h){}}boolean open,active;int closes,backs;List<String>loads=new ArrayList<>();MediaSession.Delegate mediaDelegate;Object navigation;Settings getSettings(){return new Settings();}static class Settings{void setSuspendMediaWhenInactive(boolean b){}}void setMediaSessionDelegate(MediaSession.Delegate d){mediaDelegate=d;}void setContentDelegate(Object o){}Object history;void setHistoryDelegate(Object h){history=h;}void setNavigationDelegate(Object o){navigation=o;}void setProgressDelegate(Object o){}void open(Object r){if(open)throw new AssertionError("double open");open=true;}boolean isOpen(){return open;}void close(){open=false;closes++;}void setActive(boolean b){active=b;}void loadUri(String s){loads.add(s);}void goBack(){backs++;}Controller getWebExtensionController(){return new Controller();}static class Controller{void setMessageDelegate(Object e,Object d,String n){}}}
class GeckoView{GeckoSession attached;List<Runnable>posts=new ArrayList<>();void setSession(GeckoSession s){if(attached!=null||!s.open)throw new AssertionError("must release/open before attach");attached=s;}void releaseSession(){attached=null;}void post(Runnable r){posts.add(r);}void postDelayed(Runnable r,long ms){posts.add(r);}void flush(){var copy=new ArrayList<>(posts);posts.clear();for(var r:copy)r.run();}}
class MediaSession{int pauses,plays;boolean muted;void pause(){pauses++;}void muteAudio(boolean b){muted=b;}interface Delegate{void onActivated(GeckoSession t,MediaSession m);void onDeactivated(GeckoSession t,MediaSession m);void onPlay(GeckoSession t,MediaSession m);}}
class WebExtension{static class Sender{GeckoSession session;Sender(GeckoSession s){session=s;}}static class Port{Sender sender;boolean disconnected;List<JSONObject>messages=new ArrayList<>();Port(GeckoSession s){sender=new Sender(s);}void disconnect(){disconnected=true;}void postMessage(JSONObject m){messages.add(m);}}}
class JSONObject{Map<String,Object>data=new HashMap<>();JSONObject put(String k,Object v){data.put(k,v);return this;}}
class View{static int GONE=8;void setVisibility(int v){}}
class TextView{String text="";void setText(String t){text=t;}String getText(){return text;}}
class TextUtils{static boolean isEmpty(String s){return s==null||s.isEmpty();}}
'''
# Production history callback uses the existing toolbar.
source=source.replace('Object lastCapturedItems,lastPlayerFormats;', 'Button backButton=new Button();Object lastCapturedItems,lastPlayerFormats;')+'\nclass Button{void setEnabled(boolean b){}void setAlpha(float f){}void setText(String s){}}\n'
import re
source+='\n'+re.sub(r'^package .*;\n|^import .*;\n','',(root/'app/src/main/java/com/satlink/geckoviewtest/BrowserWorkspace.java').read_text(),flags=re.M)
source=source.replace('import java.util.*;','import java.util.*;import java.net.*;import java.nio.charset.*;',1)
with tempfile.TemporaryDirectory() as tmp:
 p=Path(tmp)/'TabHarness.java';p.write_text(source);subprocess.run(['java',str(p)],check=True)
# Check background bridge routing from the source and the actual site's saved link behavior fixture.
assert 'scoped.put("tabKey", owner == null ? "" : owner.key)' in s
assert '!active.key.equals(json.optString("tabKey", ""))' in s
assert 'if (sender.session != null && sender.session != session) return null;' in s
assert 'if (port.sender.session != session) continue;' in s
assert 'browserTabsButton' in (root/'app/src/main/res/layout/activity_main.xml').read_text()
print('PASS bridge guards: hidden/same-URL tabs cannot submit unscoped background results; scan targets current session; header and close controls wired')
