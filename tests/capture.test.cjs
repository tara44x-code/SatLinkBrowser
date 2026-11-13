const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const assets = path.join(__dirname, '../app/src/main/assets/media_capture');
function event() { const listeners = []; return { listeners, addListener(fn) { listeners.push(fn); } }; }
function harness(extra) {
  let sequence = 0;
  const timers = new Map();
  const context = vm.createContext({ URL, URLSearchParams, Map, Set, WeakMap, Date, Number, Array, Promise,
    setTimeout(fn, delay) { const id = ++sequence; timers.set(id, {fn, delay}); return id; },
    clearTimeout(id) { timers.delete(id); }, setInterval() {}, ...extra });
  return { context, timers, async tick() {
    const pending = [...timers.values()]; timers.clear();
    for (const timer of pending) await timer.fn();
    for (let i=0;i<20;i++) await Promise.resolve();
  }};
}
async function backgroundTests() {
  const sent = [], request = event(), beforeHeaders = event(), response = event();
  const messages = event(), updated = event(), removed = event();
  const h = harness({browser: {
    cookies: {async getAll() { return []; }}, runtime: {onMessage: messages,
      async sendNativeMessage(host, payload) { sent.push(payload); }},
    webRequest: {onBeforeRequest: request, onBeforeSendHeaders: beforeHeaders, onHeadersReceived: response},
    tabs: {onUpdated: updated, onRemoved: removed}
  }});
  vm.runInContext(fs.readFileSync(path.join(assets,'media_metadata.js'),'utf8'), h.context);
  vm.runInContext(fs.readFileSync(path.join(assets,'background.js'),'utf8'), h.context);
  const snapshot = {tabKey:'tab-one',action:'pageSnapshot',pageUrl:'https://page.test/watch',isTopFrame:true,items:[]};
  await messages.listeners[0](snapshot,{tab:{id:1},frameId:0});
  const firstTimer = [...h.timers.keys()][0];
  for (let i=0;i<100;i++) request.listeners[0]({url:`https://cdn.test/clip${i}.ts`,tabId:1,type:'media'});
  assert.equal([...h.timers.keys()][0], firstTimer, 'HLS traffic cannot starve flush timer');
  assert.equal(h.timers.size, 1);
  await h.tick();
  assert.equal(sent.at(-1).items.length,100);
  assert.equal(sent.at(-1).tabKey,'tab-one','native merged capture dropped the owning tab identity');
  await messages.listeners[0]({...snapshot,tabKey:'same-url-a'},{tab:{id:98},frameId:0});
  await messages.listeners[0]({...snapshot,tabKey:'same-url-b'},{tab:{id:99},frameId:0});
  await h.tick();
  assert(sent.some(x=>x.tabKey==='same-url-a') && sent.some(x=>x.tabKey==='same-url-b'),'same-URL tabs lost independent identity');
  await messages.listeners[0]({action:'sessionSnapshot',pageUrl:snapshot.pageUrl,isTopFrame:true,tabKey:'same-url-a'},{tab:{id:98},frameId:0});
  assert.equal(sent.at(-1).tabKey,'same-url-a','native cookie session dropped owning tab identity');
  removed.listeners[0](98);removed.listeners[0](99);
  const url='https://cdn.test/movie.mkv?token=123';
  beforeHeaders.listeners[0]({url,tabId:1,type:'media',requestHeaders:[{name:'Referer',value:'https://page.test/watch'}]});
  response.listeners[0]({url,tabId:1,type:'media',responseHeaders:[{name:'Content-Type',value:'video/x-matroska'},{name:'Content-Length',value:'1048576'},{name:'Content-Range',value:'bytes 0-1048575/4294967296'}]});
  await h.tick();
  const item=sent.at(-1).items.find(x=>x.url===url);
  assert.equal(item.type,'video'); assert.equal(item.size,4294967296);
  assert.equal(item.referer,'https://page.test/watch','response metadata preserves request headers');
  request.listeners[0]({url:'https://googlevideo.com/other.mp4',tabId:-1,type:'media'});
  await h.tick();
  assert(!sent.at(-1).items.some(x=>x.url.includes('other.mp4')));
  request.listeners[0]({url:'https://cdn.test/worker.mp4',tabId:-1,type:'media',documentUrl:snapshot.pageUrl});
  await h.tick();
  assert(sent.at(-1).items.some(x=>x.url.includes('worker.mp4')),'worker request with exact document is recovered');
  await messages.listeners[0]({...snapshot,pageUrl:'https://page.test/second'},{tab:{id:2},frameId:0});
  request.listeners[0]({url:'https://cdn.test/ambiguous.mp4',tabId:-1,type:'media',originUrl:'https://page.test/'});
  await h.tick();
  assert(sent.slice(-2).every(x=>!x.items.some(i=>i.url.includes('ambiguous.mp4'))),'same-origin tabs cannot share unidentified worker traffic');
  request.listeners[0]({url:'https://cdn.test/stale.mp4',tabId:-1,type:'media',documentUrl:'https://page.test/old'});
  await h.tick();
  assert(sent.slice(-2).every(x=>!x.items.some(i=>i.url.includes('stale.mp4'))),'another page on same origin does not match');
  removed.listeners[0](2);
  updated.listeners[0](1,{status:'loading'});
  await messages.listeners[0]({...snapshot,pageUrl:'https://page.test/next'},{tab:{id:1},frameId:0});
  await h.tick(); assert.equal(sent.at(-1).items.length,0,'navigation clears old requests');
  request.listeners[0]({url:'https://cdn.test/live.ts',tabId:1,type:'media'});
  removed.listeners[0](1); assert.equal(h.timers.size,0);
  const feed={action:'pageSnapshot',pageUrl:'https://www.tiktok.com/',isTopFrame:true,activePostUrl:'https://www.tiktok.com/@u/video/123',items:[{url:'https://cdn.test/post123.mp4',type:'video',videoId:'123'}]};
  await messages.listeners[0](feed,{tab:{id:3},frameId:0});
  request.listeners[0]({url:'https://cdn.test/old-worker.mp4',tabId:-1,type:'media',documentUrl:feed.pageUrl});
  await h.tick();assert.equal(sent.at(-1).activePostUrl,feed.activePostUrl);
  await messages.listeners[0]({...feed,activePostUrl:'https://www.tiktok.com/@u/video/456',items:[{url:'https://cdn.test/post456.mp4',type:'video',videoId:'456'}]},{tab:{id:3},frameId:0});
  await h.tick();assert(sent.at(-1).items.every(x=>!x.url.includes('123') && !x.url.includes('old-worker')),'Same-page feed change leaked previous media');
  console.log('PASS background: MKV/TS, 4GiB sizes, headers, bounded flush, tab isolation, navigation cleanup');
}
async function contentTests() {
  const sent=[], portMessage=event(), handlers={}; let observer, entries=[];
  let queryCount=0;
  const links=[{href:'https://cdn.test/movie.mkv?mime=video%2Fx-matroska'}, {href:'https://cdn.test/movie.ts'}];
  const document={baseURI:'https://page.test/watch',title:'Test',hidden:false,scripts:[],
    documentElement:{},querySelectorAll(selector) { queryCount++; return selector==='a[href]'?links:[]; },
    addEventListener(name,fn) { handlers[name]=fn; }};
  const window={addEventListener(){},fetch:async()=>({url:'',headers:{get(){return '';}}})};window.top=window;
  const h=harness({document,window,location:{href:'https://page.test/watch'},navigator:{userAgent:'Test'},
    MutationObserver: class { constructor(cb) { observer=cb; } observe() {} },
    PerformanceObserver:class { constructor(cb) { this.cb=cb; } observe() {} },
    performance:{now(){return 100;},getEntriesByType(){return entries;}},
    browser:{runtime:{async sendMessage(payload){sent.push(payload);},connectNative(){return {onMessage:portMessage};},async sendNativeMessage(){}}}
  });
  vm.runInContext(fs.readFileSync(path.join(assets,'media_metadata.js'),'utf8'),h.context);
  vm.runInContext(fs.readFileSync(path.join(assets,'capture.js'),'utf8'),h.context);
  assert.equal(sent[0].action,'pageSnapshot','initial snapshot has no 900ms wait');
  assert.equal(sent[0].items.length,2); assert(sent[0].items.every(x=>x.type==='video'));
  portMessage.listeners[0]({action:'setPlaybackAllowed',allowed:true,tabKey:'native-tab-1'});
  assert(sent.some(x=>x.action==='sessionSnapshot' && x.tabKey==='native-tab-1'),'content session did not echo native tab identity');
  assert(sent.some(x=>x.action==='pageSnapshot' && x.tabKey==='native-tab-1'),'content snapshot did not echo native tab identity');
  await h.tick();
  const baseline=queryCount;
  for(let i=0;i<100;i++) observer();
  assert.equal(queryCount,baseline,'mutation bursts do not run synchronous full-page scans');
  await h.tick(); assert(queryCount-baseline<10);
  entries=[{name:'https://cdn.test/live.m3u8',initiatorType:'fetch'}];
  observer(); await h.tick(); await h.tick();
  assert(sent.at(-1).items.some(x=>x.url.includes('live.m3u8')),'new discoveries are delivered automatically');
  const before=sent.length;
  portMessage.listeners[0]({action:'scanMedia'});
  assert.equal(sent.length,before+1,'manual scan returns even if unchanged');
  vm.runInContext("location.href='https://page.test/next'",h.context); links.length=0;entries=[{name:'https://cdn.test/old.mp4',startTime:50,initiatorType:'fetch'}];
  portMessage.listeners[0]({action:'scanMedia'});assert.equal(sent.at(-1).items.length,0);
  document.scripts=[{textContent:'var ytInitialPlayerResponse = '+JSON.stringify({videoDetails:{videoId:'current'},streamingData:{adaptiveFormats:[{itag:137,url:'https://cdn.test/videoplayback?itag=137',mimeType:'video/mp4; codecs="avc1"',height:1080,width:1920,contentLength:'90000000'}]}})+';'}];
  vm.runInContext("location.href='https://m.youtube.com/watch?v=current'",h.context);
  portMessage.listeners[0]({action:'scanMedia'});
  const format=sent.at(-1).items.find(x=>x.itag==='137');
  assert(format && format.height===1080 && format.size===90000000,'Content scanner forwards complete player metadata');
  console.log('PASS content: immediate snapshot, automatic discovery, coalesced mutations, forced scan, SPA cleanup');
}
(async()=>{await backgroundTests();await contentTests();})().catch(e=>{console.error(e);process.exitCode=1;});
