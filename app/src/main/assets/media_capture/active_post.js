(function(root) {
  function isTikTok(url) {
    try {return /(^|\.)tiktok\.com$/i.test(new URL(url).hostname);} catch (_) {return false;}
  }
  function postUrl(url, base) {
    try {
      const u=new URL(url,base);
      if(!isTikTok(u.href) || !/^https?:$/.test(u.protocol) || !/^\/@[^/]+\/video\/\d+\/?$/.test(u.pathname)) return '';
      u.hash=''; return u.href;
    } catch (_) {return '';}
  }
  function visibleVideo(doc, viewport={}) {
    let best=null, score=-1;
    const width=viewport.innerWidth||doc.documentElement?.clientWidth||0;
    const height=viewport.innerHeight||doc.documentElement?.clientHeight||0;
    for(const video of doc.querySelectorAll('video')) {
      const r=video.getBoundingClientRect?.();
      if(!r || r.width<=0 || r.height<=0) continue;
      const area=Math.max(0,Math.min(r.right,width||r.right)-Math.max(r.left,0))
        *Math.max(0,Math.min(r.bottom,height||r.bottom)-Math.max(r.top,0));
      if(area<=0) continue;
      const next=(video.paused===false && !video.ended?1e12:0)+area;
      if(next>score) {score=next;best=video;}
    }
    return best;
  }
  function resolve(doc,pageUrl,viewport={}) {
    if(!isTikTok(pageUrl)) return {url:'',video:null};
    const video=visibleVideo(doc,viewport);
    const direct=postUrl(pageUrl,pageUrl);
    if(direct) return {url:direct,video};
    if(!video) return {url:'',video:null};
    // Search only the visible player's nearest container, not all recommended links.
    let node=video;
    for(let depth=0;node && depth<10;depth++,node=node.parentElement) {
      const candidates=new Map();
      for(const a of node.querySelectorAll?.('a[href]')||[]) {
        const url=postUrl(a.href,pageUrl);
        if(url) candidates.set(new URL(url).pathname,url);
      }
      if(candidates.size===1) return {url:candidates.values().next().value,video};
      if(candidates.size>1) break;
      for(const key of ['data-video-id','data-item-id','data-id']) {
        const id=node.getAttribute?.(key)||'';
        if(/^\d{15,25}$/.test(id)) return {url:'https://www.tiktok.com/@_/video/'+id,video};
      }
    }
    return {url:'',video};
  }
  const api={isTikTok,postUrl,visibleVideo,resolve};
  root.SatLinkActivePost=api;
  if(typeof module!=='undefined' && module.exports) module.exports=api;
})(globalThis);
