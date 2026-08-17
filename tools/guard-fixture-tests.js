'use strict';

const assert = require('node:assert/strict');
const rules = require('../app/src/main/assets/guard_rules.js');

const instagramReel = rules.instagramSharedContentIdentity('/reel/REEL_A/');
const instagramPost = rules.instagramSharedContentIdentity('/p/POST_A/');
assert.deepEqual(instagramReel, {
  kind: 'reel', contentId: 'REEL_A', canonicalPath: '/reel/REEL_A/'
}, 'singular Reel identity');
assert.deepEqual(rules.instagramSharedContentIdentity('/reels/REEL_A/'), instagramReel,
  'plural Reel route is the same stable item identity');
assert.equal(instagramPost.kind, 'post', 'normal post identity');
assert.equal(rules.instagramSharedContentIdentity('/stories/example/123/'), null, 'Stories remain blocked');

const dmTap = {
  currentRoute: 'direct-thread', eventIsTrusted: true,
  insideMessageSurface: true, targetPath: '/reel/REEL_A/'
};
assert.equal(rules.instagramDmTapDecision(dmTap), 'authorize_exact_item', 'trusted DM Reel tap');
assert.equal(rules.instagramDmTapDecision({ ...dmTap, eventIsTrusted: false }), 'block',
  'scripted click');
assert.equal(rules.instagramDmTapDecision({ ...dmTap, currentRoute: 'profile' }), 'block',
  'profile-originated click');
assert.equal(rules.instagramDmTapDecision({ ...dmTap, currentRoute: 'explore' }), 'block',
  'Explore-originated click');
assert.equal(rules.instagramDmTapDecision({ ...dmTap, currentRoute: 'shared' }), 'block',
  'recommendation from sealed viewer');

const sealedSnapshot = {
  targetPath: '/reel/REEL_A/', targetToken: 'nonce-a',
  approvedIdentity: instagramReel, approvedToken: 'nonce-a'
};
assert.equal(rules.instagramSealedItemDecision(sealedSnapshot), 'allow_same_item',
  'exact sealed Reel');
assert.equal(rules.instagramSealedItemDecision({ ...sealedSnapshot, targetPath: '/reel/REEL_B/' }),
  'block', 'next Reel');
assert.equal(rules.instagramSealedItemDecision({ ...sealedSnapshot, targetToken: 'nonce-b' }),
  'block', 'wrong capability');

for (const [name, snapshot] of [
  ['creator profile', { isExactApprovedItem: false, targetKind: 'profile' }],
  ['audio source', { isExactApprovedItem: false, targetKind: 'audio' }],
  ['hashtag', { isExactApprovedItem: false, targetKind: 'hashtag' }],
  ['comment profile', { isExactApprovedItem: false, targetKind: 'profile' }],
  ['related recommendation', { isExactApprovedItem: false, targetKind: 'post' }],
  ['Direct shortcut', { isExactApprovedItem: false, targetKind: 'direct' }],
  ['external link', { isExactApprovedItem: false, targetKind: 'external' }]
]) {
  assert.equal(rules.instagramSealedLinkDecision(snapshot), 'hide_block', name);
}
assert.equal(rules.instagramSealedLinkDecision({ isExactApprovedItem: true }), 'allow_same_item',
  'same-item replay link');

assert.equal(rules.instagramSealedGestureDecision({ deltaX: 2, deltaY: 60 }), 'block_vertical',
  'swipe up');
assert.equal(rules.instagramSealedGestureDecision({ deltaX: 1, deltaY: -60 }), 'block_vertical',
  'swipe down');
assert.equal(rules.instagramSealedGestureDecision({ deltaX: 60, deltaY: 2 }), 'allow_horizontal',
  'same-post carousel swipe');
assert.equal(rules.instagramSealedGestureDecision({
  deltaX: 0, deltaY: 40, isMediaControl: true
}), 'allow_control', 'video seek control');
assert.equal(rules.instagramSealedMediaEndDecision({ isApprovedMedia: true }), 'stop_no_advance',
  'video completion never advances');
assert.equal(rules.instagramSealedMediaEndDecision({ isApprovedMedia: false }), 'hide_block',
  'preloaded neighbor completion');

