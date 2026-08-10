(() => {
  'use strict';

  if (window.__clearFeedInstagramGuardInstalled) return;
  Object.defineProperty(window, '__clearFeedInstagramGuardInstalled', { value: true });

  const GUARD_VERSION = __DIRECTONLY_GUARD_VERSION__;
  const GUARD_CSS = __DIRECTONLY_CSS_JSON__;
  const BRIDGE_TOKEN = __CLEARFEED_BRIDGE_TOKEN_JSON__;
  const NATIVE_POST = window.clearFeedBridge?.postMessage?.bind(window.clearFeedBridge);
  const RULES = window.__clearFeedGuardRules;
  const TRUSTED_HOSTS = new Set(['instagram.com', 'www.instagram.com']);
  const UNSAFE_LABELS = new Set([
    'Instagram', 'Home', 'Reels', 'Notifications', 'New post',
    'Professional dashboard', 'Settings', 'Also from Meta'
  ]);
  const CONTENT_PREFIXES = [
    '/reel/', '/reels/', '/stories/', '/p/', '/explore/',
    '/tags/', '/locations/', '/shopping/', '/accounts/activity/'
  ];
  const initialLocation = new URL(location.href);
  const initialIdentity = sharedContentIdentity(initialLocation);
  const initialSealedToken = sealedToken(initialLocation);
  const SEALED = initialIdentity && initialSealedToken ? Object.freeze({
    kind: initialIdentity.kind,
    contentId: initialIdentity.contentId,
    canonicalPath: initialIdentity.canonicalPath,
    token: initialSealedToken
  }) : null;

  let lastHref = location.href;
  let scanQueued = false;
  let healthTimer = 0;
  let sealedStream = null;
  let sealedStreamItem = null;
  let touchStartX = 0;
  let touchStartY = 0;
  let trustedGestureActive = false;

  function post(payload) {
    try {
      if (NATIVE_POST) NATIVE_POST(JSON.stringify({ ...payload, bridgeToken: BRIDGE_TOKEN }));
    } catch (_) {}
  }

  function noteTrustedGesture(event) {
    if (!event.isTrusted) return;
    trustedGestureActive = true;
    queueMicrotask(() => { trustedGestureActive = false; });
  }

  function installWindowOpenGuard() {
    Object.defineProperty(window, 'open', {
      configurable: false,
      writable: false,
      value: function(candidate) {
        if (candidate != null) {
          let url = String(candidate);
          try { url = new URL(url, location.href).href; } catch (_) {}
          post({
            type: 'external_navigation',
            url,
            userGesture: trustedGestureActive
          });
        }
        return null;
      }
    });
  }

  function normalizePath(url) {
    let path = url.pathname || '/';
    if (/%(?:2e|2f|5c|00)/i.test(path) || path.includes('\\')) return null;
    if (!path.startsWith('/')) path = `/${path}`;
    return path === '/' || path.endsWith('/') ? path : `${path}/`;
  }

  function safeLocation(urlLike) {
    try {
      const url = new URL(urlLike, location.href);
      return `${normalizePath(url) || '/invalid/'}${url.search}${url.hash}`;
    } catch (_) {
      return '/invalid/';
    }
  }

  function sharedContentIdentity(urlLike) {
    let url;
    try { url = new URL(urlLike, location.href); } catch (_) { return null; }
    if (url.protocol !== 'https:' || !TRUSTED_HOSTS.has(url.hostname.toLowerCase())) return null;
    const path = normalizePath(url);
    if (!path) return null;
    return RULES.instagramSharedContentIdentity(path);
  }

  function sealedToken(urlLike) {
    try {
      const token = new URLSearchParams(new URL(urlLike, location.href).hash.slice(1))
        .get('clearfeed_shared');
      return /^[a-f0-9]{32}$/.test(token || '') ? token : null;
    } catch (_) {
      return null;
    }
  }

  function isExactSealedItem(urlLike) {
    if (!SEALED) return false;
    let url;
    try { url = new URL(urlLike, location.href); } catch (_) { return false; }
    return RULES.instagramSealedItemDecision({
      targetPath: normalizePath(url),
      targetToken: sealedToken(url),
      approvedIdentity: SEALED,
      approvedToken: SEALED.token
    }) === 'allow_same_item';
  }

  function isDirectThread(urlLike) {
    try {
      const url = new URL(urlLike, location.href);
      return TRUSTED_HOSTS.has(url.hostname.toLowerCase()) &&
        /^\/direct\/t\/[A-Za-z0-9_-]+\/$/.test(normalizePath(url) || '');
    } catch (_) {
      return false;
    }
  }

  function routeKind(urlLike) {
    let url;
    try { url = new URL(urlLike, location.href); } catch (_) { return 'blocked'; }
    if (url.protocol !== 'https:') return 'blocked';
    if (!TRUSTED_HOSTS.has(url.hostname.toLowerCase())) return 'external';
    const path = normalizePath(url);
    if (!path) return 'blocked';
    if (path === '/direct/inbox/' || path === '/direct/requests/' ||
        path === '/direct/new/' || /^\/direct\/t\/[A-Za-z0-9_-]+\/$/.test(path)) {
      return 'direct';
    }
    if (path === '/accounts/login/' || path === '/accounts/login/two_factor/' ||
        path === '/accounts/onetap/' || path === '/accounts/confirm_email/' ||
        path === '/accounts/confirm_phone/' || path === '/accounts/account_recovery/' ||
        path === '/accounts/consent/' || path.startsWith('/accounts/password/reset/') ||
        path.startsWith('/privacy/checks/') ||
        /^\/(?:challenge|checkpoint)(?:\/[A-Za-z0-9_-]+)*\/$/.test(path)) {
      return 'auth';
    }
    if (isExactSealedItem(url.href)) return 'shared';
    return 'blocked';
  }

  function routeAttribute() {
    const kind = routeKind(location.href);
    if (kind === 'direct' && isDirectThread(location.href)) return 'direct-thread';
    return kind;
  }

  function updateRouteAttribute() {
    document.documentElement.setAttribute('data-directonly-route', routeAttribute());
  }

  function installStyle() {
    const style = document.createElement('style');
    style.id = 'directonly-guard-style';
    style.textContent = GUARD_CSS;
    (document.head || document.documentElement).appendChild(style);
  }

  function concealDocumentIfUnsafe() {
    updateRouteAttribute();
    const kind = routeKind(location.href);
    const safe = kind === 'direct' || kind === 'auth' || kind === 'shared';
    document.documentElement.toggleAttribute('data-directonly-blocked', !safe);
    if (!safe) post({ type: 'navigation_candidate', path: safeLocation(location.href) });
    return safe;
  }

  function hideElement(element, sealed = false) {
    if (!element) return;
    const key = sealed ? 'directonlySealedHidden' : 'directonlyHidden';
    if (element.dataset?.[key] === 'true') return;
    element.dataset[key] = 'true';
    element.setAttribute('aria-hidden', 'true');
    element.setAttribute('tabindex', '-1');
    element.hidden = true;
  }

  function isContentPath(path) {
    return CONTENT_PREFIXES.some(prefix => path.startsWith(prefix));
  }

  function inDirectMessageSurface(anchor) {
    if (!isDirectThread(location.href) || !anchor.closest('main')) return false;
    if (anchor.closest('nav,header,[role="navigation"]')) return false;
    return Boolean(
      anchor.closest(
        '[role="row"],[role="listitem"],article,[data-testid*="message" i],' +
        '[aria-label*="message" i],main'
      )
    );
  }

  function isTrustedSharedContentTap(event, anchor, candidate) {
    return RULES.instagramDmTapDecision({
      currentRoute: isDirectThread(location.href) ? 'direct-thread' : routeKind(location.href),
      eventIsTrusted: event.isTrusted,
      insideMessageSurface: inDirectMessageSurface(anchor),
      targetPath: normalizePath(candidate)
    }) === 'authorize_exact_item';
  }

  function sanitizeAnchor(anchor) {
    if (!anchor?.getAttribute) return;
    const href = anchor.getAttribute('href');
    if (!href) return;
    let candidate;
    try { candidate = new URL(href, location.href); } catch (_) { hideElement(anchor); return; }

    const currentKind = routeKind(location.href);
    if (currentKind === 'shared') {
      const identity = sharedContentIdentity(candidate);
      const exactIdentity = identity && identity.kind === SEALED?.kind &&
        identity.contentId === SEALED?.contentId;
      if (exactIdentity) {
        candidate.hash = `clearfeed_shared=${SEALED.token}`;
        anchor.href = candidate.href;
      }
      const exactItem = isExactSealedItem(candidate.href);
      const decision = RULES.instagramSealedLinkDecision({ isExactApprovedItem: exactItem });
      if (decision === 'allow_same_item') return;
      const unsafeSurface = identity ?
        anchor.closest('article,[role="article"],[role="listitem"]') : anchor;
      hideElement(unsafeSurface || anchor, true);
      return;
    }
    if (!TRUSTED_HOSTS.has(candidate.hostname.toLowerCase())) return;

    const candidateKind = routeKind(candidate.href);
    const identity = sharedContentIdentity(candidate);
    if (currentKind === 'direct' && isDirectThread(location.href) && identity &&
        inDirectMessageSurface(anchor)) return;
    if (candidateKind === 'direct' || candidateKind === 'auth') return;

    const path = normalizePath(candidate) || '/invalid/';
    if (isContentPath(path) && !anchor.previousElementSibling?.classList?.contains('directonly-shared-hidden')) {
      const placeholder = document.createElement('span');
      placeholder.className = 'directonly-shared-hidden';
      placeholder.textContent = 'Shared Instagram content available only from a conversation';
      placeholder.setAttribute('role', 'note');
      anchor.parentNode?.insertBefore(placeholder, anchor);
    }
    hideElement(anchor);
  }

  function sanitizeLabels(root) {
    const nodes = [];
    if (root.matches?.('[aria-label]')) nodes.push(root);
    root.querySelectorAll?.('[aria-label]').forEach(node => nodes.push(node));
    nodes.forEach(node => {
      const label = node.getAttribute('aria-label') || '';
      const appEscape = /open (?:in )?(?:instagram|app)/i.test(label);
      const sealedUnsafe = routeKind(location.href) === 'shared' &&
        (/comment|suggested|related|more like this|profile|original audio/i.test(label) ||
          (SEALED?.kind === 'reel' && /next|previous/i.test(label)));
      if (!UNSAFE_LABELS.has(label) && !sealedUnsafe && !appEscape) return;
      const interactive = node.closest('a,[role="button"],button,section') || node;
      hideElement(interactive, routeKind(location.href) === 'shared');
    });
  }

  function findVerticalReelStream(main) {
    return Array.from(main.querySelectorAll('*')).find(element => {
      const style = getComputedStyle(element);
      return /(auto|scroll)/.test(style.overflowY) &&
        element.scrollHeight > element.clientHeight &&
        element.querySelectorAll('video').length > 1;
    }) || null;
  }

  function sealVerticalReelStream(main) {
    const stream = findVerticalReelStream(main);
    if (!stream) return;
    const videoItems = Array.from(stream.children).filter(child => child.querySelector('video'));
    if (videoItems.length <= 1) return;
    const identityTarget = videoItems.find(item => Array.from(item.querySelectorAll('a[href]'))
      .some(anchor => {
        const identity = sharedContentIdentity(anchor.href);
        return identity && identity.kind === SEALED?.kind && identity.contentId === SEALED?.contentId;
      }));
    const viewportTarget = identityTarget || videoItems.reduce((best, item) => {
      const distance = Math.abs(item.getBoundingClientRect().top - 60);
      return !best || distance < best.distance ? { item, distance } : best;
    }, null)?.item;
    if (!viewportTarget) return;
    videoItems.forEach(item => {
      if (item !== viewportTarget) hideElement(item, true);
    });
    stream.dataset.directonlySealedStream = 'true';
    stream.style.overflowY = 'hidden';
    stream.style.overscrollBehavior = 'none';
    stream.style.touchAction = 'pan-x';
    stream.scrollTop = 0;
    sealedStream = stream;
    sealedStreamItem = viewportTarget;
  }

  function sanitizeSealedViewer() {
    if (routeKind(location.href) !== 'shared') return;
    const main = document.querySelector('main');
    if (!main) return;
    document.querySelectorAll('nav,[role="navigation"]').forEach(node => hideElement(node, true));
    main.querySelectorAll('[aria-label*="comment" i]').forEach(node => {
      hideElement(node.closest('button,a,section,[role="button"]') || node, true);
    });
    main.querySelectorAll('ul,form,textarea,[contenteditable="true"]').forEach(node => {
      hideElement(node, true);
    });
    sealVerticalReelStream(main);

    const primaryVideo = main.querySelector('video');
    const primaryRoot = sealedStreamItem ||
      primaryVideo?.closest('article,[role="article"]') || main;
    main.querySelectorAll('video').forEach(video => {
      if (!primaryRoot.contains(video)) hideElement(video.closest('div') || video, true);
      video.autoplay = false;
      video.removeAttribute('autoplay');
    });
  }

  function repairAuthenticationLayout() {
    const password = document.querySelector('input[type="password"]');
    if (!password) return;
    const viewportHeight = `${Math.max(600, window.innerHeight)}px`;
    const layoutRoots = [
      document.documentElement,
      document.body,
      document.getElementById('viewport'),
      document.getElementById('page'),
      document.getElementById('root')
    ];
    layoutRoots.forEach(element => {
      if (!element) return;
      element.style.setProperty('height', viewportHeight, 'important');
      element.style.setProperty('min-height', viewportHeight, 'important');
      element.style.setProperty('max-height', 'none', 'important');
      element.style.setProperty('overflow', 'visible', 'important');
    });
    let ancestor = password.parentElement;
    while (ancestor && ancestor !== document.body) {
      if (ancestor.getBoundingClientRect().height < 1) {
        ancestor.style.setProperty('height', viewportHeight, 'important');
        ancestor.style.setProperty('min-height', viewportHeight, 'important');
        ancestor.style.setProperty('max-height', 'none', 'important');
        ancestor.style.setProperty('overflow', 'visible', 'important');
      }
      ancestor = ancestor.parentElement;
    }
  }

  function loginSurfaceIsVisible() {
    const password = document.querySelector('input[type="password"]');
    if (!password) return false;
    const style = getComputedStyle(password);
    const rect = password.getBoundingClientRect();
    if (style.display === 'none' || style.visibility === 'hidden' ||
      Number(style.opacity || 1) <= 0 || rect.width < 24 || rect.height < 16 ||
      rect.right <= 0 || rect.left >= window.innerWidth ||
      rect.bottom <= 0 || rect.top >= window.innerHeight) return false;
    let visibleLeft = Math.max(0, rect.left);
    let visibleTop = Math.max(0, rect.top);
    let visibleRight = Math.min(window.innerWidth, rect.right);
    let visibleBottom = Math.min(window.innerHeight, rect.bottom);
    for (let ancestor = password.parentElement; ancestor; ancestor = ancestor.parentElement) {
      const ancestorStyle = getComputedStyle(ancestor);
      if (ancestorStyle.display === 'none' || ancestorStyle.visibility === 'hidden' ||
        Number(ancestorStyle.opacity || 1) <= 0) return false;
      if (/(hidden|clip|scroll|auto)/.test(`${ancestorStyle.overflow} ${ancestorStyle.overflowX} ${ancestorStyle.overflowY}`)) {
        const ancestorRect = ancestor.getBoundingClientRect();
        visibleLeft = Math.max(visibleLeft, ancestorRect.left);
        visibleTop = Math.max(visibleTop, ancestorRect.top);
        visibleRight = Math.min(visibleRight, ancestorRect.right);
        visibleBottom = Math.min(visibleBottom, ancestorRect.bottom);
        if (visibleRight - visibleLeft < 8 || visibleBottom - visibleTop < 8) return false;
      }
    }
    const hit = document.elementFromPoint(
      (visibleLeft + visibleRight) / 2,
      (visibleTop + visibleBottom) / 2
    );
    return Boolean(hit && (hit === password || password.contains(hit))) &&
      Number(style.opacity || 1) > 0 && rect.width >= 24 && rect.height >= 16 &&
      rect.right > 0 && rect.left < window.innerWidth &&
      rect.bottom > 0 && rect.top < window.innerHeight;
  }

  function sanitize(root) {
    if (!root || root.nodeType !== Node.ELEMENT_NODE) return;
    // Authentication has no feed surface. Keep Meta's form intact while native
    // navigation policy continues to prevent escapes into Instagram content.
    if (routeKind(location.href) === 'auth') {
      repairAuthenticationLayout();
      return;
    }
    if (root.matches?.('a[href]')) sanitizeAnchor(root);
    root.querySelectorAll?.('a[href]').forEach(sanitizeAnchor);
    sanitizeLabels(root);
    sanitizeSealedViewer();
  }

  function reportHealth() {
    clearTimeout(healthTimer);
    healthTimer = window.setTimeout(() => {
      const kind = routeKind(location.href);
      const path = safeLocation(location.href);
      let healthy = Boolean(document.body);
      if (kind === 'direct') {
        healthy = Boolean(document.querySelector('main'));
        if (normalizePath(new URL(location.href)) === '/direct/inbox/') {
          healthy = healthy && Boolean(document.querySelector('[aria-label="Thread list"]'));
        }
      } else if (kind === 'shared') {
        const main = document.querySelector('main');
        healthy = Boolean(main && main.querySelector('video,img')) && isExactSealedItem(location.href);
      } else if (kind === 'auth') {
        healthy = loginSurfaceIsVisible();
      } else {
        healthy = false;
      }
      post({ type: 'guard_health', version: GUARD_VERSION, path, healthy });
    }, 140);
  }

  function queueScan(root) {
    if (scanQueued) return;
    scanQueued = true;
    window.setTimeout(() => {
      scanQueued = false;
      sanitize(root || document.documentElement);
      reportHealth();
    }, 55);
  }

  function auditLocation() {
    const current = location.href;
    if (current === lastHref) return;
    lastHref = current;
    updateRouteAttribute();
    const kind = routeKind(current);
    const safe = kind === 'direct' || kind === 'auth' || kind === 'shared';
    document.documentElement.toggleAttribute('data-directonly-blocked', !safe);
    post({ type: 'navigation_candidate', path: safeLocation(current) });
    if (safe) queueScan(document.documentElement);
  }

  function guardHistoryMethod(name) {
    const original = history[name];
    Object.defineProperty(history, name, {
      configurable: false,
      writable: false,
      value: function(state, title, candidate) {
        if (candidate != null) {
          const target = new URL(candidate, location.href);
          const identity = sharedContentIdentity(target);
          if (SEALED && identity && identity.kind === SEALED.kind &&
              identity.contentId === SEALED.contentId) {
            target.hash = `clearfeed_shared=${SEALED.token}`;
            const result = original.call(this, state, title, target.href);
            auditLocation();
            return result;
          }
          const kind = routeKind(target.href);
          if (kind !== 'direct' && kind !== 'auth' && kind !== 'shared') {
            post({ type: 'blocked_navigation', path: safeLocation(target.href) });
            return;
          }
        }
        const result = original.apply(this, arguments);
        auditLocation();
        return result;
      }
    });
  }

  updateRouteAttribute();
  installStyle();
  concealDocumentIfUnsafe();
  guardHistoryMethod('pushState');
  guardHistoryMethod('replaceState');
  installWindowOpenGuard();

  document.addEventListener('pointerup', noteTrustedGesture, true);
  document.addEventListener('touchend', noteTrustedGesture, true);

  document.addEventListener('click', event => {
    noteTrustedGesture(event);
    const anchor = event.target?.closest?.('a[href]');
    if (!anchor) return;
    let candidate;
    try { candidate = new URL(anchor.href, location.href); } catch (_) {
      event.preventDefault(); event.stopImmediatePropagation(); return;
    }

    if (candidate.protocol !== 'https:') {
      event.preventDefault();
      event.stopImmediatePropagation();
      post({ type: 'external_navigation', url: candidate.href, userGesture: event.isTrusted });
      return;
    }

    if (isTrustedSharedContentTap(event, anchor, candidate)) {
      event.preventDefault();
      event.stopImmediatePropagation();
      post({ type: 'intentional_instagram_shared_content', path: `${normalizePath(candidate)}${candidate.search}` });
      return;
    }

    const kind = routeKind(candidate.href);
    if (kind === 'external') {
      event.preventDefault();
      event.stopImmediatePropagation();
      post({ type: 'external_navigation', url: candidate.href, userGesture: event.isTrusted });
      return;
    }
    if (kind !== 'direct' && kind !== 'auth' && kind !== 'shared') {
      event.preventDefault();
      event.stopImmediatePropagation();
      post({ type: 'blocked_navigation', path: safeLocation(candidate.href) });
      return;
    }
    if (anchor.target && anchor.target.toLowerCase() !== '_self') {
      event.preventDefault();
      event.stopImmediatePropagation();
      location.assign(candidate.href);
    }
  }, true);

  document.addEventListener('touchstart', event => {
    if (routeKind(location.href) !== 'shared' || event.touches.length !== 1) return;
    touchStartX = event.touches[0].clientX;
    touchStartY = event.touches[0].clientY;
  }, { capture: true, passive: true });

  document.addEventListener('touchmove', event => {
    if (routeKind(location.href) !== 'shared' || event.touches.length !== 1) return;
    const deltaX = Math.abs(event.touches[0].clientX - touchStartX);
    const deltaY = Math.abs(event.touches[0].clientY - touchStartY);
    const decision = RULES.instagramSealedGestureDecision({
      deltaX,
      deltaY,
      isMediaControl: Boolean(event.target?.closest?.('input,[role="slider"]'))
    });
    if (decision === 'block_vertical') {
      event.preventDefault();
      event.stopImmediatePropagation();
      if (sealedStream) sealedStream.scrollTop = 0;
    }
  }, { capture: true, passive: false });

  document.addEventListener('wheel', event => {
    if (routeKind(location.href) === 'shared' && Math.abs(event.deltaY) > Math.abs(event.deltaX)) {
      event.preventDefault();
      event.stopImmediatePropagation();
    }
  }, { capture: true, passive: false });

  document.addEventListener('ended', event => {
    if (routeKind(location.href) !== 'shared' || event.target?.tagName !== 'VIDEO') return;
    const decision = RULES.instagramSealedMediaEndDecision({
      isApprovedMedia: !event.target.closest?.('[data-directonly-sealed-hidden="true"]')
    });
    if (decision !== 'stop_no_advance') return;
    event.target.pause();
    event.target.autoplay = false;
    event.target.removeAttribute('autoplay');
    event.stopImmediatePropagation();
  }, true);

  const observer = new MutationObserver(records => {
    for (const record of records) for (const node of record.addedNodes) {
      if (node.nodeType === Node.ELEMENT_NODE) queueScan(node);
    }
  });
  observer.observe(document.documentElement, { childList: true, subtree: true });

  window.addEventListener('popstate', () => {
    auditLocation();
    if (!['direct', 'auth', 'shared'].includes(routeKind(location.href))) {
      document.documentElement.setAttribute('data-directonly-blocked', 'true');
      post({ type: 'blocked_navigation', path: safeLocation(location.href) });
    }
  }, true);
  window.addEventListener('pageshow', () => { auditLocation(); queueScan(document.documentElement); });
  document.addEventListener('DOMContentLoaded', () => queueScan(document.documentElement), { once: true });
  window.setInterval(() => {
    if (!document.hidden) {
      auditLocation();
      if (sealedStream) sealedStream.scrollTop = 0;
    }
  }, 500);

  post({ type: 'guard_ready', version: GUARD_VERSION, path: safeLocation(location.href) });
})();
