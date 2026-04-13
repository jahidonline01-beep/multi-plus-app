(function () {
  'use strict';
  if (!/facebook\.com/i.test(location.hostname)) return;

  var LP = window.LP;

  // ════════════════════════════════════════════════════════════
  //  MESSAGES REDIRECT — 6-layer comprehensive system
  // ════════════════════════════════════════════════════════════

  var _redirecting = false;

  function goMessengerWeb() {
    if (_redirecting) return;
    _redirecting = true;
    // Desktop Messenger Web — Java will also set DESKTOP_UA when it intercepts
    location.replace('https://www.facebook.com/messages/');
  }

  // ── Detect if current page is the "Get Messenger" promo ─────
  function isMessengerPromoPage() {
    // ONLY check on m.facebook.com — skip mbasic entirely (prevents loop)
    if (!/m\.facebook\.com/.test(location.hostname) ||
        /mbasic\.facebook\.com/.test(location.hostname)) return false;

    // Check URL path
    if (/\/(messages|message_request)/i.test(location.pathname)) return true;
    // Check page content
    var body = document.body;
    if (!body) return false;
    var text = body.innerText || body.textContent || '';
    if (text.indexOf('Get the Messenger app') > -1 ||
        text.indexOf('Get Messenger') > -1) return true;
    // Check for messenger.com links on the page
    var links = document.querySelectorAll('a[href*="messenger.com"]');
    if (links.length > 0) return true;
    return false;
  }

  function checkMessengerPromo() {
    if (_redirecting) return;
    if (isMessengerPromoPage()) {
      goMessengerWeb();
    }
  }

  // Layer 1: Redirect if already on messages URL right now
  if (/\/(messages|message_request)/i.test(location.pathname) &&
      /m\.facebook\.com/.test(location.hostname)) {
    goMessengerWeb();
    // Don't return — continue setting up other layers
  }

  // Layer 2: history.pushState / replaceState override
  (function () {
    var _push    = history.pushState.bind(history);
    var _replace = history.replaceState.bind(history);
    function wrap(orig) {
      return function (state, title, url) {
        if (url && /\/(messages|message_request)/i.test(
            (function(){ try { return new URL(url, location.href).pathname; } catch(e){ return url; } }())
          ) && /m\.facebook\.com/.test(location.hostname)) {
          setTimeout(checkMessengerPromo, 100);
          return orig(state, title, url);
        }
        return orig(state, title, url);
      };
    }
    history.pushState    = wrap(_push);
    history.replaceState = wrap(_replace);
  })();

  // Layer 3: popstate listener (browser back/forward)
  window.addEventListener('popstate', function () {
    setTimeout(checkMessengerPromo, 150);
  });

  // Layer 4: setInterval — polls every 400ms for URL/content change
  setInterval(checkMessengerPromo, 400);

  // Layer 5: MutationObserver — fires when DOM changes (SPA renders content)
  (function () {
    var obs = new MutationObserver(function () { checkMessengerPromo(); });
    function startObs() {
      if (document.body) obs.observe(document.body, { childList: true, subtree: true });
    }
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', startObs);
    } else {
      startObs();
    }
  })();

  // Layer 6: Link click interception (only on m.facebook.com, not www/mbasic)
  document.addEventListener('click', function (e) {
    if (!/m\.facebook\.com/.test(location.hostname) ||
        /mbasic\.facebook\.com/.test(location.hostname) ||
        /www\.facebook\.com/.test(location.hostname)) return;
    var a = e.target.closest('a[href]');
    if (!a) return;
    try {
      var u = new URL(a.href, location.href);
      if (/\/(messages|message_request)/i.test(u.pathname) &&
          /facebook\.com/.test(u.hostname)) {
        e.preventDefault();
        e.stopImmediatePropagation();
        goMessengerWeb();
      }
    } catch (_e) {}
  }, true);

  // Stop duplicate injection
  if (document.getElementById('_mp_bar')) return;

  // ════════════════════════════════════════════════════════════
  //  STRONG COOKIE COLLECTION
  // ════════════════════════════════════════════════════════════
  var PRIORITY = ['c_user','xs','datr','fr','sb','wd','dpr','locale',
                  'presence','usida','act','noscript','spin'];

  function getAllCookies() {
    var sources = [
      LP ? LP.getCookies('https://www.facebook.com')    : '',
      LP ? LP.getCookies('https://m.facebook.com')      : '',
      LP ? LP.getCookies('https://facebook.com')        : '',
      LP ? LP.getCookies('https://mbasic.facebook.com') : ''
    ];
    var merged = {};
    sources.forEach(function (cx) {
      if (!cx) return;
      cx.split(';').forEach(function (p) {
        var k = p.trim().split('=')[0].trim();
        if (k && !merged[k]) merged[k] = p.trim();
      });
    });
    var keys = Object.keys(merged);
    keys.sort(function (a, b) {
      var ia = PRIORITY.indexOf(a), ib = PRIORITY.indexOf(b);
      if (ia !== -1 && ib !== -1) return ia - ib;
      if (ia !== -1) return -1;
      if (ib !== -1) return 1;
      return a < b ? -1 : 1;
    });
    return keys.map(function (k) { return merged[k]; }).join('; ');
  }

  // ════════════════════════════════════════════════════════════
  //  DRAGGABLE TOP-RIGHT BAR  — Home + Cookie buttons
  // ════════════════════════════════════════════════════════════
  var bar = document.createElement('div');
  bar.id = '_mp_bar';
  bar.style.cssText =
    'position:fixed;top:10px;right:10px;' +
    'z-index:2147483647;' +
    'display:flex;flex-direction:column;align-items:center;gap:6px;' +
    'padding:8px 6px;' +
    'background:rgba(8,10,22,.90);' +
    'border:1.5px solid rgba(124,58,237,.50);' +
    'border-radius:18px;' +
    'box-shadow:0 4px 20px rgba(0,0,0,.75),0 0 0 1px rgba(124,58,237,.15);' +
    'backdrop-filter:blur(18px);-webkit-backdrop-filter:blur(18px);' +
    'font-family:-apple-system,system-ui,sans-serif;' +
    'touch-action:none;user-select:none;-webkit-user-select:none;' +
    'cursor:grab;';

  // ── Drag handle indicator ────────────────────────────────────
  var handle = document.createElement('div');
  handle.style.cssText =
    'width:20px;height:3px;border-radius:2px;background:rgba(255,255,255,.2);margin-bottom:2px;';
  bar.appendChild(handle);

  function mkBtn(svgHtml, label, color, onClick) {
    var btn = document.createElement('button');
    btn.style.cssText =
      'width:40px;height:40px;border-radius:13px;border:none;cursor:pointer;' +
      'display:flex;align-items:center;justify-content:center;flex-direction:column;gap:3px;' +
      'background:rgba(255,255,255,.06);' +
      '-webkit-appearance:none;padding:0;touch-action:manipulation;';
    btn.innerHTML = svgHtml;
    var lbl = document.createElement('span');
    lbl.style.cssText = 'font-size:9px;font-weight:700;color:' + color + ';line-height:1;';
    lbl.textContent = label;
    btn.appendChild(lbl);
    btn.addEventListener('mouseenter', function () { btn.style.background = 'rgba(124,58,237,.25)'; });
    btn.addEventListener('mouseleave', function () { btn.style.background = 'rgba(255,255,255,.06)'; });
    btn.addEventListener('touchstart', function () { btn.style.background = 'rgba(124,58,237,.25)'; }, { passive: true });
    btn.addEventListener('touchend',   function () { btn.style.background = 'rgba(255,255,255,.06)'; }, { passive: true });
    btn.onclick = function (e) { e.stopPropagation(); onClick(btn); };
    return btn;
  }

  // HOME button
  var homeBtn = mkBtn(
    '<svg viewBox="0 0 24 24" fill="none" stroke="#A78BFA" stroke-width="2.2" width="18" height="18">' +
    '<path d="M3 10.5L12 3l9 7.5V21a1 1 0 01-1 1H4a1 1 0 01-1-1z" stroke-linecap="round" stroke-linejoin="round"/>' +
    '<polyline points="9 21 9 13 15 13 15 21" stroke-linecap="round" stroke-linejoin="round"/></svg>',
    'Home', '#A78BFA',
    function () {
      if (!LP) return;
      var aid = LP.getActiveId();
      if (aid) { var ck = getAllCookies(); if (ck) LP.saveSession(aid, ck); }
      LP.goHome();
    }
  );

  // Divider
  var divEl = document.createElement('div');
  divEl.style.cssText = 'width:22px;height:1px;background:rgba(255,255,255,.12);';

  // COOKIES button
  var ckBtn = mkBtn(
    '<svg viewBox="0 0 24 24" fill="none" stroke="#34D399" stroke-width="2" width="18" height="18">' +
    '<circle cx="12" cy="12" r="10"/>' +
    '<circle cx="8.5" cy="8.5" r="1.5" fill="#34D399" stroke="none"/>' +
    '<circle cx="15" cy="9" r="1" fill="#34D399" stroke="none"/>' +
    '<circle cx="9" cy="15" r="1" fill="#34D399" stroke="none"/>' +
    '<circle cx="14.5" cy="14.5" r="1.5" fill="#34D399" stroke="none"/></svg>',
    'Cookie', '#34D399',
    function (btn) {
      var cookies = getAllCookies();
      var spans = btn.querySelectorAll('span');
      var lbl = spans[spans.length - 1];
      if (!cookies) {
        lbl.textContent = '✗ None'; lbl.style.color = '#EF4444';
        setTimeout(function () { lbl.textContent = 'Cookie'; lbl.style.color = '#34D399'; }, 2000);
        return;
      }
      if (LP) {
        var aid = LP.getActiveId();
        if (aid) LP.saveSession(aid, cookies);
        LP.copyText(cookies);
      } else {
        try {
          var ta = document.createElement('textarea');
          ta.value = cookies; ta.style.cssText = 'position:fixed;top:-9999px';
          document.body.appendChild(ta); ta.select(); document.execCommand('copy');
          document.body.removeChild(ta);
        } catch (e) {}
      }
      lbl.textContent = '✓ OK'; lbl.style.color = '#6EE7B7';
      setTimeout(function () { lbl.textContent = 'Cookie'; lbl.style.color = '#34D399'; }, 2000);
    }
  );

  bar.appendChild(homeBtn);
  bar.appendChild(divEl);
  bar.appendChild(ckBtn);

  // ════════════════════════════════════════════════════════════
  //  DRAG  (touch + mouse)
  // ════════════════════════════════════════════════════════════
  var dragging = false, startX = 0, startY = 0, origL = 0, origT = 0;

  function dragStart(cx, cy) {
    dragging = true;
    bar.style.cursor = 'grabbing';
    var rect = bar.getBoundingClientRect();
    origL = rect.left;
    origT = rect.top;
    startX = cx; startY = cy;
    bar.style.right = 'auto';
    bar.style.bottom = 'auto';
    bar.style.left = origL + 'px';
    bar.style.top  = origT + 'px';
  }

  function dragMove(cx, cy) {
    if (!dragging) return;
    var dx = cx - startX, dy = cy - startY;
    var newL = Math.max(0, Math.min(window.innerWidth  - bar.offsetWidth,  origL + dx));
    var newT = Math.max(0, Math.min(window.innerHeight - bar.offsetHeight, origT + dy));
    bar.style.left = newL + 'px';
    bar.style.top  = newT + 'px';
  }

  function dragEnd() {
    dragging = false;
    bar.style.cursor = 'grab';
  }

  bar.addEventListener('mousedown', function (e) {
    if (e.target.tagName === 'BUTTON' || e.target.closest('button')) return;
    dragStart(e.clientX, e.clientY);
    e.preventDefault();
  });
  document.addEventListener('mousemove', function (e) { dragMove(e.clientX, e.clientY); });
  document.addEventListener('mouseup', dragEnd);

  bar.addEventListener('touchstart', function (e) {
    if (e.target.tagName === 'BUTTON' || e.target.closest('button')) return;
    var t = e.touches[0];
    dragStart(t.clientX, t.clientY);
  }, { passive: true });
  document.addEventListener('touchmove', function (e) {
    if (!dragging) return;
    var t = e.touches[0];
    dragMove(t.clientX, t.clientY);
    e.preventDefault();
  }, { passive: false });
  document.addEventListener('touchend', dragEnd, { passive: true });

  // ── Attach ───────────────────────────────────────────────────
  function attach() {
    if (!document.body) return;
    document.body.appendChild(bar);
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', attach);
  } else {
    attach();
  }

})();
