(function installClearFeedGuardRules(root, factory) {
  'use strict';
  const rules = Object.freeze(factory());
  if (typeof module === 'object' && module && module.exports) module.exports = rules;
  if (root && !root.__clearFeedGuardRules) {
    Object.defineProperty(root, '__clearFeedGuardRules', {
      value: rules,
      configurable: false,
      enumerable: false,
      writable: false
    });
  }
})(typeof globalThis === 'object' ? globalThis : this, function createRules() {
  'use strict';

  const FACEBOOK_FEED_POST_LIMIT = 8;
  const FACEBOOK_UNSAFE_PREFIXES = [
    '/reel/', '/reels/', '/watch/', '/watch.php', '/video/', '/video.php',
    '/videos/', '/live/', '/stories/', '/story.php'
  ];
  const MAX_TEXT_SAMPLE = 8000;
  const FACEBOOK_RECOMMENDATION_TEXT =
    /suggested for you|suggested posts?|reels for you|videos for you|people you may know|pages you may like|groups you should join|recommended (?:groups|pages)|discover (?:more|something new)|because you watched/i;

  function facebookFeedRouteDecision(rawPath, rawSearch) {
    const path = String(rawPath || '/');
    if (path !== '/') return 'not_feed';
    const search = String(rawSearch || '').replace(/^\?/, '');
    // A bare root is where Facebook drops a freshly signed-in session, and where mobile
    // canonicalizes the verified feed URL back to. Treating it as ranked Home meant the
    // guard concealed the page immediately after every login. A root carrying any query is
    // still asking for something specific and must name the complete verified pair.
    // Mirrors FacebookNavigationPolicy.evaluate.
    if (search === '') return 'newest_feed';
    const params = new URLSearchParams(search);
    const filters = new Set(['all', 'favorites', 'friends', 'groups', 'pages']);
    return params.get('sk') === 'h_chr' && filters.has(params.get('filter')) ?
      'newest_feed' : 'ranked_home_block';
  }

  function instagramSharedContentIdentity(rawPath) {
    const path = String(rawPath || '');
    let match = path.match(/^\/(?:reel|reels)\/([A-Za-z0-9_-]+)\/$/);
    if (match) return Object.freeze({
      kind: 'reel', contentId: match[1], canonicalPath: `/reel/${match[1]}/`
    });
    match = path.match(/^\/p\/([A-Za-z0-9_-]+)\/$/);
    if (match) return Object.freeze({
      kind: 'post', contentId: match[1], canonicalPath: `/p/${match[1]}/`
    });
    return null;
  }

  function instagramDmTapDecision(snapshot) {
    const identity = instagramSharedContentIdentity(snapshot.targetPath);
    return snapshot.currentRoute === 'direct-thread' && snapshot.eventIsTrusted === true &&
      snapshot.insideMessageSurface === true && identity ? 'authorize_exact_item' : 'block';
  }

  function instagramSealedItemDecision(snapshot) {
    const identity = instagramSharedContentIdentity(snapshot.targetPath);
    if (!identity || !snapshot.approvedIdentity) return 'block';
    const approved = snapshot.approvedIdentity;
    return identity.kind === approved.kind && identity.contentId === approved.contentId &&
      identity.canonicalPath === approved.canonicalPath &&
      snapshot.targetToken === snapshot.approvedToken ? 'allow_same_item' : 'block';
  }

  function instagramSealedLinkDecision(snapshot) {
    return snapshot.isExactApprovedItem === true ? 'allow_same_item' : 'hide_block';
  }

  function instagramSealedGestureDecision(snapshot) {
    if (snapshot.isMediaControl === true) return 'allow_control';
    const deltaX = Math.abs(Number(snapshot.deltaX) || 0);
    const deltaY = Math.abs(Number(snapshot.deltaY) || 0);
    return deltaY > deltaX && deltaY > 8 ? 'block_vertical' : 'allow_horizontal';
  }

  function instagramSealedMediaEndDecision(snapshot) {
    return snapshot.isApprovedMedia === true ? 'stop_no_advance' : 'hide_block';
  }

  function youtubeLinkDecision(snapshot) {
    const targetPath = String(snapshot.targetPath || '/');
    const targetKind = String(snapshot.targetKind || 'blocked');
    const currentKind = String(snapshot.currentKind || 'blocked');
    // External hosts are classified first. The path tests below are YouTube-specific,
    // and `targetPath === '/'` matched the root of every external host too, so an
    // ordinary sign-in entry point such as accounts.google.com/ was reported as blocked
    // instead of being routed.
    if (targetKind === 'external') return 'external';
    if (targetPath === '/' || targetPath.startsWith('/shorts/') ||
        targetPath.startsWith('/feed/trending/')) return 'hide_block';
    if (targetKind === 'watch') {
      if ((currentKind === 'subscriptions' || currentKind === 'search') &&
          !snapshot.inRecommendation) return 'intentional_watch';
      if (currentKind === 'watch' && snapshot.targetVideoId &&
          snapshot.targetVideoId === snapshot.currentVideoId) return 'allow';
      return 'hide_block';
    }
    if (['subscriptions', 'search', 'auth'].includes(targetKind)) return 'allow';
    return 'block';
  }

  function isUnsafeFacebookHref(rawHref) {
    let path = String(rawHref || '');
    try { path = new URL(path, 'https://www.facebook.com/').pathname; } catch (_) {}
    path = path.toLowerCase();
    return FACEBOOK_UNSAFE_PREFIXES.some(prefix => path.startsWith(prefix));
  }

  function facebookArticleDecision(snapshot) {
    if (!snapshot.allowMessageAttachments && snapshot.hasVideo) return 'hide_unsafe';
    if ((snapshot.hrefs || []).some(isUnsafeFacebookHref)) return 'hide_unsafe';
    if ((snapshot.labels || []).some(label => /reel|play video|story/i.test(String(label)))) {
      return 'hide_unsafe';
    }
    // Scan the whole sampled text, not just its first 500 characters. Recommendation
    // markers ("Suggested for you", "Because you watched") frequently sit *below* a long
    // caption, so a 500-character window let those cards through. The cap is now generous
    // enough to cover a real card while still bounding regex work on pathological input.
    if (FACEBOOK_RECOMMENDATION_TEXT.test(String(snapshot.text || '').slice(0, MAX_TEXT_SAMPLE))) {
      return 'hide_unsafe';
    }
    return 'keep';
  }

  function facebookFeedDecision(snapshot) {
    if (snapshot.articleDecision === 'hide_unsafe') {
      return Object.freeze({ action: 'hide_unsafe', nextCount: snapshot.retainedCount, endReached: false });
    }
    if (!snapshot.isStream) {
      return Object.freeze({ action: 'keep', nextCount: snapshot.retainedCount, endReached: false });
    }
    if (snapshot.retainedCount >= FACEBOOK_FEED_POST_LIMIT) {
      return Object.freeze({ action: 'hide_limit', nextCount: snapshot.retainedCount, endReached: true });
    }
    const nextCount = snapshot.retainedCount + 1;
    return Object.freeze({ action: 'keep', nextCount, endReached: nextCount === FACEBOOK_FEED_POST_LIMIT });
  }

  return {
    FACEBOOK_FEED_POST_LIMIT,
    instagramSharedContentIdentity,
    instagramDmTapDecision,
    instagramSealedItemDecision,
    instagramSealedLinkDecision,
    instagramSealedGestureDecision,
    instagramSealedMediaEndDecision,
    facebookFeedRouteDecision,
    youtubeLinkDecision,
    isUnsafeFacebookHref,
    facebookArticleDecision,
    facebookFeedDecision
  };
});
