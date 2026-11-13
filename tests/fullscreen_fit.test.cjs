const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
class Style {
 constructor(){this.values=new Map();}
 getPropertyValue(k){return this.values.get(k)?.[0]||'';}
 getPropertyPriority(k){return this.values.get(k)?.[1]||'';}
 setProperty(k,v,p=''){this.values.set(k,[v,p]);}
 removeProperty(k){this.values.delete(k);}
}
class Element {
 constructor(tag){this.tagName=tag;this.style=new Style();this.children=[];this.events={};this.paused=false;this.rect=tag==='BUTTON'?{width:120,height:40,left:0,top:0}:{width:640,height:360,left:0,top:0};this.textContent='';}
 appendChild(e){e.parentElement=this;this.children.push(e);}
 remove(){this.parentElement.children=this.parentElement.children.filter(x=>x!==this);}
 contains(e){return e===this||this.children.some(c=>c.contains(e));}
 querySelectorAll(sel){return this.children.flatMap(c=>[...(sel==='*'||c.tagName==='VIDEO'?[c]:[]),...c.querySelectorAll(sel)]);}
 getBoundingClientRect(){return {...this.rect,left:parseFloat(this.style.getPropertyValue('left'))||0,top:parseFloat(this.style.getPropertyValue('top'))||0};}
 addEventListener(k,f){this.events[k]=f;}
 setAttribute(){}
}
const document={events:{},fullscreenElement:null,addEventListener(k,f){this.events[k]=f;},createElement(t){return new Element(t.toUpperCase());}};
const window={innerWidth:1000,innerHeight:600,events:{},addEventListener(k,f){this.events[k]=f;}};
let observers=[],timers=new Map(),now=0,sequence=0;
class MutationObserver {constructor(f){this.fn=f;observers.push(this);}observe(){this.active=true;}disconnect(){this.active=false;}}
vm.runInNewContext(fs.readFileSync(__dirname+'/../app/src/main/assets/media_capture/fullscreen_fit.js','utf8'),{document,window,MutationObserver,Map,Array,Object,Number,setTimeout(f,delay){const id=++sequence;timers.set(id,{f,at:now+delay});return id;},clearTimeout(id){timers.delete(id);}});
function advance(ms){const end=now+ms;for(;;){const entry=[...timers.entries()].filter(([,t])=>t.at<=end).sort((a,b)=>a[1].at-b[1].at)[0];if(!entry)break;const [id,t]=entry;timers.delete(id);now=t.at;t.f();}now=end;}
function flush(){advance(50);}
function enter(e){document.fullscreenElement=e;document.events.fullscreenchange();}
function exit(){document.fullscreenElement=null;document.events.fullscreenchange();}
const root=new Element('DIV'),wrap=new Element('DIV'),v=new Element('VIDEO');root.appendChild(wrap);wrap.appendChild(v);
v.style.setProperty('object-fit','contain');v.style.setProperty('width','640px');wrap.style.setProperty('transform','translateX(2px)');
enter(root);
assert.equal(v.style.getPropertyValue('object-fit'),'fill');assert.equal(v.style.getPropertyValue('width'),'100vw');
assert.equal(v.style.getPropertyValue('height'),'100vh');assert.equal(wrap.style.getPropertyValue('transform'),'none');
let b=root.children.find(x=>x.tagName==='BUTTON');assert.equal(b.textContent,'عرض كامل');
assert.equal(b.style.getPropertyValue('visibility'),'visible');
advance(1999);assert.equal(b.style.getPropertyValue('visibility'),'visible');
advance(1);assert.equal(b.style.getPropertyValue('visibility'),'hidden');assert.equal(b.style.getPropertyValue('pointer-events'),'none');
// Mutation/layout callbacks must not reopen a hidden button.
observers.at(-1).fn();flush();assert.equal(b.style.getPropertyValue('visibility'),'hidden');
const interaction=document.events.pointerdown;
interaction({target:v});assert.equal(b.style.getPropertyValue('visibility'),'visible');
advance(1500);document.events.pointerup({target:v});advance(1999);assert.equal(b.style.getPropertyValue('visibility'),'visible');
advance(1);assert.equal(b.style.getPropertyValue('visibility'),'hidden');
interaction({target:v});advance(1000);interaction({target:v});advance(1999);assert.equal(b.style.getPropertyValue('visibility'),'visible');
advance(1);assert.equal(b.style.getPropertyValue('visibility'),'hidden');
interaction({target:v});
b.events.click({preventDefault(){},stopPropagation(){}});assert.equal(v.style.getPropertyValue('object-fit'),'contain');
document.activeElement=b;b.blur=()=>{document.activeElement=null;};advance(2000);assert.equal(document.activeElement,null);assert.equal(b.style.getPropertyValue('visibility'),'hidden');
window.events.orientationchange();flush();assert.equal(v.style.getPropertyValue('object-fit'),'contain');
v.style.setProperty('width','50px');observers.at(-1).fn();flush();assert.equal(v.style.getPropertyValue('width'),'100vw');
v.style.setProperty('background','red');exit();assert.equal(v.style.getPropertyValue('width'),'640px');
assert.equal(v.style.getPropertyValue('object-fit'),'contain');assert.equal(v.style.getPropertyValue('background'),'red');
assert.equal(wrap.style.getPropertyValue('transform'),'translateX(2px)');assert(!root.children.some(x=>x.tagName==='BUTTON'));
assert(!observers.at(-1).active);assert.equal(timers.size,0);advance(2000);assert.equal(root.children.filter(x=>x.tagName==='BUTTON').length,0);
// Direct video fullscreen and keyboard switching, no unrenderable button.
enter(v);assert.equal(v.style.getPropertyValue('object-fit'),'fill');
document.events.keydown({key:'z',target:v,preventDefault(){}});assert.equal(v.style.getPropertyValue('object-fit'),'contain');
document.events.touchstart({touches:[{},{}],preventDefault(){}});assert.equal(v.style.getPropertyValue('object-fit'),'fill');exit();
// Frame with no accessible video must not be resized or access frame content.
const frame=new Element('IFRAME');enter(frame);assert.equal(frame.style.values.size,0);exit();
// Replacing a site player restores old element and fills the new one.
enter(root);const replacement=new Element('VIDEO');wrap.children=[];wrap.appendChild(replacement);observers.at(-1).fn();flush();
assert.equal(v.style.getPropertyValue('width'),'640px');assert.equal(replacement.style.getPropertyValue('object-fit'),'fill');
window.events.pagehide();assert.equal(replacement.style.values.size,0);
assert.equal(timers.size,0);
// Default middle-right, long press and bounded drag; release must not toggle video.
enter(root);b=root.children.find(x=>x.tagName==='BUTTON');
assert.equal(b.style.getPropertyValue('left'),'751.04px');assert.equal(b.style.getPropertyValue('top'),'280px');
function pointer(x,y,id=1){return {pointerId:id,clientX:x,clientY:y,button:0,preventDefault(){},stopPropagation(){}};}
b.events.pointerdown(pointer(900,300));advance(449);b.events.pointermove(pointer(903,303));
assert.equal(b.style.getPropertyValue('left'),'751.04px');advance(1);
b.events.pointermove(pointer(400,200));assert(Math.abs(parseFloat(b.style.getPropertyValue('left'))-251.04)<0.01);assert.equal(b.style.getPropertyValue('top'),'180px');
advance(3000);assert.equal(b.style.getPropertyValue('visibility'),'visible');
b.events.pointerup(pointer(400,200));const fit=replacement.style.getPropertyValue('object-fit');
b.events.click({...pointer(400,200),detail:1});assert.equal(replacement.style.getPropertyValue('object-fit'),fit);
advance(2000);assert.equal(b.style.getPropertyValue('visibility'),'hidden');
// Position persists when re-entering fullscreen in same page, and rotation bounds it.
exit();enter(root);b=root.children.find(x=>x.tagName==='BUTTON');assert(Math.abs(parseFloat(b.style.getPropertyValue('left'))-251.04)<0.01);
window.innerWidth=600;window.innerHeight=1000;window.events.resize();flush();
assert(parseFloat(b.style.getPropertyValue('left'))<=472);assert(parseFloat(b.style.getPropertyValue('top'))<=952);
b.events.pointerdown(pointer(400,200));advance(450);b.events.pointermove(pointer(-1000,5000));
assert.equal(b.style.getPropertyValue('left'),'8px');assert.equal(b.style.getPropertyValue('top'),'952px');
b.events.pointercancel(pointer(-1000,5000));exit();assert.equal(timers.size,0);
// Short touch keeps the original click behavior; pending long press cancels on exit.
enter(root);b=root.children.find(x=>x.tagName==='BUTTON');b.events.pointerdown(pointer(20,30));advance(100);b.events.pointerup(pointer(20,30));
const previous=replacement.style.getPropertyValue('object-fit');b.events.click({...pointer(20,30),detail:1});assert.notEqual(replacement.style.getPropertyValue('object-fit'),previous);
b.events.pointerdown(pointer(20,30));exit();assert.equal(timers.size,0);
console.log('PASS fullscreen fit: default right-center, long-press drag, drag suppresses click, viewport bounds, rotation, same-page position retention, cancellation, short-tap toggle, 2-second idle hide, screen touch show/reset, noninteractive hidden button, timer cleanup, stretch/contain without crop, responsive viewport, style enforcement/restoration, controls, native-video key, inaccessible frame, player replacement, page exit');
