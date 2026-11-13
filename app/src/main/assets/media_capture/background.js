const networkByTab = new Map();
const unattributedNetwork = new Map();
const frameSnapshotsByTab = new Map();
const topSnapshotByTab = new Map();
const flushTimers = new Map();
const hlsRequests = new Map();
const pageGeneration = new Map();
const MAX_PER_TAB = 1500;
const MAX_AGE_MS = 30 * 60 * 1000;

function isHls(item) {
  return item?.metadataSource !== 'hls-master' &&
    (/mpegurl/i.test(item?.mime || '') || /\.m3u8(?:$|[?#])/i.test(item?.url || ''));
}

function expandHls(tabId, item) {
  const top=topSnapshotByTab.get(tabId);
  if(!top || !isHls(item) || typeof fetch!=='function' || typeof SatLinkHls==='undefined') return;
  const generation=pageGeneration.get(tabId)||0;
  const key=tabId+'|'+generation+'|'+item.url;
  const previous=hlsRequests.get(key);
  if(previous && Date.now()-previous.time<300000) return;
  const entry={time:Date.now(),variants:[]};hlsRequests.set(key,entry);
  if(hlsRequests.size>200) hlsRequests.delete(hlsRequests.keys().next().value);
  const controller=new AbortController();
  const timeout=setTimeout(()=>controller.abort(),5000);
  (async()=>{
    try {
      const response=await fetch(item.url,{credentials:'include',signal:controller.signal,
        headers:{Referer:item.referer||top.pageUrl}});
      if(!response.ok || Number(response.headers.get('content-length'))>2_000_000) return;
      // Read a bounded body instead of loading an unbounded live playlist into memory.
      let text='';
      if(response.body?.getReader && typeof TextDecoder==='function') {
        const reader=response.body.getReader(),decoder=new TextDecoder();let size=0;
        try {
          while(true) {
            const part=await reader.read();if(part.done) break;
            size+=part.value.byteLength;
            if(size>2_000_000) {await reader.cancel();return;}
            text+=decoder.decode(part.value,{stream:true});
          }
          text+=decoder.decode();
        } finally {reader.releaseLock();}
      } else text=await response.text();
      if((pageGeneration.get(tabId)||0)!==generation || topSnapshotByTab.get(tabId)?.pageUrl!==top.pageUrl) return;
      entry.variants=SatLinkHls.parse(text,response.url||item.url);
      for(const variant of entry.variants) remember(tabId,{...variant,masterUrl:item.url,
        referer:item.referer||top.pageUrl,userAgent:item.userAgent||top.userAgent,
        frameUrl:item.frameUrl||top.pageUrl,resolvedFromNetwork:true});
    } catch(_) {} finally {clearTimeout(timeout);}
  })();
}

function clearHls(tabId) {
  pageGeneration.set(tabId,(pageGeneration.get(tabId)||0)+1);
  for(const key of hlsRequests.keys()) if(key.startsWith(tabId+'|')) hlsRequests.delete(key);
}

function decodedParam(url, key) {
  try { return new URL(url).searchParams.get(key) || ''; } catch (_) { return ''; }
}

function numericParam(url, ...keys) {
  for (const key of keys) {
    const value = Number(decodedParam(url, key));
    if (Number.isFinite(value) && value > 0) return value;
  }
  return 0;
}

function inferType(url, mime = '') {
  const text = `${decodedParam(url, 'mime')} ${mime} ${url}`.toLowerCase();
  if (/audio\//.test(text) || /\.(mp3|m4a|aac|ogg|opus|wav|flac)(?:$|[?#])/.test(text)) return 'audio';
  if (/video\//.test(text) || /(mpegurl|dash\+xml|mp2t|matroska)/.test(text) ||
      /\.(mp4|webm|mkv|mov|m4v|m3u8|mpd|m4s|ts)(?:$|[?#])/.test(text)) return 'video';
  return 'unknown';
}

function isMediaRequest(url, mime = '') {
  return inferType(url, mime) !== 'unknown' ||
    /(videoplayback|manifest|playlist)(?:$|[?#/&])/i.test(url);
}

function mediaItem(url, mime = '', source = 'webRequest', size = 0, headers = {}) {
  return {
    url,
    protocol: /mpegurl/i.test(mime) || /\.m3u8(?:$|[?#])/i.test(url) ? 'm3u8_native'
      : /dash\+xml/i.test(mime) || /\.mpd(?:$|[?#])/i.test(url) ? 'http_dash_segments' : '',
    mime: decodedParam(url, 'mime') || mime || '',
    type: SatLinkMedia.typeOf(url, mime),
    source,
    duration: numericParam(url, 'dur', 'duration'),
    size: size || numericParam(url, 'clen', 'contentLength', 'content_length'),
    bitrate: numericParam(url, 'bitrate', 'abr', 'tbr'),
    fps: numericParam(url, 'fps'),
    width: numericParam(url, 'width'),
    height: numericParam(url, 'height'),
    qualityLabel: decodedParam(url, 'quality_label') || decodedParam(url, 'quality'),
    itag: decodedParam(url, 'itag'),
    referer: headers.referer || '',
    origin: headers.origin || '',
    userAgent: headers.userAgent || '',
    capturedAt: Date.now(),
    documentUrl: headers.documentUrl || "",
    initiator: headers.initiator || ""
  };
}

function remember(tabId, item) {
  if (!item?.url) return;
  const unattributed=tabId==null || tabId<0;
  let map = unattributed ? unattributedNetwork : networkByTab.get(tabId);
  if (!map && tabId >= 0) {
    map = new Map();
    networkByTab.set(tabId, map);
  }
  const key = SatLinkMedia.key(item);
  map.set(key, SatLinkMedia.merge(map.get(key), {...item, capturedAt: Date.now()}));
  if(unattributed) {
    for(const id of topSnapshotByTab.keys()) scheduleFlush(id);
  } else scheduleFlush(tabId);
  if (map.size > MAX_PER_TAB) {
    const entries = [...map.entries()].sort((a, b) => (a[1].capturedAt || 0) - (b[1].capturedAt || 0));
    for (let i = 0; i < entries.length - MAX_PER_TAB; i++) map.delete(entries[i][0]);
  }
}

async function cookiesFor(url) {
  if (!url) return [];
  try { return await browser.cookies.getAll({ url }); } catch (_) {}
  try { return await browser.cookies.getAll({ domain: new URL(url).hostname }); } catch (_) {}
  return [];
}

async function cookiesForMany(urls) {
  const merged = new Map();
  for (const batch of await Promise.all(urls.map(cookiesFor))) {
    for (const cookie of batch) {
      const key = `${cookie.domain || ''}|${cookie.path || '/'}|${cookie.name || ''}`;
      merged.set(key, cookie);
    }
  }
  return Array.from(merged.values());
}

function scheduleFlush(tabId) {
  const old = flushTimers.get(tabId);
  if (old) return; // Throttle: continuous HLS traffic must not postpone delivery.
  flushTimers.set(tabId, setTimeout(() => {
    flushTimers.delete(tabId);
    flushCapture(tabId).catch(() => {});
  }, 100));
}

function sameDocument(first,second) {
  try { const a=new URL(first),b=new URL(second);a.hash='';b.hash='';return a.href===b.href; } catch(_){return false;}
}
function belongsToSnapshot(item,top,frames) {
  const pages=[top.pageUrl,...Array.from(frames?.values()||[]).map(frame=>frame.pageUrl)].filter(Boolean);
  const provenance=[item.documentUrl,item.initiator,item.referer].filter(Boolean);
  if(provenance.some(source=>pages.some(page=>sameDocument(source,page)))) return true;
  // An origin alone cannot identify a page or survive navigation safely.
  const key=SatLinkMedia.key(item);
  return Array.from(frames?.values()||[]).some(frame=>(frame.items||[]).some(raw=>raw.url && SatLinkMedia.key(raw)===key));
}

async function flushCapture(tabId) {
  const top = topSnapshotByTab.get(tabId);
  if (!top) return;

  const now = Date.now();
  const merged = new Map();
  const metadataByItag = new Map();
  const activeVideoId = SatLinkMedia.query(top.pageUrl, "v") || (()=>{try{return new URL(top.activePostUrl||top.pageUrl).pathname.match(/\/video\/(\d+)/)?.[1]||'';}catch(_){return '';}})();
  const frames = frameSnapshotsByTab.get(tabId);
  let duration = Number(top.duration) || 0;

  if (frames) {
    for (const snapshot of frames.values()) {
      if (now - (snapshot.capturedAt || now) > MAX_AGE_MS) continue;
      duration = Math.max(duration, Number(snapshot.duration) || 0);
      for (const raw of Array.isArray(snapshot.items) ? snapshot.items : []) {
        if (raw?.videoId && activeVideoId && raw.videoId !== activeVideoId) continue;
        if (raw?.itag && raw.metadataSource === "player-json") metadataByItag.set(String(raw.itag), raw);
        if (!raw?.url) continue;
        const item = { ...raw };
        item.frameUrl = item.frameUrl || snapshot.pageUrl || '';
        item.referer = item.referer || snapshot.pageUrl || '';
        item.capturedAt = item.capturedAt || snapshot.capturedAt || now;
        merged.set(SatLinkMedia.key(item), SatLinkMedia.merge(merged.get(SatLinkMedia.key(item)), item));
      }
    }
  }

  const network = networkByTab.get(tabId);
  const allNetwork=[...Array.from(network?.values()||[])];
  for(const [key,item] of unattributedNetwork) {
    if(now-(item.capturedAt||now)>MAX_AGE_MS) {unattributedNetwork.delete(key);continue;}
    if(belongsToSnapshot(item,top,frames)) allNetwork.push(item);
  }
  {
    for (const item of allNetwork) {
      if (now - (item.capturedAt || now) > MAX_AGE_MS || item.type === "transport") continue;
      const metadata = metadataByItag.get(String(item.itag || '')) || {};
      const enriched = SatLinkMedia.merge(item, {...metadata, url: SatLinkMedia.fullStreamUrl(item.url), resolvedFromNetwork: true});
      const key = SatLinkMedia.key(enriched);
      merged.set(key, SatLinkMedia.merge(enriched, merged.get(key) || {}));
    }
  }

  const cookieUrls = [top.pageUrl || ''];
  if (frames) {
    for (const snapshot of frames.values()) {
      if (snapshot?.pageUrl) cookieUrls.push(snapshot.pageUrl);
    }
  }
  const cookies = await cookiesForMany([...new Set(cookieUrls)]);
  if (topSnapshotByTab.get(tabId) !== top) { scheduleFlush(tabId); return; }
  for(const item of merged.values()) expandHls(tabId,item);
  const expandedMasters=new Set(Array.from(merged.values()).filter(item=>item.masterUrl).map(item=>item.masterUrl));
  try {
    await browser.runtime.sendNativeMessage('satlink_capture', {
      action: 'captureResult',
      tabKey: top.tabKey || '',
      pageUrl: top.pageUrl || '',
      activePostUrl: top.activePostUrl || '',
      activeMediaKey: top.activeMediaKey || '',
      title: top.title || '',
      userAgent: top.userAgent || '',
      duration,
      items: Array.from(merged.values())
        .filter(item=>!expandedMasters.has(item.url) || item.masterUrl)
        .sort((a, b) => Number(!!b.resolvedFromNetwork) - Number(!!a.resolvedFromNetwork))
        .slice(0, 1500),
      cookies,
      playerFormats: Array.from(metadataByItag.values())
    });
  } catch (_) {}
}

try {
  browser.webRequest.onBeforeSendHeaders.addListener(details => {
    if (!details?.url) return;
    const headers = {documentUrl:details.documentUrl||"",initiator:details.originUrl||details.initiator||""};
    for (const header of details.requestHeaders || []) {
      const name = String(header.name || '').toLowerCase();
      if (name === 'referer') headers.referer = header.value || '';
      if (name === 'origin') headers.origin = header.value || '';
      if (name === 'user-agent') headers.userAgent = header.value || '';
    }
    if (isMediaRequest(details.url)) {
      remember(details.tabId, mediaItem(details.url, '', `request:${details.type || ''}`, 0, headers));
    }
  }, { urls: ['<all_urls>'] }, ['requestHeaders']);

  browser.webRequest.onBeforeRequest.addListener(details => {
    if (details?.url && isMediaRequest(details.url)) {
      remember(details.tabId, mediaItem(details.url, '', `webRequest:${details.type || ''}`,0,{documentUrl:details.documentUrl||"",initiator:details.originUrl||details.initiator||""}));
    }
  }, { urls: ['<all_urls>'] });

  browser.webRequest.onHeadersReceived.addListener(details => {
    if (!details?.url) return;
    let mime = '';
    let size = 0;
    let rangeTotal = 0;
    for (const header of details.responseHeaders || []) {
      const name = String(header.name || '').toLowerCase();
      if (name === 'content-type') mime = String(header.value || '').split(';')[0].trim();
      if (name === 'content-length') size = Number(header.value) || 0;
      if (name === 'content-range') {
        const match = String(header.value || '').match(/\/(\d+)$/);
        if (match) rangeTotal = Number(match[1]) || 0;
      }
    }
    if (isMediaRequest(details.url, mime)) {
      remember(details.tabId, mediaItem(details.url, mime, `headers:${details.type || ''}`, rangeTotal || size,{documentUrl:details.documentUrl||"",initiator:details.originUrl||details.initiator||""}));
    }
  }, { urls: ['<all_urls>'] }, ['responseHeaders']);

  browser.tabs.onUpdated.addListener((tabId, change) => {
    if (change.status !== 'loading' && !change.url) return;
    clearHls(tabId);
    networkByTab.delete(tabId);
    frameSnapshotsByTab.delete(tabId);
    topSnapshotByTab.delete(tabId);
  });

  browser.tabs.onRemoved.addListener(tabId => {
    clearHls(tabId);
    networkByTab.delete(tabId);
    frameSnapshotsByTab.delete(tabId);
    topSnapshotByTab.delete(tabId);
    const timer = flushTimers.get(tabId);
    if (timer) clearTimeout(timer);
    flushTimers.delete(tabId);
  });
} catch (_) {}

browser.runtime.onMessage.addListener(async (message, sender) => {
  if (!message || !['pageSnapshot', 'sessionSnapshot'].includes(message.action)) return;

  const tabId = sender?.tab?.id ?? -1;
  const frameId = sender?.frameId ?? (message.isTopFrame ? 0 : -1);
  const isTopFrame = frameId === 0 || message.isTopFrame === true;

  if (message.action === 'sessionSnapshot') {
    if (!isTopFrame) return;
    const cookies = await cookiesFor(message.pageUrl || '');
    try {
      await browser.runtime.sendNativeMessage('satlink_capture', {
        action: 'sessionUpdate',
        tabKey: message.tabKey || '',
        pageUrl: message.pageUrl || '',
        title: message.title || '',
        userAgent: message.userAgent || '',
        cookies
      });
    } catch (_) {}
    return;
  }

  let frames = frameSnapshotsByTab.get(tabId);
  if (!frames) {
    frames = new Map();
    frameSnapshotsByTab.set(tabId, frames);
  }
  const snapshot = {
    tabKey: message.tabKey || '',
    pageUrl: message.pageUrl || '',
    activePostUrl: message.activePostUrl || '',
    activeMediaKey: message.activeMediaKey || '',
    title: message.title || '',
    userAgent: message.userAgent || '',
    duration: Number(message.duration) || 0,
    items: Array.isArray(message.items) ? message.items : [],
    capturedAt: Date.now(),
    isTopFrame
  };
  const oldTop = topSnapshotByTab.get(tabId);
  if (isTopFrame && oldTop && (oldTop.pageUrl !== snapshot.pageUrl || oldTop.activePostUrl !== snapshot.activePostUrl || oldTop.activeMediaKey !== snapshot.activeMediaKey)) {
    clearHls(tabId);
    for(const [key,item] of unattributedNetwork) {
      if(belongsToSnapshot(item,oldTop,frames)) unattributedNetwork.delete(key);
    }
    frames.clear();
    networkByTab.delete(tabId);
  }
  frames.set(frameId, snapshot);
  if (isTopFrame) topSnapshotByTab.set(tabId, snapshot);
  scheduleFlush(tabId);
});
