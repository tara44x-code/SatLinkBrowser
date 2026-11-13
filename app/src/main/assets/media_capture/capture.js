(() => {
  const found = new Map();
  let scannedScripts = new WeakMap();
  let sendTimer = null;
  let scanTimer = null;
  let currentPage = location.href;
  let activePostUrl = '';
  let activeMediaKey = '';
  let playbackAllowed = true;
  let userPlaybackRequired = false;
  let navigationCutoff = 0;
  let navigationPending = false;
  let lastPayload = '';
  function scheduleSend() {
    if (sendTimer !== null) return;
    sendTimer = setTimeout(() => { sendTimer = null; send(false); }, 100);
  }
  function scheduleScan() {
    if (scanTimer !== null) return;
    scanTimer = setTimeout(() => { scanTimer = null; scan(); }, 100);
  }
  const mediaExt = /\.(mp4|webm|mkv|mov|m4v|mp3|m4a|aac|ogg|opus|wav|flac|m3u8|mpd|m4s|ts)(?:$|[?#])/i;

  function absolute(url) {
    if (typeof url !== 'string' || !url.trim()) return null;
    try { return new URL(url, document.baseURI).href; } catch (_) { return null; }
  }

  function add(url, mime = '', source = '') {
    const full = absolute(url);
    if (!full || !/^https?:/i.test(full)) return;
    const queryMime = SatLinkMedia.query(full, 'mime');
    if (!mediaExt.test(full) && !/(mpegurl|dash\+xml|mp2t|matroska)/i.test(mime) && !/^(video|audio)\//i.test(mime) &&
        !/^(video|audio)\//i.test(queryMime) &&
        !/(videoplayback|manifest|playlist|master|stream|\.m4s(?:$|[?#]))/i.test(full)) return;
    const next = { ...SatLinkMedia.urlMetadata(full, mime), source, frameUrl: location.href };
    const previous = found.get(full);
    if (previous && (!mime || previous.mime === next.mime)) return;
    found.set(full, SatLinkMedia.merge(previous, next));
    if (found.size > 1000) found.delete(found.keys().next().value);
    scheduleSend();
  }

  function addPageCandidate(url, source = 'iframe') {
    const full = absolute(url);
    if (!full || !/^https?:/i.test(full)) return;
    if (found.has(`page:${full}`)) return;
    if (found.size >= 1000) found.delete(found.keys().next().value);
    scheduleSend();
    found.set(`page:${full}`, {
      url: full,
      mime: 'text/html',
      type: 'page',
      source,
      duration: 0,
      size: 0,
      bitrate: 0,
      fps: 0,
      width: 0,
      height: 0,
      qualityLabel: '',
      itag: '',
      frameUrl: location.href
    });
  }

  function scanInlinePlayerData() {
    // Some players expose signed MP4/HLS/DASH URLs only inside inline JSON.
    // Reading script text is passive and avoids modifying the player.
    try {
      const scripts = document.scripts || [];
      for (const script of scripts) {
        const text = String(script.textContent || '');
        if (!text || text.length > 4_000_000 || scannedScripts.get(script) === text) continue;
        scannedScripts.set(script, text);
        for (const item of SatLinkMedia.formatsFromText(text, location.href, activePostUrl)) {
          const key = item.url || `metadata:${item.itag}`;
          const enriched = SatLinkMedia.merge(found.get(key), item);
          if (JSON.stringify(found.get(key)) !== JSON.stringify(enriched)) {
            found.set(key, enriched);
            if (found.size > 1000) found.delete(found.keys().next().value);
            scheduleSend();
          }
        }
        if (/(^|\.)tiktok\.com$/i.test(location.hostname)) continue;
        const normalized = text.replace(/\\u0026/g, '&').replace(/\\\//g, '/');
        const matches = normalized.match(/https?:\/\/[^"'<>\\\s]+/g) || [];
        for (const raw of matches.slice(0, 500)) {
          const clean = raw.replace(/\\u0026/g, '&').replace(/\\+/g, '');
          add(clean, '', 'inline-player-data');
        }
      }
    } catch (_) {}
  }

  function scan() {
    if (currentPage !== location.href) {
      currentPage = location.href;
      if(!navigationPending) navigationCutoff = typeof performance.now === "function" ? performance.now() : 0;
      navigationPending = false;
      found.clear();
      scannedScripts = new WeakMap();
      lastPayload = '';
      sendSession();
    }
    const active=typeof SatLinkActivePost==='undefined'?{url:'',video:null}:SatLinkActivePost.resolve(document,location.href,window);
    const nextMediaKey=active.url || active.video ? active.url+'|'+(active.video?.currentSrc||active.video?.src||'') : '';
    if(nextMediaKey!==activeMediaKey) {
      activeMediaKey=nextMediaKey;
      activePostUrl=active.url;
      found.clear(); scannedScripts=new WeakMap(); lastPayload='';
      navigationCutoff=typeof performance.now==='function'?performance.now():0;
    }
    document.querySelectorAll('iframe[src], embed[src], object[data]').forEach(el =>
      addPageCandidate(el.src || el.data, el.tagName.toLowerCase()));

    document.querySelectorAll('video, audio').forEach(el => {
      if(typeof SatLinkActivePost!=='undefined' && SatLinkActivePost.isTikTok(location.href) && active.video && el!==active.video) return;
      const src=el.currentSrc || el.src;
      const mime=el.getAttribute('type') || (el.tagName.toLowerCase()==='video'?'video/unknown':'audio/unknown');
      add(src, mime, el.tagName.toLowerCase());
      const previous=found.get(absolute(src));
      if(previous && el.videoWidth>0 && el.videoHeight>0) {
        const data={width:el.videoWidth,height:el.videoHeight,duration:SatLinkMedia.positive(el.duration)};
        if(typeof el.mozHasAudio==='boolean') data.hasAudio=el.mozHasAudio;
        found.set(absolute(src),SatLinkMedia.merge(previous,data));
      }
      add(el.getAttribute('data-src'), el.getAttribute('type') || '', `${el.tagName.toLowerCase()}:data-src`);
      el.querySelectorAll('source').forEach(s => add(s.src || s.getAttribute('data-src'), s.type || '', 'source'));
    });

    document.querySelectorAll('a[href]').forEach(a => add(a.href, '', 'link'));
    try {
      performance.getEntriesByType('resource').forEach(r => {
        if(!navigationCutoff || !r.startTime || r.startTime >= navigationCutoff) add(r.name, '', `performance:${r.initiatorType || ''}`);
      });
    } catch (_) {}
    scanInlinePlayerData();
  }

  function pageDuration() {
    let best = 0;
    document.querySelectorAll('video, audio').forEach(el => {
      const value = Number(el.duration);
      if (Number.isFinite(value) && value > best) best = value;
    });
    return best;
  }

  let nativeTabKey = "";

  function sendSession() {
    browser.runtime.sendMessage({
      action: 'sessionSnapshot',
      tabKey: nativeTabKey,
      pageUrl: location.href,
      title: document.title || '',
      userAgent: navigator.userAgent || '',
      isTopFrame: window.top === window
    }).catch(() => {});
  }

  function send(force = true) {
    scan();
    const payload = {
      action: 'pageSnapshot',
      tabKey: nativeTabKey,
      pageUrl: location.href,
      title: document.title || '',
      userAgent: navigator.userAgent || '',
      duration: pageDuration(),
      isTopFrame: window.top === window,
      activePostUrl,
      activeMediaKey,
      items: Array.from(found.values()).slice(-1000)
    };
    const fingerprint = JSON.stringify(payload);
    if (!force && fingerprint === lastPayload) return;
    lastPayload = fingerprint;
    browser.runtime.sendMessage(payload).catch(() => {
      browser.runtime.sendNativeMessage('satlink_capture', {
        ...payload,
        action: 'captureResult',
        cookies: []
      }).catch(() => {});
    });
  }

  try {
    const originalFetch = window.fetch;
    window.fetch = function(...args) {
      try { add(typeof args[0] === 'string' ? args[0] : args[0]?.url, '', 'fetch'); } catch (_) {}
      return originalFetch.apply(this, args).then(response => {
        try { add(response.url, response.headers.get('content-type') || '', 'fetch-response'); } catch (_) {}
        return response;
      });
    };
  } catch (_) {}

  try {
    const originalOpen = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function(method, url, ...rest) {
      try { add(url, '', 'xhr'); } catch (_) {}
      return originalOpen.call(this, method, url, ...rest);
    };
  } catch (_) {}

  try {
    const performanceObserver = new PerformanceObserver(list => {
      list.getEntries().forEach(entry => {
        if(!navigationCutoff || !entry.startTime || entry.startTime >= navigationCutoff) add(entry.name, '', `observer:${entry.initiatorType || ''}`);
      });
    });
    performanceObserver.observe({ type: 'resource', buffered: true });
  } catch (_) {}

  function pauseAllMedia() {
    document.querySelectorAll('video, audio').forEach(el=>{ try {el.pause();} catch (_) {} });
  }
  document.addEventListener('play',event=>{
    if(!playbackAllowed || userPlaybackRequired) { try {event.target.pause();} catch (_) {} }
  },true);
  for(const name of ['pointerdown','keydown']) {
    document.addEventListener(name,event=>{
      if(playbackAllowed && event.isTrusted) userPlaybackRequired=false;
    },true);
  }

  // Touch playback buttons of the app act on the video the user is watching in fullscreen.
  function controlTarget() {
    const full = document.fullscreenElement || document.mozFullScreenElement || null;
    // A parent frame only sees the fullscreen iframe; the frame that owns the video answers.
    if (!full || full.tagName === 'IFRAME' || full.tagName === 'FRAME') return null;
    let list = [];
    if (full.tagName === 'VIDEO') list = [full];
    else if (full.querySelectorAll) list = Array.from(full.querySelectorAll('video'));
    if (!list.length) list = Array.from(document.querySelectorAll('video'));
    let best = null, bestArea = -1;
    for (const video of list) {
      let area = 0;
      try { const box = video.getBoundingClientRect(); area = box.width * box.height; } catch (_) {}
      if (!video.paused) area += 1;
      if (area > bestArea) { best = video; bestArea = area; }
    }
    return best;
  }
  function playerControl(message) {
    const video = controlTarget();
    if (!video) return;
    const value = Number(message.value);
    try {
      if (message.command === 'seek' && Number.isFinite(value)) {
        let next = (Number(video.currentTime) || 0) + value;
        if (Number.isFinite(video.duration) && video.duration > 0) next = Math.min(next, video.duration);
        video.currentTime = Math.max(0, next);
      } else if (message.command === 'toggle') {
        if (video.paused) {
          // The press on the app button is the user's own request to play.
          userPlaybackRequired = false;
          const started = video.play();
          if (started && typeof started.catch === 'function') started.catch(() => {});
        } else video.pause();
      } else if (message.command === 'rate' && Number.isFinite(value) && value >= 0.25 && value <= 4) {
        video.playbackRate = value;
      }
    } catch (_) {}
  }

  let nativePort = null;
  try {
    nativePort = browser.runtime.connectNative('satlink_capture');
    nativePort.onMessage.addListener(message => {
      if (!message) return;
      const changedKey = typeof message.tabKey === 'string' && message.tabKey !== nativeTabKey;
      if (changedKey) nativeTabKey = message.tabKey;
      if (message.action === 'scanMedia') send();
      if (message.action === 'pauseMedia') { userPlaybackRequired=true; pauseAllMedia(); }
      if (message.action === 'playerControl' && message.allowed === true) playerControl(message);
      if (message.action === 'setPlaybackAllowed') {
        playbackAllowed=message.allowed===true;
        if(!playbackAllowed) {userPlaybackRequired=true;pauseAllMedia();}
      }
      if (changedKey) {sendSession();send(false);}
    });
  } catch (_) {}

  try {
    const mutationObserver = new MutationObserver(scheduleScan);
    mutationObserver.observe(document.documentElement || document, {
      childList: true,
      subtree: true,
      attributes: true,
      attributeFilter: ['src', 'href', 'data', 'data-src']
    });
  } catch (_) {}

  function navigationStarting() {
    navigationPending = true;
    navigationCutoff = typeof performance.now === 'function' ? performance.now() : 0;
    scheduleScan();
  }
  window.addEventListener('popstate', navigationStarting, { passive: true });
  document.addEventListener('yt-navigate-start', navigationStarting, { passive: true });
  document.addEventListener('yt-navigate-finish', () => send(), { passive: true });
  send();
  sendSession();
  document.addEventListener('DOMContentLoaded', () => send(), { once: true });
  for (const event of ['loadedmetadata', 'play', 'durationchange']) {
    document.addEventListener(event, scheduleSend, true);
  }
  setInterval(() => { if (!document.hidden) { scan(); scheduleSend(); } }, 3000);
  window.addEventListener('pageshow', sendSession, { passive: true });
  document.addEventListener('visibilitychange', () => {
    if (!document.hidden) sendSession();
  }, { passive: true });
})();
