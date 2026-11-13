from pathlib import Path
import re,subprocess,tempfile
root=Path(__file__).resolve().parents[1];base=root/'app/src/main/java/com/satlink/geckoviewtest'
s=(base/'MainActivity.java').read_text()
methods=s[s.index('    private void sendPlaybackMessage'):s.index('    private void showHomeScreen')]
back=s[s.index('    @Override\n    public void onBackPressed'):s.index('    @Override\n    protected void onDestroy')]
delegate=s[s.index('        GeckoSession created = new GeckoSession();'):s.index('        created.setContentDelegate')].replace('MainActivity.this.session','session') + '\n session=created; browserTabs.add(new BrowserTab(session));'
page=re.sub(r'^package .*;\n|^import .*;\n','',(base/'ActiveMediaPage.java').read_text(),flags=re.M)
source='''import java.util.*;import java.net.URI;
class PlaybackTest extends Activity {
 final List<BrowserTab> browserTabs=new ArrayList<>();BrowserTab findTab(GeckoSession target){for(var tab:browserTabs)if(tab.session==target)return tab;return null;}
 static class BrowserTab{GeckoSession session;MediaSession media;String key=UUID.randomUUID().toString(),openerKey="",url="https://site.test/";int historyIndex=-1;BrowserTab(GeckoSession s){session=s;}}
 boolean backNavigationPending,returnToBrowserFromHome,inPageFullscreen;long lastHomeBackPress;Object runtime=new Object();final List<BrowserTab>closed=new ArrayList<>();
 GeckoSession createTabSession(){return new GeckoSession();}void selectTab(BrowserTab t){session=t.session;}void closeTab(BrowserTab t){closed.add(t);browserTabs.remove(t);}
 boolean currentTabHasPage(){BrowserTab t=findTab(session);return t!=null&&!"about:blank".equals(t.url);}
 final View downloadsList=new View();final Runnable generalDownloadsRefresh=()->{};void refreshDownloads(){}void persistBrowserWorkspace(){}
 boolean activityForeground=true,browserCanGoBack=true;String activeScreen="browser";GeckoSession session;MediaSession browserMediaControl;final List<WebExtension.Port>capturePorts=new ArrayList<>();final View capturePanel=new View();final Text addressBar=new Text();int homes=0;boolean movedBack;
 void showHomeScreen(){homes++;activeScreen="home";updateBrowserPlayback();}void showBrowserScreen(){activeScreen="browser";updateBrowserPlayback();}void closeCapturePanel(){capturePanel.visibility=8;}
 boolean moveTaskToBack(boolean b){movedBack=b;return b;}
 void initializeDelegate(){
'''+delegate+'''}
'''+methods+back+'''
 static void check(boolean v,String m){if(!v)throw new AssertionError(m);}
 public static void main(String[] a){
 var app=new PlaybackTest();var port=new WebExtension.Port();app.capturePorts.add(port);app.initializeDelegate();
 port.sender.session=app.session;
 app.showHomeScreen();check(app.browserMediaControl==null && !app.session.active,"No-media home failed");
 app.showBrowserScreen();var original=MediaSession.createForEngine(app.session);app.session.delegate.onActivated(app.session,original);
 check(app.browserMediaControl==original,"Engine session not retained");
 app.showHomeScreen();check(!app.session.active && app.browserMediaControl.muted && app.browserMediaControl.pauses==1,"Home did not suspend, mute and pause page");
 check(port.messages.stream().anyMatch(x->"pauseMedia".equals(x.data.get("action"))),"DOM frames did not receive pause");
 var late=MediaSession.createForEngine(app.session);app.session.delegate.onPlay(app.session,late);check(late.pauses==1 && late.muted,"Hidden late autoplay bypassed guard");
 app.showBrowserScreen();check(app.session.active && !app.browserMediaControl.muted,"Browser not reactivated");check(app.browserMediaControl.plays==0,"Returning to browser resumed video");
 int beforeBack=app.browserMediaControl.pauses;app.goBackInBrowser();check(app.session.backs==1 && app.browserMediaControl.pauses==beforeBack+1,"History back didn't pause media first");
 app.browserCanGoBack=false;app.findTab(app.session).historyIndex=2;int viaHistory=app.session.backs;app.goBackInBrowser();check(app.session.backs==viaHistory+1,"History position ignored when Gecko flag was stale");app.findTab(app.session).historyIndex=0;
 app.browserCanGoBack=false;app.goBackInBrowser();check(app.activeScreen.equals("home"),"Back with no history failed to show home");
 app.onBackPressed();check(!app.movedBack && app.activeScreen.equals("home"),"First home back left the app");
 app.onBackPressed();check(app.movedBack && app.activeScreen.equals("home"),"Second home back did not leave");
 app.showBrowserScreen();app.inPageFullscreen=true;int backsBefore=app.session.backs;app.browserCanGoBack=true;app.onBackPressed();
 check(app.session.fullscreenExits==1 && app.session.backs==backsBefore,"Back left the page instead of fullscreen");app.inPageFullscreen=false;
 app.onBackPressed();check(app.session.backs==backsBefore+1 && app.backNavigationPending,"Back did not step one page");
 app.browserCanGoBack=false;var parent=app.findTab(app.session);var popupSession=new GeckoSession();var popup=new BrowserTab(popupSession);popup.openerKey=parent.key;app.browserTabs.add(popup);app.session=popupSession;
 app.onBackPressed();check(app.session==parent.session && app.closed.contains(popup) && app.activeScreen.equals("browser"),"Back from a new-tab page did not return to the page that opened it");
 app.returnToBrowserFromHome=true;app.activeScreen="home";app.movedBack=false;app.onBackPressed();check(app.activeScreen.equals("browser") && !app.movedBack,"Back from home did not return to the open page");
 var control=new WebExtension.Port();control.sender.session=app.session;var other=new WebExtension.Port();other.sender.session=new GeckoSession();app.capturePorts.add(control);app.capturePorts.add(other);
 app.sendPlayerControl("seek",-10d);var sent=control.messages.get(control.messages.size()-1);
 check("playerControl".equals(sent.data.get("action")) && "seek".equals(sent.data.get("command")) && Double.valueOf(-10d).equals(sent.data.get("value")) && Boolean.TRUE.equals(sent.data.get("allowed")),"Seek command not delivered to the visible tab");
 check(other.messages.stream().noneMatch(x->"playerControl".equals(x.data.get("action"))),"Player command leaked to a hidden tab");app.capturePorts.remove(control);app.capturePorts.remove(other);
 app.showBrowserScreen();app.onPause();check(!app.session.active && app.browserMediaControl.muted,"Leaving app kept media running");app.onResume();check(app.session.active && app.browserMediaControl.plays==0,"Activity resume auto-played");
 app.session.delegate.onDeactivated(app.session,original);check(app.browserMediaControl==late,"Old deactivation cleared the new session");
 app.session.delegate.onDeactivated(app.session,late);check(app.browserMediaControl==null,"Deactivated session retained");
 app.showHomeScreen();var replacement=MediaSession.createForEngine(app.session);app.session.delegate.onActivated(app.session,replacement);check(replacement.muted && replacement.pauses==1,"Hidden activation not stopped");
 app.showBrowserScreen();check(!replacement.muted && replacement.plays==0,"Reactivation auto-played or stayed muted");
 check(ActiveMediaPage.resolve("https://www.tiktok.com/", "https://www.tiktok.com/@u/video/123").endsWith("/123"),"Root instead of visible post selected");
 check(ActiveMediaPage.resolve("https://www.tiktok.com/", "").isEmpty(),"Unsupported root sent to engine");
 check(ActiveMediaPage.resolve("https://vm.tiktok.com/AB/", "").equals("https://vm.tiktok.com/AB/"),"Short URL blocked");
 check(ActiveMediaPage.validTikTokPost("https://tiktok.com.fake/@u/video/123").isEmpty(),"Unrelated host accepted");
 check(ActiveMediaPage.resolve("https://youtube.com/watch?v=123", "https://www.tiktok.com/@u/video/123").contains("youtube.com"),"TikTok state changed YouTube routing");
 System.out.println("PASS production initialization with inaccessible MediaSession constructor, delegate activation/deactivation and playback: home/history/app pause, late autoplay blocked, no resume play, home back, active feed post, root rejected, short links, YouTube preserved");
 }
}
class Activity{protected void onPause(){}protected void onResume(){}public void onBackPressed(){}}
class Toast{static final int LENGTH_SHORT=0;static Toast makeText(Object c,String s,int t){return new Toast();}void show(){}}
class GeckoSession{int fullscreenExits;String loaded;boolean opened=true;void exitFullScreen(){fullscreenExits++;}boolean isOpen(){return opened;}void open(Object r){opened=true;}void loadUri(String u){loaded=u;}boolean active=true;int backs;MediaSession.Delegate delegate;void setActive(boolean b){active=b;}void goBack(){backs++;}void setMediaSessionDelegate(MediaSession.Delegate d){delegate=d;}Settings getSettings(){return new Settings();}static class Settings{void setSuspendMediaWhenInactive(boolean v){}}}
class MediaSession{int pauses,plays;boolean muted;private MediaSession(GeckoSession s){}static MediaSession createForEngine(GeckoSession s){return new MediaSession(s);}void pause(){pauses++;}void play(){plays++;}void muteAudio(boolean b){muted=b;}interface Delegate{default void onActivated(GeckoSession t,MediaSession m){}default void onPlay(GeckoSession t,MediaSession m){}default void onDeactivated(GeckoSession t,MediaSession m){}}}
class JSONObject{Map<String,Object>data=new HashMap<>();JSONObject put(String k,Object v){data.put(k,v);return this;}}
class WebExtension{static class Sender{GeckoSession session;}static class Port{Sender sender=new Sender();List<JSONObject>messages=new ArrayList<>();void postMessage(JSONObject m){messages.add(m);}}}
class View{static int VISIBLE=0;int visibility=8;int getVisibility(){return visibility;}void removeCallbacks(Runnable r){}}
class Text{String getText(){return "https://site.test/";}}
class TextUtils{static boolean isEmpty(String v){return v==null||v.isEmpty();}}
'''+page
with tempfile.TemporaryDirectory() as tmp:
 p=Path(tmp)/'PlaybackTest.java';p.write_text(source);subprocess.run(['java',str(p)],check=True)
