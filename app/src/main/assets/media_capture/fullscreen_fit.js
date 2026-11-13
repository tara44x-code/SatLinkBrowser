/* Presentation only. No network, source, playback, capture or download operations. */
(() => {
  'use strict';
  let root = null, video = null, button = null, observer = null, timer = null;
  let cover = true;
  let hideButtonTimer = null;
  let holdTimer = null, gesture = null, suppressClick = false;
  // Relative coordinates survive fullscreen re-entry and rotation in this page.
  let buttonPosition = {x: 0.86, y: 0.5};
  const originals = new Map();
  const fullscreen = () => document.fullscreenElement || document.mozFullScreenElement;
  function showFitButton() {
    if (!button || fullscreen() !== root) return;
    if (hideButtonTimer !== null) clearTimeout(hideButtonTimer);
    button.style.setProperty('visibility', 'visible', 'important');
    button.style.setProperty('pointer-events', 'auto', 'important');
    if (gesture) { hideButtonTimer = null; return; }
    hideButtonTimer = setTimeout(() => {
      hideButtonTimer = null;
      if (!button) return;
      if (document.activeElement === button) button.blur();
      button.style.setProperty('visibility', 'hidden', 'important');
      button.style.setProperty('pointer-events', 'none', 'important');
    }, 2000);
  }
  function positionFitButton() {
    if (!button) return;
    const rect = button.getBoundingClientRect();
    const width = window.innerWidth || document.documentElement.clientWidth;
    const height = window.innerHeight || document.documentElement.clientHeight;
    const margin = 8;
    const x = Math.max(0, width - rect.width - margin * 2);
    const y = Math.max(0, height - rect.height - margin * 2);
    const values = {right:'auto',
      left:Math.min(margin, Math.max(0, width - rect.width)) + buttonPosition.x * x + 'px',
      top:Math.min(margin, Math.max(0, height - rect.height)) + buttonPosition.y * y + 'px'};
    for (const [name, value] of Object.entries(values)) {
      if (button.style.getPropertyValue(name) !== value || button.style.getPropertyPriority(name) !== 'important')
        button.style.setProperty(name, value, 'important');
    }
  }
  function cancelHold() {
    if (holdTimer !== null) clearTimeout(holdTimer);
    holdTimer = null;
  }
  function finishDrag(e) {
    if (!gesture || e.pointerId !== gesture.id) return;
    cancelHold();
    if (gesture.dragging || gesture.moved) suppressClick = true;
    const id = gesture.id;
    gesture = null;
    try { button.releasePointerCapture(id); } catch (_) {}
    showFitButton();
  }
  function installButtonDrag() {
    button.addEventListener('pointerdown', e => {
      if (gesture || e.isPrimary === false || (e.button !== undefined && e.button !== 0)) return;
      e.stopPropagation(); suppressClick = false;
      const rect = button.getBoundingClientRect();
      gesture = {id:e.pointerId, x:e.clientX, y:e.clientY, left:rect.left, top:rect.top, dragging:false, moved:false};
      showFitButton();
      try { button.setPointerCapture(e.pointerId); } catch (_) {}
      holdTimer = setTimeout(() => {
        holdTimer = null;
        if (gesture) { gesture.dragging = true; suppressClick = true; }
      }, 450);
    });
    button.addEventListener('pointermove', e => {
      if (!gesture || e.pointerId !== gesture.id) return;
      e.stopPropagation();
      const dx = e.clientX - gesture.x, dy = e.clientY - gesture.y;
      if (!gesture.dragging) {
        if (Math.hypot(dx, dy) > 8) { gesture.moved = true; cancelHold(); }
        return;
      }
      e.preventDefault();
      const rect = button.getBoundingClientRect();
      const width = window.innerWidth || document.documentElement.clientWidth;
      const height = window.innerHeight || document.documentElement.clientHeight;
      const x = Math.max(0, width - rect.width - 16), y = Math.max(0, height - rect.height - 16);
      buttonPosition = {x:x ? Math.max(0, Math.min(1, (gesture.left + dx - 8) / x)) : 0,
        y:y ? Math.max(0, Math.min(1, (gesture.top + dy - 8) / y)) : 0};
      positionFitButton();
    });
    for (const name of ['pointerup', 'pointercancel', 'lostpointercapture']) button.addEventListener(name, finishDrag);
    button.addEventListener('contextmenu', e => { e.preventDefault(); e.stopPropagation(); });
  }
  function set(element, name, value) {
    let saved = originals.get(element);
    if (!saved) { saved = new Map(); originals.set(element, saved); }
    if (!saved.has(name)) saved.set(name, {
      value: element.style.getPropertyValue(name), priority: element.style.getPropertyPriority(name), applied: value
    });
    saved.get(name).applied = value;
    if (element.style.getPropertyValue(name) !== value || element.style.getPropertyPriority(name) !== 'important')
      element.style.setProperty(name, value, 'important');
  }
  function restore() {
    cancelHold(); gesture = null; suppressClick = false;
    if (hideButtonTimer !== null) clearTimeout(hideButtonTimer);
    hideButtonTimer = null;
    if (observer) observer.disconnect();
    observer = null;
    if (timer !== null) clearTimeout(timer);
    timer = null;
    if (button) button.remove();
    button = null;
    for (const [element, saved] of originals) for (const [name, previous] of saved) {
      // Preserve newer unrelated site changes.
      if (element.style.getPropertyValue(name) !== previous.applied || element.style.getPropertyPriority(name) !== 'important') continue;
      if (previous.value) element.style.setProperty(name, previous.value, previous.priority);
      else element.style.removeProperty(name);
    }
    originals.clear(); video = null;
  }
  function videos(element) {
    const found = element.tagName === 'VIDEO' ? [element] : Array.from(element.querySelectorAll('video'));
    for (const child of element.querySelectorAll('*')) if (child.shadowRoot) found.push(...videos(child.shadowRoot));
    return found;
  }
  function choose(element) {
    return videos(element).filter(v => {
      const r = v.getBoundingClientRect(); return r.width > 0 && r.height > 0;
    }).sort((a, b) => {
      const area = v => { const r = v.getBoundingClientRect(); return r.width * r.height; };
      return Number(!b.paused) - Number(!a.paused) || area(b) - area(a);
    })[0] || null;
  }
  function schedule() {
    if (timer !== null) return;
    timer = setTimeout(() => { timer = null; update(); }, 50);
  }
  function update() {
    const current = fullscreen();
    if (!current) { restore(); root = null; return; }
    const next = choose(current);
    if (current !== root || next !== video) {
      restore(); root = current; video = next; cover = true;
      observer = new MutationObserver(schedule);
      observer.observe(root, {childList: true, subtree: true, attributes: true, attributeFilter: ['style', 'class']});
    }
    if (!video) return; // The child frame handles its own video; never read cross-origin frames.
    set(root, 'overflow', 'hidden');
    set(root, 'padding', '0px');
    set(root, 'border-radius', '0px');
    let parent = video.parentElement;
    while (parent && parent !== root && root.contains(parent)) {
      for (const [name, value] of Object.entries({width:'100%', height:'100%', 'max-width':'none', 'max-height':'none',
        padding:'0px', margin:'0px', transform:'none', 'border-radius':'0px'})) set(parent, name, value);
      parent = parent.parentElement;
    }
    for (const [name, value] of Object.entries({position:'fixed', top:'0px', left:'0px', width:'100vw', height:'100vh',
      'max-width':'none', 'max-height':'none', margin:'0px', padding:'0px', transform:'none',
      // Fill only the video element's empty area, without clipping source pixels.
      'object-fit':cover ? 'fill' : 'contain', 'object-position':'50% 50%', 'box-sizing':'border-box'})) set(video, name, value);
    // A replaced VIDEO element cannot render child controls; keyboard Z still switches fit there.
    if (!button && root.tagName !== 'VIDEO' && !/^(IFRAME|EMBED|OBJECT)$/.test(root.tagName)) {
      button = document.createElement('button');
      button.type = 'button'; button.setAttribute('data-satlink-fullscreen-fit', '');
      button.style.cssText = 'position:fixed!important;z-index:2147483647!important;width:auto!important;height:auto!important;max-width:100vw!important;box-sizing:border-box!important;touch-action:none!important;user-select:none!important;padding:8px 12px!important;background:rgba(0,0,0,.65)!important;color:white!important;border:1px solid #aaa!important;border-radius:8px!important;font:14px sans-serif!important;cursor:pointer!important;';
      button.addEventListener('click', e => {
        e.preventDefault(); e.stopPropagation();
        if (suppressClick && e.detail !== 0) { suppressClick = false; return; }
        toggle();
      });
      installButtonDrag();
      root.appendChild(button);
      showFitButton();
    }
    if (button) {
      const label = cover ? 'عرض كامل' : 'ملء الشاشة';
      if (button.textContent !== label) button.textContent = label;
      button.setAttribute('aria-label', label); button.title = 'SatLink: ' + label + ' (Z)';
      positionFitButton();
    }
  }
  function toggle() { if (video) { cover = !cover; update(); showFitButton(); } }
  // Capture phase observes screen touches without consuming site/player gestures.
  function screenInteraction(e) {
    if (button && fullscreen() === root && root.contains(e.target)) showFitButton();
  }
  document.addEventListener('pointerdown', screenInteraction, true);
  document.addEventListener('pointerup', screenInteraction, true);
  document.addEventListener('pointercancel', screenInteraction, true);
  document.addEventListener('keydown', screenInteraction, true);
  document.addEventListener('fullscreenchange', update);
  document.addEventListener('mozfullscreenchange', update);
  document.addEventListener('loadedmetadata', schedule, true);
  document.addEventListener('playing', schedule, true);
  document.addEventListener('keydown', e => {
    if (fullscreen() && video && e.key && e.key.toLowerCase() === 'z' && !e.ctrlKey && !e.altKey && !e.metaKey &&
        !/^(INPUT|TEXTAREA|SELECT)$/.test(e.target.tagName) && !e.target.isContentEditable) {
      e.preventDefault(); toggle();
    }
  });
  // Touch alternative when the VIDEO itself is fullscreen and cannot host a button.
  document.addEventListener('touchstart', e => {
    if (fullscreen() && video && root.tagName === 'VIDEO' && e.touches.length === 2) {
      e.preventDefault(); toggle();
    }
  }, {passive: false});
  window.addEventListener('resize', schedule);
  window.addEventListener('orientationchange', schedule);
  window.addEventListener('pagehide', () => { restore(); root = null; });
})();
