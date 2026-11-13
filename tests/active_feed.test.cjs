const assert=require('node:assert/strict');
const fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const assets=path.join(__dirname,'../app/src/main/assets/media_capture');
const post=require(path.join(assets,'active_post.js'));
const media=require(path.join(assets,'media_metadata.js'));
const rect={width:400,height:700,left:0,right:400,top:0,bottom:700};
const current='https://www.tiktok.com/@owner/video/7654321098765432101';
const other='https://www.tiktok.com/@other/video/7654321098765432102';
function container(links){return {querySelectorAll(){return links.map(href=>({href}));},getAttribute(){return '';},parentElement:null};}
function video(url,links,paused=false,r=rect){return {paused,ended:false,currentSrc:url,src:url,tagName:'VIDEO',videoWidth:1080,videoHeight:1920,duration:22,mozHasAudio:true,getBoundingClientRect(){return r;},getAttribute(){return '';},querySelectorAll(){return [];},parentElement:container(links),pause(){this.paused=true;this.pauses=(this.pauses||0)+1;}};}
const playing=video('https://cdn.test/play/first',[current]);
const offscreen=video('https://cdn.test/other',[other],false,{...rect,top:900,bottom:1600});
const doc={documentElement:{clientWidth:400,clientHeight:800},querySelectorAll(){return [offscreen,playing];}};
assert.equal(post.resolve(doc,'https://www.tiktok.com/',{innerWidth:400,innerHeight:800}).url,current);
assert.equal(post.resolve(doc,'https://www.tiktok.com/@direct/video/123').url,'https://www.tiktok.com/@direct/video/123');
assert.equal(post.resolve({...doc,querySelectorAll(){return [video('blob:test',[current,other])];}},'https://www.tiktok.com/').url,'','Ambiguous recommendations cannot select a random post');
assert.equal(post.postUrl('https://tiktok.com.fake/@owner/video/123'),'');
const raw={items:[{id:'7654321098765432101',video:{playAddr:'https://cdn.test/one',width:1080,height:1920}},{id:'7654321098765432102',video:{playAddr:'https://cdn.test/two',width:720,height:1280}}]};
assert.equal(media.formatsFromText(JSON.stringify(raw),'https://www.tiktok.com/').length,0,'Root page must not capture every recommendation');
const selected=media.formatsFromText(JSON.stringify(raw),'https://www.tiktok.com/',current);
assert.equal(selected.length,1);assert.equal(selected[0].url,'https://cdn.test/one');
// Execute the real content script with the active-post resolver and its native port.
let active=playing, receiver;const sent=[],events={},timers=[];
const document={baseURI:'https://www.tiktok.com/',title:'TikTok',hidden:false,scripts:[],documentElement:{},
 querySelectorAll(selector){if(selector==='video' || selector==='video, audio')return [active];return [];},
 addEventListener(name,fn){(events[name]??=[]).push(fn);}};
const window={innerWidth:400,innerHeight:800,addEventListener(){}};window.top=window;
const context=vm.createContext({document,window,location:{href:document.baseURI,hostname:'www.tiktok.com'},navigator:{userAgent:'Test'},URL,URLSearchParams,Map,Set,WeakMap,Number,Date,performance:{now(){return 100;},getEntriesByType(){return [];}},setTimeout(fn){timers.push(fn);return timers.length;},setInterval(){},clearTimeout(){},browser:{runtime:{sendMessage(p){sent.push(p);return Promise.resolve();},connectNative(){return {onMessage:{addListener(fn){receiver=fn;}}};},sendNativeMessage(){return Promise.resolve();}}}});
for(const name of ['media_metadata.js','active_post.js','capture.js'])vm.runInContext(fs.readFileSync(path.join(assets,name),'utf8'),context);
assert.equal(sent.find(x=>x.action==='pageSnapshot').activePostUrl,current);
assert(sent.find(x=>x.action==='pageSnapshot').items.some(x=>x.width===1080 && x.hasAudio===true),'Rendered video facts missing');
receiver({action:'setPlaybackAllowed',allowed:false});assert.equal(active.paused,true);
active.paused=false;events.play.forEach(fn=>fn({target:active}));assert.equal(active.paused,true,'Hidden autoplay must be paused');
receiver({action:'setPlaybackAllowed',allowed:true});const pauses=active.pauses;assert.equal(active.pauses,pauses);
active.paused=false;events.play.forEach(fn=>fn({target:active}));assert.equal(active.paused,true,'Returning to browser cannot auto-play the paused video');
events.pointerdown.forEach(fn=>fn({isTrusted:true}));active.paused=false;events.play.forEach(fn=>fn({target:active}));assert.equal(active.paused,false,'User playback must still work');
receiver({action:'pauseMedia'});active.paused=false;events.play.forEach(fn=>fn({target:active}));assert.equal(active.paused,true,'History back must stop a returning mini-player');
active=video('https://cdn.test/play/second',[other]);receiver({action:'scanMedia'});
const latest=sent.filter(x=>x.action==='pageSnapshot').at(-1);assert.equal(latest.activePostUrl,other);assert(!latest.items.some(x=>x.url.includes('/first')),'Feed switch leaked old media');
active=video('https://cdn.test/play/third',[]);receiver({action:'scanMedia'});
const unidentified=sent.filter(x=>x.action==='pageSnapshot').at(-1);assert.equal(unidentified.activePostUrl,'');assert(!unidentified.items.some(x=>x.url.includes('/second')),'Unresolved player switch retained previous media');
console.log('PASS real feed/content integration: visible post on TikTok root, current JSON only, metadata, hidden media pause, back mini-player pause, no auto-resume, explicit user play, feed-switch isolation');
