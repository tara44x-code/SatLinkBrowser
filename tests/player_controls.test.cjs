// Executes the real content script and drives the app's touch playback commands.
const assert=require('node:assert/strict');
const fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const assets=path.join(__dirname,'../app/src/main/assets/media_capture');
function video(width,height){return {tagName:'VIDEO',paused:false,ended:false,currentTime:50,duration:120,playbackRate:1,plays:0,pauses:0,
 currentSrc:'https://cdn.test/v',src:'https://cdn.test/v',videoWidth:1280,videoHeight:720,mozHasAudio:true,parentElement:null,
 getBoundingClientRect(){return {width,height,left:0,top:0,right:width,bottom:height};},getAttribute(){return '';},querySelectorAll(){return [];},
 pause(){this.paused=true;this.pauses++;},play(){this.paused=false;this.plays++;return Promise.resolve();}};}
function load(fullscreen,videos){
 let receiver;const events={};
 const document={baseURI:'https://site.test/watch',title:'Site',hidden:false,scripts:[],documentElement:{},fullscreenElement:fullscreen,
  querySelectorAll(selector){return selector==='video' || selector==='video, audio'?videos:[];},
  addEventListener(name,fn){(events[name]??=[]).push(fn);}};
 const window={innerWidth:800,innerHeight:450,addEventListener(){}};window.top=window;
 const context=vm.createContext({document,window,location:{href:document.baseURI,hostname:'site.test'},navigator:{userAgent:'Test'},URL,URLSearchParams,Map,Set,WeakMap,Number,Date,Array,Math,Promise,
  performance:{now(){return 100;},getEntriesByType(){return [];}},setTimeout(){return 1;},setInterval(){},clearTimeout(){},
  browser:{runtime:{sendMessage(){return Promise.resolve();},connectNative(){return {onMessage:{addListener(fn){receiver=fn;}}};},sendNativeMessage(){return Promise.resolve();}}}});
 for(const name of ['media_metadata.js','active_post.js','capture.js'])vm.runInContext(fs.readFileSync(path.join(assets,name),'utf8'),context);
 return {send:(command,value,allowed=true)=>receiver({action:'playerControl',command,value,allowed}),receiver,events};
}
const small=video(160,90),main=video(800,450);
const container={tagName:'DIV',querySelectorAll(selector){return selector==='video'?[small,main]:[];}};
let page=load(container,[small,main]);
page.send('seek',10);assert.equal(main.currentTime,60,'Forward seek');assert.equal(small.currentTime,50,'Thumbnail video moved');
page.send('seek',-10);page.send('seek',-10);assert.equal(main.currentTime,40,'Backward seek');
page.send('seek',-500);assert.equal(main.currentTime,0,'Seek before the start');
page.send('seek',9999);assert.equal(main.currentTime,120,'Seek past the end');
page.send('seek',NaN);assert.equal(main.currentTime,120,'Invalid seek value applied');
page.send('toggle',0);assert.equal(main.paused,true,'Pause');
page.send('toggle',0);assert.equal(main.paused,false,'Play');assert.equal(main.plays,1);
page.events.play.forEach(fn=>fn({target:main}));assert.equal(main.paused,false,'App play button was blocked by the autoplay guard');
page.send('rate',1.5);assert.equal(main.playbackRate,1.5,'Speed');
page.send('rate',99);assert.equal(main.playbackRate,1.5,'Out-of-range speed applied');
page.send('seek',-20,false);assert.equal(main.currentTime,120,'Command for a hidden tab applied');
// After the app paused the page (history back), the app play button is an explicit user request.
page.receiver({action:'pauseMedia'});assert.equal(main.paused,true);
page.send('toggle',0);page.events.play.forEach(fn=>fn({target:main}));assert.equal(main.paused,false,'Explicit play after an app pause failed');
// A parent frame holding only the fullscreen iframe must not touch its own videos.
const outer=video(300,200);page=load({tagName:'IFRAME'},[outer]);page.send('seek',10);page.send('toggle',0);
assert.equal(outer.currentTime,50);assert.equal(outer.paused,false,'Parent frame answered for the player frame');
// Outside fullscreen the buttons do nothing.
const idle=video(800,450);page=load(null,[idle]);page.send('seek',10);assert.equal(idle.currentTime,50,'Command applied outside fullscreen');
// The video element itself in fullscreen.
const direct=video(800,450);page=load(direct,[direct]);page.send('seek',5);assert.equal(direct.currentTime,55,'Direct video fullscreen');
console.log('PASS touch playback: +/-10s with bounds, play/pause, autoplay guard respected, speed limits, largest fullscreen video only, iframe owner only, hidden tab ignored');