const youtubeFixtures = [
  {
    name: 'Shorts result',
    snapshot: { currentKind: 'search', targetKind: 'blocked', targetPath: '/shorts/abc123/' },
    expected: 'hide_block'
  },
  {
    name: 'related normal video',
    snapshot: {
      currentKind: 'watch', targetKind: 'watch', targetPath: '/watch/',
      currentVideoId: 'current123', targetVideoId: 'related456', inRecommendation: true
    },
    expected: 'hide_block'
  },
  {
    name: 'explicit Search video result',
    snapshot: {
      currentKind: 'search', targetKind: 'watch', targetPath: '/watch/',
      targetVideoId: 'chosen123', inRecommendation: false
    },
    expected: 'intentional_watch'
  },
  {
    name: 'Subscriptions card',
    snapshot: {
      currentKind: 'subscriptions', targetKind: 'watch', targetPath: '/watch/',
      targetVideoId: 'chosen456', inRecommendation: false
    },
    expected: 'intentional_watch'
  }
];

for (const fixture of youtubeFixtures) {
  assert.equal(rules.youtubeLinkDecision(fixture.snapshot), fixture.expected, fixture.name);
}

const safeTextPost = {
  allowMessageAttachments: false,
  hasVideo: false,
  hrefs: ['/example/posts/123/'],
  labels: [],
  text: 'A normal text update'
};
assert.equal(rules.facebookFeedRouteDecision('/', '?filter=all&sk=h_chr'), 'newest_feed',
  'verified Facebook newest Feeds route');
assert.equal(rules.facebookFeedRouteDecision('/', '?sk=nf'), 'ranked_home_block',
  'ranked Facebook Home');
assert.equal(rules.facebookFeedRouteDecision('/messages/', ''), 'not_feed',
  'Facebook utility route');
const safeImagePost = {
  ...safeTextPost,
  hrefs: ['/photo.php?fbid=123'],
  labels: ['Photo'],
  text: 'A normal photo update'
};
const facebookFixtures = [
  { name: 'text post', snapshot: safeTextPost, expected: 'keep' },
  { name: 'image post', snapshot: safeImagePost, expected: 'keep' },
  { name: 'video post', snapshot: { ...safeTextPost, hasVideo: true }, expected: 'hide_unsafe' },
  { name: 'Reel card', snapshot: { ...safeTextPost, hrefs: ['/reel/123/'] }, expected: 'hide_unsafe' },
  { name: 'Story card', snapshot: { ...safeTextPost, labels: ['View Story'] }, expected: 'hide_unsafe' },
  { name: 'recommendation', snapshot: { ...safeTextPost, text: 'Suggested for you' }, expected: 'hide_unsafe' },
  { name: 'Page discovery filler', snapshot: { ...safeTextPost, text: 'Pages you may like' }, expected: 'hide_unsafe' },
  { name: 'Group discovery filler', snapshot: { ...safeTextPost, text: 'Groups you should join' }, expected: 'hide_unsafe' }
];

for (const fixture of facebookFixtures) {
  assert.equal(rules.facebookArticleDecision(fixture.snapshot), fixture.expected, fixture.name);
}

let retainedCount = 0;
for (let index = 1; index <= 8; index += 1) {
  const decision = rules.facebookFeedDecision({
    articleDecision: 'keep', isStream: true, retainedCount
  });
  assert.equal(decision.action, 'keep', `safe post ${index}`);
  retainedCount = decision.nextCount;
}
const ninth = rules.facebookFeedDecision({
  articleDecision: 'keep', isStream: true, retainedCount
});
assert.equal(ninth.action, 'hide_limit', 'ninth Feed item');
assert.equal(ninth.nextCount, 8, 'Feed count must remain fixed at eight');
assert.equal(ninth.endReached, true, 'ninth item keeps end card active');

const privateAttachment = rules.facebookArticleDecision({
  ...safeTextPost,
  allowMessageAttachments: true,
  hasVideo: true
});
assert.equal(privateAttachment, 'keep', 'private Messenger attachment');

// ---------------------------------------------------------------------------
// Regression fixtures for defects the original suite could not see.
// ---------------------------------------------------------------------------

// R9: the '/' path test used to run before the external check, so the root of any
// external host — including a real Google sign-in entry point — was reported as blocked
// instead of being routed to the external handler.
assert.equal(
  rules.youtubeLinkDecision({ currentKind: 'watch', targetKind: 'external', targetPath: '/' }),
  'external',
  'external host root is routed, not blocked'
);
assert.equal(
  rules.youtubeLinkDecision({
    currentKind: 'subscriptions', targetKind: 'external', targetPath: '/signin/'
  }),
  'external',
  'external sign-in path is routed'
);

