from pathlib import Path
import re,subprocess,tempfile
root=Path(__file__).resolve().parents[1]
code=re.sub(r'^package .*;\n|^import .*;\n','',(root/'app/src/main/java/com/satlink/geckoviewtest/FullscreenDisplayControls.java').read_text(),flags=re.M)
stubs=r'''
class Context {static final String AUDIO_SERVICE="audio";}
class Activity extends Context {
 Application app=new Application();Window window=new Window();ViewGroup content=new ViewGroup(this);AudioManager audio=new AudioManager();
 Object getSystemService(String key){return audio;}Resources getResources(){return new Resources();}Object getContentResolver(){return this;}
 Window getWindow(){return window;}Application getApplication(){return app;}
 @SuppressWarnings("unchecked") <T extends View> T findViewById(int id){return (T)content;}
}
class Application {
 interface ActivityLifecycleCallbacks {void onActivityPaused(Activity a);void onActivityResumed(Activity a);void onActivityDestroyed(Activity a);void onActivityCreated(Activity a,Bundle b);void onActivityStarted(Activity a);void onActivityStopped(Activity a);void onActivitySaveInstanceState(Activity a,Bundle b);}
 java.util.List<ActivityLifecycleCallbacks> callbacks=new java.util.ArrayList<>();
 void registerActivityLifecycleCallbacks(ActivityLifecycleCallbacks c){callbacks.add(c);}
 void unregisterActivityLifecycleCallbacks(ActivityLifecycleCallbacks c){callbacks.remove(c);}
}
class Resources {Metrics getDisplayMetrics(){return new Metrics();}}class Metrics{float density=1;}
class Window {WindowManager.LayoutParams attrs=new WindowManager.LayoutParams();WindowManager.LayoutParams getAttributes(){return attrs;}void setAttributes(WindowManager.LayoutParams p){attrs=p;}}
class WindowManager {static class LayoutParams{float screenBrightness=-1;}}
class AudioManager {
 static final int STREAM_MUSIC=3;int current=7,max=15,calls;boolean fixed,reject;int stream=-1;
 boolean isVolumeFixed(){return fixed;}int getStreamVolume(int s){return current;}int getStreamMaxVolume(int s){return max;}
 void setStreamVolume(int s,int v,int f){if(reject)throw new SecurityException();calls++;stream=s;current=v;}
}
class Settings{static class System{static final String SCREEN_BRIGHTNESS="screen";static int getInt(Object resolver,String key,int fallback){return 128;}}}
class Bundle{}class android{static class R{static class id{static int content=1;}}}
class Gravity{static final int LEFT=3,RIGHT=5,CENTER_VERTICAL=16;}
class KeyEvent{static final int ACTION_DOWN=0,KEYCODE_DPAD_UP=19,KEYCODE_DPAD_DOWN=20;int getAction(){return 0;}}
class MotionEvent {
 static final int ACTION_DOWN=0,ACTION_UP=1,ACTION_MOVE=2,ACTION_CANCEL=3;int action;float y;
 MotionEvent(int action,float y){this.action=action;this.y=y;}int getActionMasked(){return action;}float getY(){return y;}
}
class View {
 static final int VISIBLE=0,GONE=8,IMPORTANT_FOR_ACCESSIBILITY_YES=1;
 static long now=0;static java.util.Map<Runnable,Long> timers=new java.util.HashMap<>();
 int visibility=0,width=44,height=180;ViewGroup parent;ViewGroup.LayoutParams layout;String description;
 View(Context c){}void setClickable(boolean b){}void setFocusable(boolean b){}void setImportantForAccessibility(int b){}
 void setContentDescription(String s){description=s;}void invalidate(){}void clearFocus(){}void setVisibility(int v){visibility=v;}
 void removeCallbacks(Runnable r){timers.remove(r);}boolean postDelayed(Runnable r,long ms){timers.put(r,now+ms);return true;}
 static void advance(long ms){now+=ms;var ready=new java.util.ArrayList<Runnable>();timers.forEach((r,t)->{if(t<=now)ready.add(r);});for(var r:ready){timers.remove(r);r.run();}}
 void bringToFront(){}void setLayoutParams(ViewGroup.LayoutParams p){layout=p;height=p.height;width=p.width;}
 ViewGroup.LayoutParams getLayoutParams(){return layout;}ViewGroup getParent(){return parent;}int getWidth(){return width;}int getHeight(){return height;}
 protected void onDraw(Canvas c){}public boolean onTouchEvent(MotionEvent e){return false;}public boolean performClick(){return true;}public boolean onKeyDown(int c,KeyEvent e){return false;}
 interface OnLayoutChangeListener {void onLayoutChange(View v,int l,int t,int r,int b,int ol,int ot,int or,int ob);}
 OnLayoutChangeListener listener;void addOnLayoutChangeListener(OnLayoutChangeListener l){listener=l;}
}
class ViewGroup extends View {
 static class LayoutParams{int width,height;LayoutParams(int w,int h){width=w;height=h;}}
 java.util.List<View>children=new java.util.ArrayList<>();ViewGroup(Context c){super(c);}
 void addView(View v,LayoutParams p){children.add(v);v.parent=this;v.setLayoutParams(p);}void removeView(View v){children.remove(v);v.parent=null;}
 void requestDisallowInterceptTouchEvent(boolean b){}
}
class FrameLayout extends ViewGroup{FrameLayout(Context c){super(c);}static class LayoutParams extends ViewGroup.LayoutParams{int gravity,leftMargin,rightMargin;LayoutParams(int w,int h,int g){super(w,h);gravity=g;}}}
class Paint {static final int ANTI_ALIAS_FLAG=1;enum Align{CENTER}Paint(int x){}void setColor(int x){}void setTextSize(int x){}void setTextAlign(Align a){}void setStrokeWidth(int x){}}
class Color{static final int WHITE=-1,GRAY=0xaaaaaa;}
class Canvas{void drawRoundRect(float a,float b,float c,float d,float e,float f,Paint p){}void drawText(String s,float a,float b,Paint p){}void drawLine(float a,float b,float c,float d,Paint p){}void drawCircle(float a,float b,float c,Paint p){}}
'''
test=r'''
class ControlsTest {
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static View bar(FullscreenDisplayControls c,String name)throws Exception{var f=c.getClass().getDeclaredField(name);f.setAccessible(true);return (View)f.get(c);}
 public static void main(String[]args)throws Exception{
  var a=new Activity();a.window.attrs.screenBrightness=.4f;var c=new FullscreenDisplayControls(a);
  var light=bar(c,"light");var sound=bar(c,"sound");var overlay=bar(c,"overlay");
  check(overlay.visibility==8,"Visible outside fullscreen");c.setFullscreen(true);check(light.visibility==0 && sound.visibility==0,"Missing fullscreen bars");
  check(((FrameLayout.LayoutParams)light.layout).gravity==(Gravity.LEFT|Gravity.CENTER_VERTICAL),"Brightness wrong side");
  check(((FrameLayout.LayoutParams)sound.layout).gravity==(Gravity.RIGHT|Gravity.CENTER_VERTICAL),"Volume wrong side");
  View.advance(1999);check(light.visibility==0,"Hid too early");View.advance(1);check(light.visibility==8,"No idle hide");
  c.onScreenTouch(new MotionEvent(0,100));check(light.visibility==0,"Touch failed to reveal");
  light.onTouchEvent(new MotionEvent(0,0));check(a.window.attrs.screenBrightness==1,"Brightness upper clamp");
  View.advance(5000);check(light.visibility==0,"Hid during slider drag");
  light.onTouchEvent(new MotionEvent(2,9999));check(Math.abs(a.window.attrs.screenBrightness-.02)<.001,"Brightness lower safety bound");
  light.onTouchEvent(new MotionEvent(1,9999));View.advance(2000);check(light.visibility==8,"No release hide");
  c.reveal();sound.onTouchEvent(new MotionEvent(0,0));check(a.audio.current==15 && a.audio.stream==3,"Not media stream/full volume");
  sound.onTouchEvent(new MotionEvent(2,999));check(a.audio.current==0,"Volume lower clamp");sound.onTouchEvent(new MotionEvent(3,999));
  sound.onKeyDown(19,new KeyEvent());check(a.audio.current==1,"Remote up unavailable");
  a.audio.fixed=true;c.reveal();int before=a.audio.calls;sound.onTouchEvent(new MotionEvent(0,0));sound.onTouchEvent(new MotionEvent(1,0));check(a.audio.calls==before,"Tried modifying fixed volume");
  check(sound.description.contains("ثابت"),"No fixed-volume explanation");
  a.audio.fixed=false;a.audio.reject=true;c.reveal();sound.onTouchEvent(new MotionEvent(0,0));sound.onTouchEvent(new MotionEvent(3,0));
  c.onActivityPaused(a);check(overlay.visibility==8 && a.window.attrs.screenBrightness==.4f,"Pause leaked brightness");
  check(View.timers.isEmpty(),"Pause leaked timer");c.onActivityResumed(a);check(overlay.visibility==0,"Resume fullscreen failed");
  c.setFullscreen(false);check(overlay.visibility==8 && a.window.attrs.screenBrightness==.4f,"Fullscreen exit didn't restore");
  a.window.attrs.screenBrightness=-1;c.setFullscreen(true);light.onTouchEvent(new MotionEvent(0,80));light.onTouchEvent(new MotionEvent(1,80));c.setFullscreen(false);
  check(a.window.attrs.screenBrightness==-1,"System brightness sentinel lost");
  c.onActivityDestroyed(a);check(a.content.children.isEmpty() && a.app.callbacks.isEmpty() && View.timers.isEmpty(),"Destroy leak");
  System.out.println("PASS native controls: left brightness/right media volume, 2-second idle, drag stays visible, clamping, remote adjustment, fixed/policy volume, brightness restoration on exit/pause, lifecycle cleanup (Android stubs)");
 }
}
'''
with tempfile.TemporaryDirectory() as tmp:
 p=Path(tmp)/'ControlsTest.java';p.write_text('import java.util.*;\n'+test+code+stubs);subprocess.run(['java',str(p)],check=True)
