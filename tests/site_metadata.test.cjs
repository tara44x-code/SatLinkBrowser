const assert=require('node:assert/strict');
const media=require('../app/src/main/assets/media_capture/media_metadata.js');
const video={width:1080,height:1920,duration:35,playAddr:'https://cdn.test/play?token=a%2Bb',
  bitrateInfo:[
    {Bitrate:2500000,PlayAddr:{UrlKey:'v123_h264_1080p_2500000',DataSize:12000000,UrlList:['https://cdn.test/high']}},
    {Bitrate:600000,PlayAddr:{UrlKey:'v123_h264_360p_600000',DataSize:3000000,UrlList:['https://cdn.test/low']}},
    {Bitrate:1900000,PlayAddr:{UrlKey:'v123_bytevc1_720p_1900000',UrlList:['https://cdn.test/media-video-hvc1/']}}
  ]};
const item={id:'123456',video,music:{playUrl:'https://cdn.test/background.m4a'}};
const root={__DEFAULT_SCOPE__:{'webapp.video-detail':{itemInfo:{itemStruct:item}}, recommendations:[{...item,id:'999999'}]}};
const page='https://www.tiktok.com/@user/video/123456';
const formats=media.formatsFromText(JSON.stringify(root),page);
assert.equal(formats.length,4,'Only current post renditions are selectable');
assert.equal(formats.find(x=>x.url.endsWith('/high')).height,1920);
assert.equal(formats.find(x=>x.url.endsWith('/low')).height,640);
assert.equal(formats.find(x=>x.url.endsWith('/low')).width,360);
assert.equal(formats.find(x=>x.url.endsWith('/high')).hasAudio,true);
assert.equal(formats.find(x=>x.url.endsWith('/media-video-hvc1/')).hasAudio,false);
assert(!formats.some(x=>x.url.endsWith('.m4a')),'Background music must not impersonate full soundtrack');
assert.equal(formats.find(x=>x.url.includes('token=')).url,'https://cdn.test/play?token=a%2Bb');
const low=formats.find(x=>x.url.endsWith('/low'));
const merged=media.merge(low,{height:1080,width:1920,hasAudio:false,mime:'video/mp4',metadataSource:'network'});
assert.equal(merged.height,640);assert.equal(merged.hasAudio,true);
assert.equal(media.formatsFromText(JSON.stringify(root),'https://www.tiktok.com/@user/video/777777').length,0);
assert.equal(media.formatsFromText(JSON.stringify(root),'https://example.test/').length,0);
console.log('PASS TikTok passive fixtures: current post only, all supplied variants, dimensions, bitrate, muxed AAC, hvc1 exception, signed URL, metadata precedence, background-music isolation');
