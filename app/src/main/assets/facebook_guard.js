(() => {
  'use strict';

  if (window.__clearFeedFacebookGuardInstalled) return;
  Object.defineProperty(window, '__clearFeedFacebookGuardInstalled', { value: true });

  const GUARD_VERSION = __CLEARFEED_GUARD_VERSION__;
  const GUARD_CSS = __CLEARFEED_CSS_JSON__;
  const BRIDGE_TOKEN = __CLEARFEED_BRIDGE_TOKEN_JSON__;
  const NATIVE_POST = window.clearFeedBridge?.postMessage?.bind(window.clearFeedBridge);
  const RULES = window.__clearFeedGuardRules;
  const FACEBOOK_FEED_POST_LIMIT = 8;
  const FB_HOSTS = new Set(['facebook.com', 'www.facebook.com', 'm.facebook.com']);
  const MESSENGER_HOSTS = new Set(['messenger.com', 'www.messenger.com']);
  const BLOCKED_PREFIXES = ['/reel/', '/reels/', '/watch/', '/watch.php/', '/video/', '/video.php/', '/videos/', '/live/', '/stories/', '/story.php/', '/marketplace/', '/gaming/'];
  const POST_SELECTOR = '[role="article"],article,[data-pagelet*="FeedUnit" i]';
  const articleStates = new WeakMap();
  let lastHref = location.href;
  let scanQueued = false;
  let healthTimer = 0;
  let authenticationRequested = false;
  let retainedPosts = 0;
  let trustedGestureActive = false;

  function post(payload) {
    try { NATIVE_POST?.(JSON.stringify({ ...payload, bridgeToken: BRIDGE_TOKEN })); } catch (_) {}
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
          post({ type: 'external_navigation', url, userGesture: trustedGestureActive });
        }
        return null;
      }
    });
  }

  function normalizedPath(url) {
    let path = url.pathname || '/';
    if (/%(?:2e|2f|5c|00)/i.test(path) || path.includes('\\')) return null;
    if (!path.startsWith('/')) path = `/${path}`;
    return path === '/' || path.endsWith('/') ? path : `${path}/`;
  }

  function pathAndQuery(urlLike) {
    try {
      const url = new URL(urlLike, location.href);
      return `${normalizedPath(url) || '/invalid/'}${url.search}`;
    } catch (_) { return '/invalid/'; }
  }

  function routeKind(urlLike) {
    let url;
    try { url = new URL(urlLike, location.href); } catch (_) { return 'blocked'; }
    if (url.protocol !== 'https:') return 'blocked';
    const host = url.hostname.toLowerCase();
    const path = normalizedPath(url);
    if (!path) return 'blocked';
    if (!FB_HOSTS.has(host) && !MESSENGER_HOSTS.has(host)) return 'external';
    if (MESSENGER_HOSTS.has(host)) {
      if (path === '/' || path.startsWith('/login/')) return 'auth';
      if (path.startsWith('/t/') || path === '/new/') return 'messages';
      return 'blocked';
    }
    if (path === '/login.php/' || path === '/unified/login_via/app/' || path.startsWith('/login/') || path.startsWith('/checkpoint/') || path.startsWith('/recover/') ||
        path.startsWith('/two_step_verification/') || path.startsWith('/privacy/consent/')) return 'auth';
    const queryKeys = Array.from(url.searchParams.keys());
    if (path === '/' && url.searchParams.has('_rdr') &&
        queryKeys.every(key => key === '_rdr' || key === '__mmr')) return 'auth';
    if (BLOCKED_PREFIXES.some(prefix => path.startsWith(prefix))) return 'blocked';
    if (path === '/' && RULES.facebookFeedRouteDecision(path, url.search) === 'newest_feed') return 'feed';
    if (path === '/messages/' || path.startsWith('/messages/t/') || path.startsWith('/messages/e2ee/t/')) return 'messages';
    if (path === '/notifications/') return 'notifications';
    if (path.startsWith('/search/')) return 'search';
    if (path === '/friends/' || path === '/friends/list/' || path === '/friends/requests/') return 'friends';
    if (/^\/groups\/[A-Za-z0-9._-]+\/(?:posts\/[A-Za-z0-9._-]+\/)?$/.test(path)) return 'group';
    if (/^\/events\/[A-Za-z0-9._-]+\/$/.test(path)) return 'event';
    if (path === '/permalink.php/' || path === '/photo.php/' || path === '/photo/' ||
        /^\/[A-Za-z0-9._-]+\/posts\/[A-Za-z0-9._-]+\/$/.test(path)) return 'post';
    if (path === '/profile.php/' || /^\/pages\/[A-Za-z0-9._-]+\/[A-Za-z0-9._-]+\/$/.test(path) ||
        /^\/[A-Za-z0-9._-]+\/$/.test(path)) return 'page';
    return 'blocked';
  }

  function isSafeKind(kind) {
    return ['auth', 'feed', 'messages', 'notifications', 'search', 'friends', 'group', 'event', 'page', 'post'].includes(kind);
  }

  function isStreamKind(kind) {
    return ['feed', 'friends', 'group', 'page'].includes(kind);
  }

  function installStyle() {
    const style = document.createElement('style');
    style.id = 'clearfeed-facebook-guard-style';
    style.textContent = GUARD_CSS;
    (document.head || document.documentElement).appendChild(style);
  }

  function hide(element) {
    if (!element || element.dataset?.clearfeedHidden === 'true') return;
    element.dataset.clearfeedHidden = 'true';
    element.setAttribute('aria-hidden', 'true');
    element.setAttribute('tabindex', '-1');
    element.hidden = true;
  }

  function unsafeRoute(urlLike) {
    let url;
    try { url = new URL(urlLike, location.href); } catch (_) { return true; }
    if (!FB_HOSTS.has(url.hostname.toLowerCase()) && !MESSENGER_HOSTS.has(url.hostname.toLowerCase())) return false;
    return RULES.isUnsafeFacebookHref(url.href);
  }

  function sanitizeAnchor(anchor) {
    if (!anchor?.getAttribute) return;
    const href = anchor.getAttribute('href');
    if (href) {
      try {
        const candidate = new URL(href, location.href);
        if (FB_HOSTS.has(candidate.hostname.toLowerCase()) && normalizedPath(candidate) === '/' &&
            RULES.facebookFeedRouteDecision('/', candidate.search) !== 'newest_feed') {
          candidate.search = 'filter=all&sk=h_chr';
          anchor.href = candidate.href;
        }
      } catch (_) {}
    }
    if (href && unsafeRoute(href)) hide(anchor);
    const label = anchor.getAttribute('aria-label') || '';
    if (/^(reels?|stories?|watch|videos?|marketplace|gaming)$/i.test(label.trim()) ||
        /open (?:in )?(?:facebook|messenger|app)/i.test(label)) hide(anchor);
  }

  function articleIsUnsafe(article, allowMessageAttachments) {
    const hrefs = Array.from(article.querySelectorAll?.('a[href]') || [])
      .map(anchor => anchor.getAttribute('href') || '');
    const labels = Array.from(article.querySelectorAll?.('[aria-label]') || [])
      .map(element => element.getAttribute('aria-label') || '');
    return RULES.facebookArticleDecision({
      allowMessageAttachments,
      hasVideo: Boolean(article.querySelector?.('video')),
      hrefs,
      labels,
      text: article.innerText || ''
    }) === 'hide_unsafe';
  }

  function fallbackPostCard(descendant) {
    const direct = descendant.closest(POST_SELECTOR);
    if (direct) return direct;
    let cursor = descendant.parentElement;
    let best = null;
    while (cursor && cursor !== document.body && cursor !== document.querySelector('main')) {
      const actions = cursor.querySelectorAll('[aria-label^="Actions for this post" i]').length;
      if (actions > 1) break;
      if (actions === 1) best = cursor;
      cursor = cursor.parentElement;
    }
    return best;
  }

  function semanticPostCard(action) {
    return fallbackPostCard(action);
  }

  function collectPostCandidates(root) {
    const candidates = new Set();
    if (root.matches?.(POST_SELECTOR)) candidates.add(root);
    root.closest?.(POST_SELECTOR) && candidates.add(root.closest(POST_SELECTOR));
    const fallback = fallbackPostCard(root);
    if (fallback) candidates.add(fallback);
    root.querySelectorAll?.(POST_SELECTOR).forEach(article => candidates.add(article));
    const actions = [];
    if (root.matches?.('[aria-label^="Actions for this post" i]')) actions.push(root);
    root.querySelectorAll?.('[aria-label^="Actions for this post" i]').forEach(action => actions.push(action));
    actions.forEach(action => {
      const card = semanticPostCard(action);
      if (card) candidates.add(card);
    });
    return Array.from(candidates).filter(candidate =>
      !Array.from(candidates).some(other => other !== candidate && other.contains(candidate))
    );
  }

  function articleIsReady(article) {
    if (article.matches('[aria-busy="true"]')) return false;
    const hasPostAction = Boolean(article.querySelector(
      '[aria-label^="Actions for this post" i],button[aria-label="React" i],' +
      'button[aria-label^="Like" i],button[aria-label*="comment" i]'
    ));
    const onlyLoading = Boolean(article.querySelector('[role="status"]')) &&
      !article.querySelector('a[href],img,video') && !hasPostAction;
    return hasPostAction && !onlyLoading;
  }

  function articleIdentity(article) {
    for (const anchor of article.querySelectorAll('a[href]')) {
      try {
        const url = new URL(anchor.href, location.href);
        const path = normalizedPath(url) || '';
        if (/\/(?:posts|permalink|photo)\b/i.test(path) ||
            url.searchParams.has('fbid') || url.searchParams.has('story_fbid')) {
          return `${path}?${url.searchParams.get('fbid') || url.searchParams.get('story_fbid') || ''}`;
        }
      } catch (_) {}
    }
    return article.querySelector('[aria-label^="Actions for this post" i]')
      ?.getAttribute('aria-label') || null;
  }

  function appendEndCard() {
    if (!document.querySelector('.clearfeed-feed-end')) {
      const card = document.createElement('section');
      card.className = 'clearfeed-feed-end';
      card.setAttribute('role', 'note');
      card.innerHTML = "You're caught up here.<small>Messages, search and notifications are still available.</small>";
      (document.querySelector('main') || document.body)?.appendChild(card);
    }
    document.querySelectorAll('[role="progressbar"], [aria-busy="true"]').forEach(hide);
    document.querySelectorAll('button,a[role="button"]').forEach(control => {
      if (/load more|continue|see more posts/i.test(control.textContent || '')) hide(control);
    });
  }

  function sanitizeArticles(root, kind) {
    const allowMessageAttachments = kind === 'messages';
    collectPostCandidates(root).forEach(article => {
      if (!articleIsReady(article)) return;
      const identity = articleIdentity(article);
      const previous = articleStates.get(article);
      const sameRenderedPost = previous && previous.route === lastHref &&
        (!previous.identity || !identity || previous.identity === identity);
      const articleDecision = articleIsUnsafe(article, allowMessageAttachments) ? 'hide_unsafe' : 'keep';
      if (articleDecision === 'hide_unsafe') {
        hide(article);
        articleStates.set(article, { route: lastHref, identity, action: 'hide_unsafe' });
        return;
      }
      if (sameRenderedPost) return;
      const feedDecision = RULES.facebookFeedDecision({
        articleDecision,
        isStream: isStreamKind(kind),
        retainedCount: retainedPosts
      });
      if (feedDecision.action === 'hide_unsafe') {
        hide(article);
        articleStates.set(article, { route: lastHref, identity, action: 'hide_unsafe' });
        return;
      }
      if (feedDecision.action === 'hide_limit') {
        hide(article);
        articleStates.set(article, { route: lastHref, identity, action: 'hide_limit' });
        appendEndCard();
        return;
      }
      if (!isStreamKind(kind)) {
        articleStates.set(article, { route: lastHref, identity, action: 'keep' });
        return;
      }
      retainedPosts = feedDecision.nextCount;
      article.dataset.clearfeedSafePost = String(retainedPosts);
      articleStates.set(article, { route: lastHref, identity, action: 'keep' });
      if (feedDecision.endReached) appendEndCard();
    });
  }

  function repairAuthenticationLayout() {
    const password = document.querySelector('input[type="password"]');
    if (!password) return;
    // Facebook's unified Android login shell can resolve 100vh to 0 inside a
    // WebView even though window.innerHeight is correct. Use the measured CSS
    // viewport in pixels so its otherwise healthy form is not clipped away.
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
    const kind = routeKind(location.href);
    if (kind === 'auth') {
      repairAuthenticationLayout();
      return;
    }
    if (root.matches?.('a[href]')) sanitizeAnchor(root);
    root.querySelectorAll?.('a[href]').forEach(sanitizeAnchor);
    if (kind !== 'messages') {
      const videos = [];
      if (root.matches?.('video')) videos.push(root);
      root.querySelectorAll?.('video').forEach(video => videos.push(video));
      videos.forEach(video => hide(fallbackPostCard(video) || video));
    }
    root.querySelectorAll?.('[aria-label="Stories" i],[data-pagelet*="Stories" i]').forEach(hide);
    sanitizeArticles(root, kind);
    if (retainedPosts >= FACEBOOK_FEED_POST_LIMIT && isStreamKind(kind)) appendEndCard();
    const primaryPost = collectPostCandidates(document.documentElement).find(articleIsReady);
    if (kind === 'post' && primaryPost && articleIsUnsafe(primaryPost, false)) {
      document.documentElement.setAttribute('data-clearfeed-blocked', 'true');
      post({ type: 'blocked_navigation', path: pathAndQuery(location.href) });
    }
  }

  function reportHealth() {
    clearTimeout(healthTimer);
    healthTimer = window.setTimeout(() => {
      const kind = routeKind(location.href);
      const loginSurface = kind === 'feed' && Boolean(document.querySelector('input[type="password"]'));
      if (loginSurface) {
        repairAuthenticationLayout();
        if (loginSurfaceIsVisible() && !authenticationRequested) {
          authenticationRequested = true;
          post({ type: 'authentication_required', path: pathAndQuery(location.href) });
        } else if (!loginSurfaceIsVisible()) {
          post({ type: 'guard_health', version: GUARD_VERSION, path: pathAndQuery(location.href), healthy: false });
        }
        return;
      }
      let healthy = Boolean(document.body) && isSafeKind(kind);
      if (kind === 'auth') healthy = healthy && loginSurfaceIsVisible();
      if (kind === 'post') {
        const primaryPost = collectPostCandidates(document.documentElement).find(articleIsReady);
        healthy = healthy && Boolean(primaryPost) && !articleIsUnsafe(primaryPost, false);
      }
      post({ type: 'guard_health', version: GUARD_VERSION, path: pathAndQuery(location.href), healthy });
    }, 180);
  }

  function queueScan(root) {
    if (scanQueued) return;
    scanQueued = true;
    window.setTimeout(() => {
      scanQueued = false;
      sanitize(root || document.documentElement);
      reportHealth();
    }, 70);
  }

  function auditLocation() {
    if (location.href === lastHref) return;
    lastHref = location.href;
    retainedPosts = 0;
    document.querySelector('.clearfeed-feed-end')?.remove();
    const safe = isSafeKind(routeKind(location.href));
    document.documentElement.dataset.clearfeedFacebookRoute = routeKind(location.href);
    document.documentElement.toggleAttribute('data-clearfeed-blocked', !safe);
    post({ type: 'navigation_candidate', path: pathAndQuery(location.href) });
    if (safe) queueScan(document.documentElement);
  }

  function guardHistory(name) {
    const original = history[name];
    Object.defineProperty(history, name, {
      configurable: false,
      writable: false,
      value: function(state, title, candidate) {
        if (candidate != null && !isSafeKind(routeKind(candidate))) {
          post({ type: 'blocked_navigation', path: pathAndQuery(candidate) });
          return;
        }
        const result = original.apply(this, arguments);
        auditLocation();
        return result;
      }
    });
  }

  installStyle();
  const initialSafe = isSafeKind(routeKind(location.href));
  document.documentElement.dataset.clearfeedFacebookRoute = routeKind(location.href);
  document.documentElement.toggleAttribute('data-clearfeed-blocked', !initialSafe);
  guardHistory('pushState');
  guardHistory('replaceState');
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
    const kind = routeKind(candidate.href);
    if (kind === 'external') {
      event.preventDefault(); event.stopImmediatePropagation();
      post({ type: 'external_navigation', url: candidate.href, userGesture: event.isTrusted });
    } else if (!isSafeKind(kind)) {
      event.preventDefault(); event.stopImmediatePropagation();
      post({ type: 'blocked_navigation', path: pathAndQuery(candidate.href) });
    }
  }, true);

  const observer = new MutationObserver(records => {
    for (const record of records) for (const node of record.addedNodes) {
      if (node.nodeType === Node.ELEMENT_NODE) queueScan(node);
    }
  });
  observer.observe(document.documentElement, { childList: true, subtree: true });
  window.addEventListener('popstate', auditLocation, true);
  window.addEventListener('resize', () => {
    if (routeKind(location.href) === 'auth') repairAuthenticationLayout();
  });
  window.addEventListener('pageshow', () => { auditLocation(); queueScan(document.documentElement); });
  document.addEventListener('DOMContentLoaded', () => queueScan(document.documentElement), { once: true });
  window.setInterval(() => { if (!document.hidden) auditLocation(); }, 700);
  post({ type: 'guard_ready', version: GUARD_VERSION, path: pathAndQuery(location.href) });
})();
