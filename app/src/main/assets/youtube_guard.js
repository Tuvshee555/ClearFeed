(() => {
  'use strict';

  if (window.__clearFeedYouTubeGuardInstalled) return;
  Object.defineProperty(window, '__clearFeedYouTubeGuardInstalled', { value: true });

  const GUARD_VERSION = __CLEARFEED_GUARD_VERSION__;
  const GUARD_CSS = __CLEARFEED_CSS_JSON__;
  const BRIDGE_TOKEN = __CLEARFEED_BRIDGE_TOKEN_JSON__;
  const NATIVE_POST = window.clearFeedBridge?.postMessage?.bind(window.clearFeedBridge);
  const RULES = window.__clearFeedGuardRules;
  const YOUTUBE_HOSTS = new Set(['youtube.com', 'www.youtube.com', 'm.youtube.com']);
  const AUTH_HOSTS = new Set(['accounts.google.com', 'consent.google.com', 'consent.youtube.com']);
  let lastHref = location.href;
  let scanQueued = false;
  let healthTimer = 0;
  let trustedGestureActive = false;

  function post(payload) {
    try {
      NATIVE_POST?.(JSON.stringify({ ...payload, bridgeToken: BRIDGE_TOKEN }));
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
    } catch (_) {
      return '/invalid/';
    }
  }

  function routeKind(urlLike) {
    let url;
    try { url = new URL(urlLike, location.href); } catch (_) { return 'blocked'; }
    if (url.protocol !== 'https:') return 'blocked';
    const host = url.hostname.toLowerCase();
    const path = normalizedPath(url);
    if (!path) return 'blocked';
    if (AUTH_HOSTS.has(host)) return 'auth';
    if (!YOUTUBE_HOSTS.has(host)) return 'external';
    if (path === '/feed/subscriptions/') return 'subscriptions';
    if (path === '/results/') return 'search';
    if (path === '/watch/' && /^[A-Za-z0-9_-]{6,32}$/.test(url.searchParams.get('v') || '')) return 'watch';
    if (path === '/signin/' || path.startsWith('/o/oauth2/')) return 'auth';
    return 'blocked';
  }

  function currentVideoId() {
    try { return new URL(location.href).searchParams.get('v') || ''; } catch (_) { return ''; }
  }

  function installStyle() {
    const style = document.createElement('style');
    style.id = 'clearfeed-youtube-guard-style';
    style.textContent = GUARD_CSS;
    (document.head || document.documentElement).appendChild(style);
  }

  function concealIfUnsafe() {
    const kind = routeKind(location.href);
    const safe = ['subscriptions', 'search', 'watch', 'auth'].includes(kind);
    document.documentElement.toggleAttribute('data-clearfeed-blocked', !safe);
    if (!safe) post({ type: 'navigation_candidate', path: pathAndQuery(location.href) });
    return safe;
  }

  function hide(element) {
    if (!element || element.dataset?.clearfeedHidden === 'true') return;
    element.dataset.clearfeedHidden = 'true';
    element.setAttribute('aria-hidden', 'true');
    element.setAttribute('tabindex', '-1');
    element.hidden = true;
  }

  function isRecommendationContext(anchor) {
    return Boolean(anchor.closest(
      'ytm-reel-shelf-renderer, ytm-single-column-watch-next-results-renderer, ' +
      'ytm-item-section-renderer[section-identifier="related-items"], ytm-compact-video-renderer'
    ));
  }

  function linkDecision(candidate, anchor) {
    return RULES.youtubeLinkDecision({
      currentKind: routeKind(location.href),
      targetKind: routeKind(candidate.href),
      targetPath: normalizedPath(candidate) || '/invalid/',
      currentVideoId: currentVideoId(),
      targetVideoId: candidate.searchParams.get('v') || '',
      inRecommendation: Boolean(anchor && isRecommendationContext(anchor))
    });
  }

  function sanitizeAnchor(anchor) {
    if (!anchor?.getAttribute) return;
    const href = anchor.getAttribute('href');
    if (!href) return;
    let candidate;
    try { candidate = new URL(href, location.href); } catch (_) { hide(anchor); return; }
    if (!YOUTUBE_HOSTS.has(candidate.hostname.toLowerCase())) return;
    const targetPath = normalizedPath(candidate) || '/invalid/';
    if (targetPath.startsWith('/shorts/')) {
      hide(anchor.closest('ytm-reel-shelf-renderer') ||
        anchor.closest('ytm-shorts-lockup-view-model, ytm-pivot-bar-item-renderer') || anchor);
      hide(anchor);
      return;
    }
    const decision = linkDecision(candidate, anchor);
    if (decision === 'hide_block' || decision === 'block') hide(anchor);
  }

  function sanitize(root) {
    if (!root || root.nodeType !== Node.ELEMENT_NODE) return;
    if (root.matches?.('.pivot-shorts')) {
      hide(root.closest('ytm-pivot-bar-item-renderer') || root);
    }
    if (root.matches?.('a[href]')) sanitizeAnchor(root);
    root.querySelectorAll?.('a[href]').forEach(sanitizeAnchor);
    root.querySelectorAll?.('.pivot-shorts').forEach(element =>
      hide(element.closest('ytm-pivot-bar-item-renderer') || element)
    );
    root.querySelectorAll?.(
      'ytm-reel-shelf-renderer, ytm-shorts-lockup-view-model, ' +
      'ytm-comments-entry-point-header-renderer, ' +
      'ytm-comment-section-renderer, ytm-autoplay-bar-renderer, ' +
      '[aria-label*="Open app" i], [aria-label*="Open in YouTube" i]'
    ).forEach(hide);
    if (routeKind(location.href) === 'watch') {
      root.querySelectorAll?.(
        'ytm-single-column-watch-next-results-renderer ytm-video-with-context-renderer, ' +
        'ytm-single-column-watch-next-results-renderer ytm-reel-shelf-renderer, ' +
        'ytm-item-section-renderer[section-identifier="related-items"], ytm-compact-video-renderer'
      ).forEach(hide);
    }
    root.querySelectorAll?.('video').forEach(video => {
      video.autoplay = false;
      video.removeAttribute('autoplay');
    });
  }

  function reportHealth() {
    clearTimeout(healthTimer);
    healthTimer = window.setTimeout(() => {
      const kind = routeKind(location.href);
      if (document.body) document.body.dataset.clearfeedYoutubeRoute = kind;
      let healthy = Boolean(document.body);
      if (kind === 'subscriptions' || kind === 'search') healthy = healthy && Boolean(document.querySelector('ytm-app'));
      if (kind === 'watch') healthy = healthy && Boolean(document.querySelector('ytm-watch, video'));
      if (!['subscriptions', 'search', 'watch', 'auth'].includes(kind)) healthy = false;
      post({ type: 'guard_health', version: GUARD_VERSION, path: pathAndQuery(location.href), healthy });
    }, 160);
  }

  function queueScan(root) {
    if (scanQueued) return;
    scanQueued = true;
    window.setTimeout(() => {
      scanQueued = false;
      sanitize(root || document.documentElement);
      reportHealth();
    }, 60);
  }

  function auditLocation() {
    if (location.href === lastHref) return;
    const previous = lastHref;
    lastHref = location.href;
    const kind = routeKind(location.href);
    if (kind === 'watch' && routeKind(previous) === 'watch') {
      const oldId = new URL(previous).searchParams.get('v');
      if (currentVideoId() !== oldId) {
        document.documentElement.setAttribute('data-clearfeed-blocked', 'true');
        post({ type: 'blocked_navigation', path: pathAndQuery(location.href) });
        return;
      }
    }
    const safe = ['subscriptions', 'search', 'watch', 'auth'].includes(kind);
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
        if (candidate != null) {
          const target = new URL(candidate, location.href);
          const kind = routeKind(target.href);
          const changesWatch = kind === 'watch' && routeKind(location.href) === 'watch' &&
            target.searchParams.get('v') !== currentVideoId();
          if (!['subscriptions', 'search', 'watch', 'auth'].includes(kind) || changesWatch) {
            post({ type: 'blocked_navigation', path: pathAndQuery(target.href) });
            return;
          }
        }
        const result = original.apply(this, arguments);
        auditLocation();
        return result;
      }
    });
  }

  installStyle();
  concealIfUnsafe();
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
    const decision = linkDecision(candidate, anchor);
    if (decision === 'external') {
      event.preventDefault(); event.stopImmediatePropagation();
      post({ type: 'external_navigation', url: candidate.href, userGesture: event.isTrusted });
      return;
    }
    if (decision === 'intentional_watch') {
      event.preventDefault(); event.stopImmediatePropagation();
      post({ type: 'intentional_video', path: pathAndQuery(candidate.href) });
      return;
    }
    if (decision !== 'allow') {
      event.preventDefault(); event.stopImmediatePropagation();
      post({ type: 'blocked_navigation', path: pathAndQuery(candidate.href) });
    }
  }, true);

  document.addEventListener('ended', event => {
    if (event.target?.tagName !== 'VIDEO') return;
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
  window.addEventListener('popstate', auditLocation, true);
  window.addEventListener('pageshow', () => { auditLocation(); queueScan(document.documentElement); });
  document.addEventListener('DOMContentLoaded', () => queueScan(document.documentElement), { once: true });
  window.setInterval(() => { if (!document.hidden) auditLocation(); }, 600);
  post({ type: 'guard_ready', version: GUARD_VERSION, path: pathAndQuery(location.href) });
})();
