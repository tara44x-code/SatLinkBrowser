// SL.Tube's master-playlist discovery adapted to the SatLink session bridge.
// Only EXT-X-STREAM-INF variants are qualities: media segments never become cards.
(function(root) {
  function attributes(line) {
    const result={};
    const pattern=/([A-Z0-9-]+)=("[^"]*"|[^,]*)/g; let match;
    while((match=pattern.exec(line))) result[match[1]]=match[2].replace(/^"|"$/g,'');
    return result;
  }
  function parse(text,masterUrl) {
    if(typeof text!=='string' || text.length>2_000_000 || !text.trimStart().startsWith('#EXTM3U')) return [];
    const result=[]; let pending=null;
    for(const raw of text.split(/\r?\n/)) {
      const line=raw.trim();
      if(line.startsWith('#EXT-X-STREAM-INF:')) {pending=attributes(line);continue;}
      if(!line || line.startsWith('#') || !pending) continue;
      const data=pending; pending=null;
      let url;try {url=new URL(line,masterUrl).href;}catch(_){continue;}
      if(!/^https?:\/\//i.test(url)) continue;
      const dimensions=(data.RESOLUTION||'').match(/^(\d+)x(\d+)$/i);
      const width=dimensions?Number(dimensions[1]):0,height=dimensions?Number(dimensions[2]):0;
      const fps=Number(data['FRAME-RATE'])||0;
      const codecs=data.CODECS||'';
      const videoCodec=codecs.split(',').find(c=>/^(avc|hev|hvc|vp0|av01)/i.test(c))||'';
      const audioCodec=codecs.split(',').find(c=>/^(mp4a|opus|ac-3|ec-3)/i.test(c))||'';
      const filter=height>0?'[height='+height+']'+(fps>0?'[fps<='+Math.ceil(fps)+']':''):'';
      result.push({url,masterUrl,protocol:'m3u8_native',type:'video',mime:'application/vnd.apple.mpegurl',
        width,height,fps,videoCodec,audioCodec,hasAudio:!!audioCodec||!!data.AUDIO,
        bitrate:Number(data['AVERAGE-BANDWIDTH']||data.BANDWIDTH)||0,
        formatSelector:height>0?'bestvideo'+filter+'+bestaudio/best'+filter+'/bestvideo'+filter:'',
        qualityLabel:height>0?height+'p':'',source:'hls-master',metadataSource:'hls-master'});
      if(result.length>=100) break;
    }
    return result;
  }
  root.SatLinkHls={parse};
})(globalThis);
