"""Run the real FullscreenPlaybackControls class against small Android substitutes."""
from pathlib import Path
import re,subprocess,tempfile
root=Path(__file__).resolve().parents[1]
code=re.sub(r'^package .*;\n|^import .*;\n','',(root/'app/src/main/java/com/satlink/geckoviewtest/FullscreenPlaybackControls.java').read_text(),flags=re.M)
stubs=r'''
class Activity {ViewGroup content=new ViewGroup(this);Resources getResources(){return new Resources();}
 @SuppressWarnings("unchecked") <T extends View> T findViewById(int id){return (T)content;}}
class Resources {Metrics getDisplayMetrics(){return new Metrics();}}class Metrics{float density=1;}
class android{static class R{static class id{static int content=1;}}}
class Gravity{static final int CENTER=17,BOTTOM=80,CENTER_HORIZONTAL=1;}class Color{static final int WHITE=-1;}class Typeface{static final Typeface DEFAULT_BOLD=new Typeface();}
class GradientDrawable{void setColor(int c){}void setCornerRadius(float r){}void setStroke(int w,int c){}}
class KeyEvent{static final int ACTION_DOWN=0;int getAction(){return 0;}}
class MotionEvent{static final int ACTION_DOWN=0,ACTION_UP=1,ACTION_MOVE=2;int action;MotionEvent(int a){action=a;}int getActionMasked(){return action;}}
class View {
 static final int VISIBLE=0,GONE=8;static long now=0;static Map<Runnable,Long> timers=new HashMap<>();
 interface OnClickListener{void onClick(View v);}
 int visibility=0;OnClickListener click;String description;boolean clickable;ViewGroup.LayoutParams layout;
 interface OnLayoutChangeListener{void onLayoutChange(View v,int l,int t,int r,int b,int ol,int ot,int or,int ob);}
 OnLayoutChangeListener layoutListener;void addOnLayoutChangeListener(OnLayoutChangeListener l){layoutListener=l;}
 ViewGroup.LayoutParams getLayoutParams(){return layout;}void setLayoutParams(ViewGroup.LayoutParams p){layout=p;}
 View(Activity a){}void setClickable(boolean b){clickable=b;}void setFocusable(boolean b){}void setContentDescription(String s){description=s;}
 void setVisibility(int v){visibility=v;}int getVisibility(){return visibility;}void setBackground(Object o){}void bringToFront(){}
 void setOnClickListener(OnClickListener l){click=l;}void tap(){click.onClick(this);}
 void removeCallbacks(Runnable r){timers.remove(r);}boolean postDelayed(Runnable r,long ms){timers.put(r,now+ms);return true;}
 static void advance(long ms){now+=ms;var ready=new ArrayList<Runnable>();timers.forEach((r,t)->{if(t<=now)ready.add(r);});for(var r:ready){timers.remove(r);r.run();}}
}
class TextView extends View{String text;TextView(Activity a){super(a);}void setText(String s){text=s;}void setTextColor(int c){}void setTextSize(float s){}void setTypeface(Typeface t){}void setGravity(int g){}}
class ViewGroup extends View{
 static class LayoutParams{static final int WRAP_CONTENT=-2;int width,height;LayoutParams(int w,int h){width=w;height=h;}}
 List<View> children=new ArrayList<>();ViewGroup(Activity a){super(a);}void addView(View v,LayoutParams p){children.add(v);v.layout=p;}
}
class FrameLayout extends ViewGroup{FrameLayout(Activity a){super(a);}static class LayoutParams extends ViewGroup.LayoutParams{int gravity,bottomMargin;LayoutParams(int w,int h,int g){super(w,h);gravity=g;}}}
class LinearLayout extends ViewGroup{static final int HORIZONTAL=0;LinearLayout(Activity a){super(a);}void setOrientation(int o){}void setGravity(int g){}
 static class LayoutParams extends ViewGroup.LayoutParams{int leftMargin,rightMargin;LayoutParams(int w,int h){super(w,h);}}}
'''
test=r'''
class PlaybackControlsTest {
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 public static void main(String[] args)throws Exception{
  var activity=new Activity();var sent=new ArrayList<String>();
  var controls=new FullscreenPlaybackControls(activity,(command,value)->sent.add(command+":"+value));
  var overlay=(FrameLayout)activity.content.children.get(0);var bar=(LinearLayout)overlay.children.get(0);
  check(overlay.visibility==8 && !overlay.clickable,"Overlay visible or blocking touches outside fullscreen");
  check(bar.children.size()==4,"Expected back, play/pause, forward and speed");
  for(View child:bar.children)check(child.description!=null && !child.description.isEmpty(),"Button without a screen-reader description");
  var back=(TextView)bar.children.get(0);var toggle=(TextView)bar.children.get(1);var forward=(TextView)bar.children.get(2);var speed=(TextView)bar.children.get(3);
  back.tap();check(sent.isEmpty(),"Command sent outside fullscreen");
  controls.setFullscreen(true);check(overlay.visibility==0 && controls.isShowing(),"Buttons missing in fullscreen");
  View.advance(2999);check(controls.isShowing(),"Hid too early");View.advance(1);check(!controls.isShowing(),"No idle hide");
  controls.onScreenTouch(new MotionEvent(MotionEvent.ACTION_MOVE));check(!controls.isShowing(),"Finger movement revealed the buttons");
  controls.onScreenTouch(new MotionEvent(MotionEvent.ACTION_DOWN));check(controls.isShowing(),"Touch did not reveal the buttons");
  back.tap();forward.tap();toggle.tap();check(sent.equals(List.of("seek:-10.0","seek:10.0","toggle:0.0")),"Wrong commands: "+sent);
  View.advance(2000);toggle.tap();View.advance(2999);check(controls.isShowing(),"Pressing a button did not keep the buttons visible");
  sent.clear();check(speed.text.equals("1x"),"Initial speed label "+speed.text);
  String[] labels={"1.25x","1.5x","2x","0.5x","0.75x","1x"};double[] rates={1.25,1.5,2.0,0.5,0.75,1.0};
  for(int i=0;i<labels.length;i++){speed.tap();check(speed.text.equals(labels[i]),"Speed label "+speed.text);check(sent.get(i).equals("rate:"+rates[i]),"Speed command "+sent.get(i));}
  speed.tap();controls.setFullscreen(false);check(overlay.visibility==8 && !controls.isShowing(),"Buttons stayed after fullscreen");
  View.advance(10000);controls.onScreenTouch(new MotionEvent(MotionEvent.ACTION_DOWN));check(!controls.isShowing(),"Touch outside fullscreen revealed the buttons");
  controls.setFullscreen(true);check(speed.text.equals("1x"),"Speed label not reset for a new fullscreen video");
  var place=(FrameLayout.LayoutParams)bar.layout;overlay.layoutListener.onLayoutChange(overlay,0,0,800,360,0,0,0,0);check(place.gravity==Gravity.CENTER && place.bottomMargin==0,"Landscape buttons not centred");
  overlay.layoutListener.onLayoutChange(overlay,0,0,360,800,0,0,0,0);place=(FrameLayout.LayoutParams)bar.layout;check(place.gravity==(Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL) && place.bottomMargin==72,"Portrait buttons overlap the side bars");
  controls.onScreenKey(new KeyEvent());check(controls.isShowing(),"Remote key did not reveal the buttons");
  System.out.println("PASS native playback buttons: -10s/play-pause/+10s/speed cycle, 3-second idle hide, touch and remote reveal, press keeps visible, inactive outside fullscreen, descriptions (Android stubs)");
 }
}
'''
with tempfile.TemporaryDirectory() as tmp:
 target=Path(tmp)/'PlaybackControlsTest.java';target.write_text('import java.util.*;\n'+test+stubs+code);subprocess.run(['java',str(target)],check=True)