// youtubeLinkDecision return sites the original fixtures never reached.
assert.equal(
  rules.youtubeLinkDecision({
    currentKind: 'watch', targetKind: 'watch', targetPath: '/watch/',
    currentVideoId: 'same123', targetVideoId: 'same123'
  }),
  'allow',
  'replaying the same watch page is allowed'
);
assert.equal(
  rules.youtubeLinkDecision({
    currentKind: 'subscriptions', targetKind: 'watch', targetPath: '/watch/',
    targetVideoId: 'shelf123', inRecommendation: true
  }),
  'hide_block',
  'a Shorts/recommendation shelf inside Subscriptions cannot mint a watch'
);
assert.equal(
  rules.youtubeLinkDecision({
    currentKind: 'watch', targetKind: 'watch', targetPath: '/watch/',
    currentVideoId: 'a', targetVideoId: 'b', inRecommendation: false
  }),
  'hide_block',
  'a description link to another video cannot mint a next-video token'
);
assert.equal(
  rules.youtubeLinkDecision({ currentKind: 'watch', targetKind: 'subscriptions', targetPath: '/feed/subscriptions/' }),
  'allow',
  'returning to Subscriptions is allowed'
);
assert.equal(
  rules.youtubeLinkDecision({ currentKind: 'watch', targetKind: 'auth', targetPath: '/signin/' }),
  'allow',
  'authentication route is allowed'
);
assert.equal(
  rules.youtubeLinkDecision({ currentKind: 'watch', targetKind: 'channel', targetPath: '/channel/UC123/' }),
  'block',
  'channel destinations fall through to block'
);
assert.equal(
  rules.youtubeLinkDecision({ currentKind: 'search', targetKind: 'blocked', targetPath: '/feed/trending/' }),
  'hide_block',
  'trending shelf is hidden'
);

// isUnsafeFacebookHref: every blocked prefix, in relative, absolute and mixed-case form.
// The original suite reached only '/reel/', through a single article fixture.
for (const prefix of [
  '/reel/', '/reels/', '/watch/', '/watch.php', '/video/', '/video.php',
  '/videos/', '/live/', '/stories/', '/story.php'
]) {
  for (const href of [
    `${prefix}123`,
    `https://www.facebook.com${prefix}123`,
    `${prefix.toUpperCase()}123`
  ]) {
    assert.equal(rules.isUnsafeFacebookHref(href), true, `unsafe href: ${href}`);
  }
}
for (const href of [
  '/example.page/', '/photo.php?fbid=1', '/groups/123/posts/456/',
  '/reelsomething/', '/videographer/', ''
]) {
  assert.equal(rules.isUnsafeFacebookHref(href), false, `safe href: ${href}`);
}
assert.equal(rules.isUnsafeFacebookHref(null), false, 'null href is inert');
assert.equal(rules.isUnsafeFacebookHref(undefined), false, 'undefined href is inert');

// facebookFeedRouteDecision across every accepted filter, plus the near-misses.
for (const filter of ['all', 'favorites', 'friends', 'groups', 'pages']) {
  assert.equal(
    rules.facebookFeedRouteDecision('/', `?filter=${filter}&sk=h_chr`),
    'newest_feed',
    `newest feed filter ${filter}`
  );
}
assert.equal(rules.facebookFeedRouteDecision('/', '?sk=h_chr'), 'ranked_home_block',
  'sk without a filter is not the verified feed');
assert.equal(rules.facebookFeedRouteDecision('/', '?filter=all'), 'ranked_home_block',
  'filter without sk is not the verified feed');
assert.equal(rules.facebookFeedRouteDecision('/', '?filter=unknown&sk=h_chr'), 'ranked_home_block',
  'unknown filter value');
assert.equal(rules.facebookFeedRouteDecision('/', ''), 'ranked_home_block',
  'bare root is ranked Home');
assert.equal(rules.facebookFeedRouteDecision(undefined, undefined), 'ranked_home_block',
  'missing arguments fail closed');

// facebookArticleDecision: the 500-character text window. A recommendation marker sitting
// below a long caption used to survive, because only the first 500 characters are scanned.
assert.equal(
  rules.facebookArticleDecision({ ...safeTextPost, text: `${'x'.repeat(600)} Suggested for you` }),
  'hide_unsafe',
  'a recommendation marker below a long caption is still caught'
);
for (const marker of [
  'Suggested posts', 'Reels for you', 'Videos for you', 'People you may know',
  'Recommended groups', 'Recommended pages', 'Discover more', 'Because you watched'
]) {
  assert.equal(
    rules.facebookArticleDecision({ ...safeTextPost, text: marker }),
    'hide_unsafe',
    `recommendation marker: ${marker}`
  );
}
for (const label of ['Reel', 'Play video', 'View Story']) {
  assert.equal(
    rules.facebookArticleDecision({ ...safeTextPost, labels: [label] }),
    'hide_unsafe',
    `unsafe label: ${label}`
  );
}

