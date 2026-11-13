// Shared passive metadata parser. Does not evaluate page JavaScript or decipher signatures.
(function(root) {
  function query(url, key) {
    try { return new URL(url).searchParams.get(key) || ''; } catch (_) { return ''; }
  }
  function positive(value) { const n=Number(value); return Number.isFinite(n) && n>0?n:0; }
  function typeOf(url, mime='') {
    const text=(query(url,'mime')+' '+mime).toLowerCase();
    if (text.includes('yt-ump') || text.includes('sabr')) return 'transport';
    if (/audio\//.test(text) || /\.(mp3|m4a|aac|ogg|opus|wav|flac)(?:$|[?#])/i.test(url)) return 'audio';
    if (/video\//.test(text) || /(mpegurl|dash\+xml|mp2t|matroska)/.test(text) ||
      /\.(mp4|webm|mkv|mov|m4v|m3u8|mpd|ts)(?:$|[?#])/i.test(url)) return 'video';
    return 'unknown';
  }
  function urlMetadata(url, mime='') {
    const protocol=/mpegurl/i.test(mime) || /\.m3u8(?:$|[?#])/i.test(url) ? 'm3u8_native'
      : /dash\+xml/i.test(mime) || /\.mpd(?:$|[?#])/i.test(url) ? 'http_dash_segments' : '';
    return {url, protocol, mime:query(url,'mime')||mime, type:typeOf(url,mime), itag:query(url,'itag'),
      duration:positive(query(url,'dur')||query(url,'duration')),
      size:positive(query(url,'clen')||query(url,'contentLength')),
      bitrate:positive(query(url,'bitrate')), fps:positive(query(url,'fps')),
      width:positive(query(url,'width')),height:positive(query(url,'height')),
      qualityLabel:query(url,'quality_label')||query(url,'quality')};
  }
  function merge(previous={}, incoming={}) {
    const item={...previous,...incoming};
    for (const [key,value] of Object.entries(previous)) {
      if ((item[key]==='' || item[key]==null || item[key]===0) && value) item[key]=value;
    }
    if (previous.mime?.includes('codecs=') && !incoming.mime?.includes('codecs=')) item.mime=previous.mime;
    if (incoming.type==='transport') item.type='transport';
    else if ((incoming.type==='unknown' || !incoming.type) && previous.type) item.type=previous.type;
    // Inferred guesses never overwrite explicit player facts.
    if (['player-json','site-json'].includes(previous.metadataSource) && !['player-json','site-json'].includes(incoming.metadataSource)) {
      for(const key of ['mime','type','width','height','fps','bitrate','size','duration','qualityLabel','hasAudio','audioCodec','videoCodec']) {
        if(previous[key]!=null && previous[key]!=='') item[key]=previous[key];
      }
    }
    return item;
  }
  function fullStreamUrl(url) {
    try {
      const u=new URL(url);
      if (!/(^|\.)googlevideo\.com$/i.test(u.hostname)) return url;
      const signed=(u.searchParams.get('sparams')||'')+','+(u.searchParams.get('lsparams')||'');
      for(const name of ['range','rn','rbuf']) {
        if (!signed.split(',').includes(name)) u.searchParams.delete(name);
      }
      return u.href;
    } catch (_) { return url; }
  }
  function key(item) {
    try {
      const u=new URL(item.url);
      for(const name of ['range','rn','rbuf']) u.searchParams.delete(name);
      return u.href;
    } catch (_) { return 'metadata:'+item.itag; }
  }
  function objectAt(text,start) {
    let depth=0, quoted=false, escaped=false;
    for(let i=start;i<text.length;i++) {
      const c=text[i];
      if(quoted) { if(escaped) escaped=false; else if(c==='\\') escaped=true; else if(c==='"') quoted=false; continue; }
      if(c==='"') quoted=true;
      else if(c==='{') depth++;
      else if(c==='}' && --depth===0) { try { return JSON.parse(text.slice(start,i+1)); } catch (_) { return null; } }
    }
    return null;
  }
  function formatsFromText(text,pageUrl,activePostUrl='') {
    const roots=[];
    try { roots.push(JSON.parse(text)); } catch (_) {}
    const assignment=/["']?ytInitialPlayerResponse["']?\s*(?:=|:)\s*(\{)/g;
    let match;
    while((match=assignment.exec(text)) && roots.length<8) {
      const obj=objectAt(text,assignment.lastIndex-1); if(obj) roots.push(obj);
    }
    const formats=[]; let visited=0;
    const activeId=query(pageUrl,'v') || (()=>{try{return new URL(pageUrl).pathname.match(/\/(?:shorts|embed)\/([^/?]+)/)?.[1]||'';}catch(_){return '';}})();
    let tikTokId='', tikTokPage=false;
    try { const u=new URL(pageUrl); tikTokPage=/(^|\.)tiktok\.com$/i.test(u.hostname); tikTokId=u.pathname.match(/\/video\/(\d+)/)?.[1]||''; if(tikTokPage && !tikTokId && activePostUrl) {const active=new URL(activePostUrl); if(/(^|\.)tiktok\.com$/i.test(active.hostname)) tikTokId=active.pathname.match(/\/video\/(\d+)/)?.[1]||'';} } catch (_) {}
    function tikTokFormats(item) {
      const video=item.video, id=String(item.id||item.aweme_id||'');
      if(!tikTokId || !id || id!==tikTokId || !video || typeof video!=='object') return false;
      const duration=positive(video.duration), width=positive(video.width), height=positive(video.height);
      function addAddress(address, extra={}) {
        const urls=typeof address==='string'?[address]:Array.isArray(address)?address.map(x=>x?.src):address?.UrlList||[];
        for(let url of urls) {
          if(typeof url!=='string') continue;
          if(url.startsWith('//')) url='https:'+url;
          let u; try {u=new URL(url);} catch (_) {continue;}
          if(!/^https?:$/.test(u.protocol) || /(^|\.)tiktok\.com$/i.test(u.hostname)) continue;
          // Same exception as the bundled TikTok extractor: hvc1 endpoints can be video-only.
          const hasAudio=!u.pathname.endsWith('/media-video-hvc1/');
          formats.push({url,type:'video',mime:'video/mp4',width,height,duration,
            size:positive(address?.DataSize),fps:0,bitrate:0,videoCodec:'h264',
            ...extra,hasAudio,audioCodec:hasAudio?'aac':'',metadataSource:'site-json',
            source:'tiktok-player-json',videoId:id,frameUrl:pageUrl});
        }
      }
      for(const rate of video.bitrateInfo||video.bitRateInfo||[]) {
        const address=rate.PlayAddr; if(!address) continue;
        const parsed=String(address.UrlKey||'').match(/v[^_]+_([^_]+)_(\d+)p_(\d+)/);
        let w=positive(address.Width), h=positive(address.Height);
        if((!w||!h) && parsed && width && height) {
          let dimension=Number(parsed[2]); if(dimension===540) dimension=576;
          if(width<height) {w=dimension;h=Math.floor(dimension*height/width/2)*2;}
          else {h=dimension;w=Math.ceil(dimension*width/height/2)*2;}
        }
        addAddress(address,{width:w,height:h,bitrate:positive(rate.Bitrate)||positive(parsed?.[3]),
          videoCodec:parsed?.[1]==='bytevc1'?'h265':parsed?.[1]||video.codecType||'h264'});
      }
      addAddress(video.playAddr);
      addAddress(video.downloadAddr,{width:0,height:0});
      // music.playUrl is background music, not necessarily the video's full soundtrack.
      return true;
    }
    function walk(obj,depth=0) {
      if(!obj || typeof obj!=='object' || depth>10 || ++visited>5000) return;
      if(tikTokPage && obj.video && (obj.id || obj.aweme_id)) { tikTokFormats(obj); return; }
      if(obj.streamingData) {
        if(activeId && obj.videoDetails?.videoId && obj.videoDetails.videoId!==activeId) return;
        const data=obj.streamingData;
        for(const f of [...(data.formats||[]),...(data.adaptiveFormats||[])]) {
          if(!f || !f.itag || !f.mimeType) continue;
          let url=f.url||'';
          // An encrypted signatureCipher is metadata, never a downloadable URL.
          if(!url && (f.signatureCipher||f.cipher)) {
            const params=new URLSearchParams(f.signatureCipher||f.cipher);
            if(!params.get('s')) {
              url=params.get('url')||'';
              if(url && params.get('sig')) { try { const u=new URL(url);u.searchParams.set(params.get('sp')||'signature',params.get('sig'));url=u.href;}catch(_){url='';} }
            }
          }
          if(url && !/^https?:\/\//i.test(url)) continue;
          const mime=f.mimeType, audioOnly=mime.startsWith('audio/');
          const codecs=mime.match(/codecs="([^"]+)"/)?.[1]||'';
          const hasAudio=audioOnly || !!f.audioQuality || !!f.audioChannels || /mp4a|opus|vorbis/.test(codecs);
          formats.push({url,mime,type:audioOnly?'audio':'video',itag:String(f.itag),
            duration:positive(f.approxDurationMs)/1000 || positive(obj.videoDetails?.lengthSeconds),
            size:positive(f.contentLength),bitrate:positive(f.bitrate||f.averageBitrate),
            fps:positive(f.fps),width:positive(f.width),height:positive(f.height),
            qualityLabel:f.qualityLabel||'',hasAudio,
            videoCodec:audioOnly?'':codecs.split(',')[0].trim(),
            audioCodec:hasAudio?(audioOnly?codecs:codecs.split(',').at(-1)?.trim()||''):'',
            metadataSource:'player-json', videoId:obj.videoDetails?.videoId||'', source:'player-json', frameUrl:pageUrl});
        }
        for(const field of ['hlsManifestUrl','dashManifestUrl']) {
          if(data[field]) formats.push({...urlMetadata(data[field]),source:'player-manifest',frameUrl:pageUrl});
        }
        return;
      }
      for(const value of Object.values(obj)) walk(value,depth+1);
    }
    roots.forEach(x=>walk(x)); return formats;
  }
  const api={query,positive,typeOf,urlMetadata,merge,key,fullStreamUrl,formatsFromText};
  root.SatLinkMedia=api;
  if(typeof module!=='undefined' && module.exports) module.exports=api;
})(globalThis);
