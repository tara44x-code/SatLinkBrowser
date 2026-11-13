const assert=require('node:assert/strict');
const path=require('node:path');
const fs=require('node:fs');
const vm=require('node:vm');
const assets=path.join(__dirname,'../app/src/main/assets/media_capture');
const media=require(path.join(assets,'media_metadata.js'));
const videoId='KnguPRCVUss';
const player={videoDetails:{videoId,lengthSeconds:'300'},streamingData:{
  formats:[{itag:18,url:'https://r1.googlevideo.com/videoplayback?itag=18',mimeType:'video/mp4; codecs="avc1.42001E, mp4a.40.2"',width:640,height:360,fps:30,contentLength:'12000000',bitrate:320000,qualityLabel:'360p',audioQuality:'AUDIO_QUALITY_MEDIUM'}],
  adaptiveFormats:[
    {itag:137,signatureCipher:'url=https%3A%2F%2Fr1.googlevideo.com%2Fvideoplayback%3Fitag%3D137&s=encrypted',mimeType:'video/mp4; codecs="avc1.640028"',width:1920,height:1080,fps:60,contentLength:'100000000',qualityLabel:'1080p60',bitrate:2666666},
    {itag:140,url:'https://r1.googlevideo.com/videoplayback?itag=140',mimeType:'audio/mp4; codecs="mp4a.40.2"',contentLength:'5000000',bitrate:128000,audioChannels:2}
  ]}};
const page='https://m.youtube.com/watch?v='+videoId;
const text='var ytInitialPlayerResponse = '+JSON.stringify(player)+'; var unrelated = "}";';
const formats=media.formatsFromText(text,page);
assert.equal(formats.length,3);
const video=formats.find(f=>f.itag==='137');
assert.equal(video.url,'','encrypted signature is not presented as a resolved URL');
assert.equal(video.height,1080);assert.equal(video.fps,60);assert.equal(video.size,100000000);
assert.equal(formats.find(f=>f.itag==='18').hasAudio,true);
assert.equal(formats.find(f=>f.itag==='140').type,'audio');
assert.equal(media.formatsFromText(text,'https://youtube.com/watch?v=AnotherVideo').length,0);
assert.equal(media.typeOf('https://r1.googlevideo.com/videoplayback','application/vnd.yt-ump'),'transport');
const ranged='https://r1.googlevideo.com/videoplayback?itag=137&sig=abc%2Bdef&sparams=itag&range=0-999&rn=12';
const full=media.fullStreamUrl(ranged);
assert(!full.includes('range='));assert.equal(media.query(full,'sig'),'abc+def');
assert(media.fullStreamUrl(ranged.replace('sparams=itag','sparams=itag,range')).includes('range='));
assert.equal(media.fullStreamUrl('https://another.test/movie.mp4?range=0-99'),'https://another.test/movie.mp4?range=0-99');
console.log('PASS player metadata: exact resolution/FPS/size, muxed audio, audio-only, encrypted cipher, video identity, UMP rejection, full signed stream');
function event(){const callbacks=[];return{callbacks,addListener(fn){callbacks.push(fn);}};}
async function integration(){
 const before=event(),headers=event(),request=event(),messages=event(),updated=event(),removed=event();
 const sent=[],timers=new Map();let next=0;
 const browser={cookies:{async getAll(){return[];}},runtime:{onMessage:messages,async sendNativeMessage(_,p){sent.push(p);}},
 webRequest:{onBeforeRequest:request,onBeforeSendHeaders:before,onHeadersReceived:headers},tabs:{onUpdated:updated,onRemoved:removed}};
 const context=vm.createContext({URL,URLSearchParams,Map,Set,Date,Number,Promise,browser,
 setTimeout(fn){const id=++next;timers.set(id,fn);return id;},clearTimeout(id){timers.delete(id);}});
 vm.runInContext(fs.readFileSync(path.join(assets,'media_metadata.js'),'utf8'),context);
 vm.runInContext(fs.readFileSync(path.join(assets,'background.js'),'utf8'),context);
 await messages.callbacks[0]({action:'pageSnapshot',pageUrl:page,isTopFrame:true,items:formats},{tab:{id:1},frameId:0});
 for(let i=0;i<7;i++){
  const url=ranged.replace('range=0-999','range='+i+'-'+(i+99)).replace('rn=12','rn='+i);
  request.callbacks[0]({url,tabId:1,type:'media'});
  headers.callbacks[0]({url,tabId:1,type:'media',responseHeaders:[{name:'Content-Type',value:'video/mp4'},{name:'Content-Length',value:'100'}]});
 }
 request.callbacks[0]({url:'https://r1.googlevideo.com/videoplayback?itag=999',tabId:1,type:'media'});
 headers.callbacks[0]({url:'https://r1.googlevideo.com/videoplayback?itag=999',tabId:1,type:'media',responseHeaders:[{name:'Content-Type',value:'application/vnd.yt-ump'}]});
 for(const fn of [...timers.values()]) await fn();
 for(let i=0;i<30;i++) await Promise.resolve();
 const items=sent.at(-1).items;
 assert.equal(items.filter(i=>i.itag==='137').length,1,'Seven byte ranges must not become seven qualities');
 const captured=items.find(i=>i.itag==='137');
 assert.equal(captured.height,1080);assert.equal(captured.fps,60);assert.equal(captured.size,100000000);
 assert(captured.mime.includes('codecs='));assert(!captured.url.includes('range='));
 assert(items.some(i=>i.type==='audio'&&i.itag==='140'),'Audio must survive network merging');
 assert(!items.some(i=>i.itag==='999'),'UMP transport is not a playable MP4');
 console.log('PASS capture integration: player metadata + seven network ranges produce one 1080p60 video, one audio source, exact total size');
}
integration().catch(error=>{console.error(error);process.exitCode=1;});