// facebookFeedDecision on a non-stream route must not consume the feed budget.
const permalink = rules.facebookFeedDecision({
  articleDecision: 'keep', isStream: false, retainedCount: 0
});
assert.equal(permalink.action, 'keep', 'permalink post is kept');
assert.equal(permalink.nextCount, 0, 'a non-stream route never consumes feed budget');
assert.equal(permalink.endReached, false, 'a non-stream route never reaches the end card');
assert.equal(rules.FACEBOOK_FEED_POST_LIMIT, 8, 'the Feed limit is fixed at eight');

// instagramSharedContentIdentity negatives the original suite never probed.
for (const path of [
  '/reel/ABC', '/reel/', '/REEL/abc/', '/reel/ABC/liked_by/',
  '/reels/audio/123/', '/tv/XYZ/', '/explore/', '/', '//reel/ABC/'
]) {
  assert.equal(rules.instagramSharedContentIdentity(path), null, `not a shared item: ${path}`);
}
assert.deepEqual(
  rules.instagramSharedContentIdentity('/p/POST_A/'),
  { kind: 'post', contentId: 'POST_A', canonicalPath: '/p/POST_A/' },
  'post identity is fully pinned, not just its kind'
);

// instagramDmTapDecision: the surface flag is the gate the guard bug bypassed.
assert.equal(rules.instagramDmTapDecision({ ...dmTap, insideMessageSurface: false }), 'block',
  'a tap outside a message surface is never authorized');
assert.equal(rules.instagramDmTapDecision({ ...dmTap, insideMessageSurface: 'true' }), 'block',
  'a truthy non-boolean surface flag is not accepted');
assert.equal(rules.instagramDmTapDecision({ ...dmTap, eventIsTrusted: 'true' }), 'block',
  'a truthy non-boolean trust flag is not accepted');
assert.equal(rules.instagramDmTapDecision({ ...dmTap, targetPath: '/stories/x/1/' }), 'block',
  'a shared Story has no supported identity');
assert.equal(rules.instagramDmTapDecision({ ...dmTap, targetPath: undefined }), 'block',
  'a missing target path fails closed');

// instagramSealedItemDecision: kind confusion and missing approvals.
assert.equal(
  rules.instagramSealedItemDecision({ ...sealedSnapshot, approvedIdentity: null }),
  'block',
  'no approval means no sealed item'
);
assert.equal(
  rules.instagramSealedItemDecision({ ...sealedSnapshot, targetPath: '/p/REEL_A/' }),
  'block',
  'a post sharing a Reel content id must not match'
);
assert.equal(
  rules.instagramSealedItemDecision({
    ...sealedSnapshot,
    approvedIdentity: { ...instagramReel, canonicalPath: '/reel/OTHER/' }
  }),
  'block',
  'a tampered canonical path must not match'
);

// instagramSealedGestureDecision boundaries.
assert.equal(rules.instagramSealedGestureDecision({ deltaX: 0, deltaY: 8 }), 'allow_horizontal',
  'a gesture at the vertical threshold is not a scroll');
assert.equal(rules.instagramSealedGestureDecision({ deltaX: 0, deltaY: 9 }), 'block_vertical',
  'one pixel past the threshold is a vertical scroll');
assert.equal(rules.instagramSealedGestureDecision({ deltaX: 0, deltaY: 0 }), 'allow_horizontal',
  'a tap is not a scroll');
assert.equal(rules.instagramSealedGestureDecision({}), 'allow_horizontal',
  'absent deltas fail to a harmless verdict');

// instagramSealedLinkDecision reads exactly one field; pin the strict comparison.
for (const value of ['true', 1, undefined, null, {}]) {
  assert.equal(
    rules.instagramSealedLinkDecision({ isExactApprovedItem: value }),
    'hide_block',
    `only a real boolean true allows a sealed link (got ${JSON.stringify(value)})`
  );
}

// The count used to be a hand-maintained literal that silently drifted whenever a fixture
// was added. Derive it instead.
const assertionCount = (() => {
  const source = require('node:fs').readFileSync(__filename, 'utf8');
  return (source.match(/assert\.(?:equal|deepEqual)\(/g) || []).length;
})();
console.log(`Guard fixture assertions executed (static call sites: ${assertionCount}); all passed`);
