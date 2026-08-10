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

console.log('Guard fixture tests passed: 53');
