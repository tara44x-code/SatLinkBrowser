const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const assets=path.join(__dirname,'../app/src/main/assets/media_capture');
const fixture=`#EXTM3U
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="Main",URI="sound.m3u8"
#EXT-X-STREAM-INF:BANDWIDTH=900000,RESOLUTION=640x360,CODECS="avc1.4d,mp4a.40.2",FRAME-RATE=30
low/index.m3u8?sig=low
#EXT-X-STREAM-INF:BANDWIDTH=6000000,RESOLUTION=1920x1080,CODECS="avc1.64",AUDIO="audio",FRAME-RATE=60
../high/playlist?sig=high
#EXT-X-STREAM-INF:BANDWIDTH=15000000,RESOLUTION=3840x2160,CODECS="hvc1.1,mp4a.40.2",FRAME-RATE=60
https://cdn.test/4k.m3u8?sig=signed
`;
const context=vm.createContext({URL,Map,Set,Date,Number,Array,Promise,AbortController,
 timers:new Map(),sequence:0,setTimeout(fn,delay){const id=++context.sequence;context.timers.set(id,{fn,delay});return id;},clearTimeout(id){context.timers.delete(id);}});
vm.runInContext(fs.readFileSync(path.join(assets,'hls_metadata.js'),'utf8'),context);
const rows=context.SatLinkHls.parse(fixture,'https://cdn.test/root/master.m3u8');
assert.equal(rows.length,3);assert.equal(rows[1].url,'https://cdn.test/high/playlist?sig=high');
assert.equal(rows[2].height,2160);assert.equal(rows[2].fps,60);assert(rows[1].hasAudio);
assert(rows[2].formatSelector.includes('[height=2160]'));
assert.equal(context.SatLinkHls.parse('#EXTM3U\n#EXTINF:10,\nchunk1.ts\n#EXTINF:10,\nchunk2.ts','https://cdn.test/master').length,0,'segments must not become video qualities');
assert.equal(context.SatLinkHls.parse('#EXTM3U\n#EXT-X-STREAM-INF:RESOLUTION=999x999\njavascript:bad','https://cdn.test/master').length,0);
const event=()=>({listeners:[],addListener(fn){this.listeners.push(fn);}});
const messages=event(),updated=event(),removed=event(),sent=[],pending=[];
context.fetch=(url,options)=>new Promise(resolve=>pending.push({url,options,resolve}));
context.browser={runtime:{onMessage:messages,async sendNativeMessage(host,data){sent.push(data);}},cookies:{async getAll(){return[];}},
 webRequest:{onBeforeRequest:event(),onBeforeSendHeaders:event(),onHeadersReceived:event()},tabs:{onUpdated:updated,onRemoved:removed}};
vm.runInContext(fs.readFileSync(path.join(assets,'media_metadata.js'),'utf8'),context);
vm.runInContext(fs.readFileSync(path.join(assets,'background.js'),'utf8'),context);
async function tick(){for(const [id,t] of [...context.timers])if(t.delay===100){context.timers.delete(id);await t.fn();}for(let i=0;i<30;i++)await Promise.resolve();}
async function snapshot(pageUrl,items){await messages.listeners[0]({action:'pageSnapshot',isTopFrame:true,pageUrl,items},{tab:{id:1},frameId:0});await tick();}
(async()=>{
 const master='https://cdn.test/root/master.m3u8';
 await snapshot('https://page.test/watch',[{url:master,type:'video',mime:'application/vnd.apple.mpegurl'}]);
 assert.equal(pending.length,1);assert.equal(pending[0].options.credentials,'include');
 pending.shift().resolve({ok:true,url:master,headers:{get(){return '';}},async text(){return fixture;}});
 for(let i=0;i<30;i++)await Promise.resolve();await tick();
 const items=sent.at(-1).items;
 assert.equal(items.length,3,'master placeholder replaced by three actual variants');
 assert(items.every(i=>i.masterUrl===master && i.referer==='https://page.test/watch'));
 assert.equal(pending.length,0,'extension fetch must not recursively fetch every variant');
 await snapshot('https://page.test/next',[{url:'https://cdn.test/next.m3u8',type:'video'}]);
 assert.equal(pending.length,1);const stale=pending.shift();
 await snapshot('https://page.test/third',[]);
 stale.resolve({ok:true,url:stale.url,headers:{get(){return ''; }},async text(){return fixture;}});
 for(let i=0;i<30;i++)await Promise.resolve();await tick();
 assert.equal(sent.at(-1).items.length,0,'old playlist completion cannot enter a new page');
 console.log('PASS HLS: 360/1080p60/4K, relative signed URLs, separate audio groups, no segment cards, exact selectors, credentials, bounded fetch, no recursion, navigation isolation');
})().catch(error=>{console.error(error);process.exitCode=1;});
