'use strict';

// Executes the three platform guards in a real DOM, the way PlatformScriptInjector does
// on a device: guard_rules.js concatenated with the platform template, placeholders
// substituted, run at document start against a trusted origin.
//
// Until this harness existed the guards were only ever *compiled* (tools/check-guard-syntax.js)
// and grepped for substrings (GuardContractTest.kt), so every route classifier, sanitizer
// and click handler had zero execution coverage. That is where the fail-open bugs lived.

const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');
const { JSDOM, VirtualConsole } = require('jsdom');

const ASSET_DIR = path.join(__dirname, '..', 'app', 'src', 'main', 'assets');

// Mirrors SocialPlatform.kt.
const PLATFORMS = {
  instagram: { script: 'instagram_guard.js', css: 'instagram_guard.css', version: 6 },
  youtube: { script: 'youtube_guard.js', css: 'youtube_guard.css', version: 4 },
  facebook: { script: 'facebook_guard.js', css: 'facebook_guard.css', version: 7 },
};

const BRIDGE_TOKEN = 'a'.repeat(48);

const asset = name => fs.readFileSync(path.join(ASSET_DIR, name), 'utf8');

// Each booted guard installs a repeating setInterval and a MutationObserver. Left open,
// those keep running across every later test (and keep the process alive), and the
// Instagram guard's getComputedStyle sweep is slow enough under jsdom to stall the suite.
// Windows are registered here and torn down after each test.
const openWindows = [];

/** Mirrors PlatformScriptInjector.install's placeholder substitution. */
function buildScript(platform) {
  const { script, css, version } = PLATFORMS[platform];
  const template = asset(script)
    .replace('__CLEARFEED_GUARD_VERSION__', String(version))
    .replace('__CLEARFEED_CSS_JSON__', JSON.stringify(asset(css)))
    .replace('__DIRECTONLY_GUARD_VERSION__', String(version))
    .replace('__DIRECTONLY_CSS_JSON__', JSON.stringify(asset(css)))
    .replace('__CLEARFEED_BRIDGE_TOKEN_JSON__', JSON.stringify(BRIDGE_TOKEN));
  return `${asset('guard_rules.js')}\n${template}`;
}

/**
 * Boots a guard at `url` with `body` as the initial document.
 * Returns the window plus the bridge messages the guard posted.
 */
function boot(platform, url, body = '<main></main>') {
  const messages = [];
  const virtualConsole = new VirtualConsole();
  const errors = [];
  virtualConsole.on('jsdomError', error => errors.push(error));

  const dom = new JSDOM(`<!doctype html><html><head></head><body>${body}</body></html>`, {
    url,
    runScripts: 'outside-only',
    pretendToBeVisual: true,
    virtualConsole,
  });

  // The origin-restricted AndroidX WebKit bridge, as the guard sees it.
  dom.window.clearFeedBridge = {
    postMessage(raw) {
      try {
        messages.push(JSON.parse(raw));
      } catch (_) {
        messages.push({ type: 'unparseable', raw });
      }
    },
  };

  // jsdom defines isTrusted as a non-configurable prototype getter, and events it
  // synthesizes are always untrusted. The guards gate every capability mint on
  // event.isTrusted, so the harness needs to be able to simulate a real user tap.
  // Route the getter through an own marker property instead.
  Object.defineProperty(dom.window.Event.prototype, 'isTrusted', {
    configurable: true,
    get() {
      return this.__harnessTrusted === true;
    },
  });

  dom.window.eval(buildScript(platform));
  openWindows.push(dom.window);
  return { window: dom.window, document: dom.window.document, messages, errors, dom };
}

function closeAllWindows() {
  while (openWindows.length > 0) {
    try {
      openWindows.pop().close();
    } catch (_) {
      // A window that already tore itself down is fine.
    }
  }
}

/** Dispatches a browser-trusted click, as a real user tap would produce. */
function trustedClick(window, element) {
  const event = new window.MouseEvent('click', { bubbles: true, cancelable: true });
  event.__harnessTrusted = true;
  element.dispatchEvent(event);
  return event;
}

/** The guards conceal via toggleAttribute, which sets an empty value rather than "true". */
const isConcealed = (document, attribute) => document.documentElement.hasAttribute(attribute);

/** Runs pending debounced work (queueScan 55-70ms, reportHealth 160ms). */
function settle(window, ms = 400) {
  // jsdom timers are real; advance by actually waiting is avoided by draining
  // synchronously where possible. Callers that need debounced output await this.
  return new Promise(resolve => window.setTimeout(resolve, ms));
}

const results = [];
function test(name, fn) {
  results.push({ name, fn });
}

// ---------------------------------------------------------------------------
// Guards boot cleanly and announce themselves
// ---------------------------------------------------------------------------

for (const platform of Object.keys(PLATFORMS)) {
  const safeUrl = {
    instagram: 'https://www.instagram.com/direct/inbox/',
    youtube: 'https://m.youtube.com/feed/subscriptions',
    facebook: 'https://m.facebook.com/?filter=all&sk=h_chr',
  }[platform];

  test(`${platform}: guard boots and posts guard_ready with its build version`, async () => {
    const { window, messages, errors } = boot(platform, safeUrl);
    assert.deepEqual(errors, [], `${platform} guard threw during document-start install`);
    await settle(window);
    const ready = messages.find(m => m.type === 'guard_ready');
    assert.ok(ready, `${platform} never posted guard_ready; got ${JSON.stringify(messages)}`);
    assert.equal(ready.version, PLATFORMS[platform].version);
    assert.equal(ready.bridgeToken, BRIDGE_TOKEN, 'every event must carry the session token');
  });

  test(`${platform}: guard injects its stylesheet at document start`, () => {
    const { document } = boot(platform, safeUrl);
    const style = document.querySelector('style[id*="guard-style"]');
    assert.ok(style, `${platform} did not install its guard stylesheet`);
    assert.ok(style.textContent.length > 0);
  });
}

// ---------------------------------------------------------------------------
// S1: Facebook route classification is case-insensitive
// ---------------------------------------------------------------------------

// routeKind is internal to the guard, so its verdict is observed through the
// document-start conceal attribute, which is set for any non-safe route.
function facebookRouteIsBlocked(pathAndQuery) {
  const { document } = boot('facebook', `https://m.facebook.com${pathAndQuery}`);
  return isConcealed(document, 'data-clearfeed-blocked');
}

test('S1 facebook: capitalized video routes are blocked like their lowercase forms', () => {
  for (const route of [
    '/reels/', '/Reels/', '/REELS/',
    '/watch/', '/Watch/', '/WATCH/',
    '/videos/123/', '/Videos/123/',
    '/stories/x/1/', '/Stories/x/1/',
    '/marketplace/', '/Marketplace/',
    '/gaming/', '/Gaming/',
    '/live/', '/Live/',
  ]) {
    assert.equal(facebookRouteIsBlocked(route), true, `expected ${route} to be concealed`);
  }
});

test('S1 facebook: a real profile is still allowed in any case', () => {
  assert.equal(facebookRouteIsBlocked('/zuck/'), false);
  assert.equal(facebookRouteIsBlocked('/Example.Page/'), false);
});

// ---------------------------------------------------------------------------
// S4: bare discovery directories must not read as profiles
// ---------------------------------------------------------------------------

test('S4 facebook: bare discovery directories are blocked', () => {
  for (const route of [
    '/groups/', '/events/', '/pages/', '/dating/', '/games/',
    '/photos/', '/saved/', '/memories/', '/watch_videos/', '/friends_center/',
    '/groups/feed/', '/groups/discover/',
  ]) {
    assert.equal(facebookRouteIsBlocked(route), true, `expected ${route} to be concealed`);
  }
});

test('S4 facebook: specific groups, events and pages still resolve', () => {
  assert.equal(facebookRouteIsBlocked('/groups/example.group/'), false);
  assert.equal(facebookRouteIsBlocked('/events/123456789/'), false);
  assert.equal(facebookRouteIsBlocked('/pages/Example-Page/123456/'), false);
});

test('facebook: the newest-feed query is the only allowed root', () => {
  assert.equal(facebookRouteIsBlocked('/?filter=all&sk=h_chr'), false);
  assert.equal(facebookRouteIsBlocked('/?sk=nf'), true);
  assert.equal(facebookRouteIsBlocked('/home.php'), true);
});

// ---------------------------------------------------------------------------
// S2: the Instagram DM capability gate requires a real message surface
// ---------------------------------------------------------------------------

const THREAD_URL = 'https://www.instagram.com/direct/t/12345/';

/**
 * Reports whether the guard treats a `/reel/` anchor in `wrapperHtml` as living in a real
 * message surface.
 *
 * `inDirectMessageSurface` is internal to the guard, and jsdom cannot synthesise a
 * browser-trusted event (isTrusted is a non-configurable own property on every event
 * instance), so the capability mint itself is not reachable from here. sanitizeAnchor
 * gives an equivalent, purely DOM-observable signal: on a Direct thread it leaves a
 * shared-content anchor visible exactly when inDirectMessageSurface() is true, and
 * otherwise hides it behind a placeholder. Same predicate, same call, observable result.
 */
async function instagramTreatsAsMessageSurface(wrapperHtml) {
  const { window, document } = boot('instagram', THREAD_URL, `<main>${wrapperHtml}</main>`);
  const anchor = document.querySelector('a');
  assert.ok(anchor, 'fixture must contain an anchor');
  await settle(window, 200);
  return anchor.getAttribute('aria-hidden') !== 'true';
}

test('S2 instagram: a Reel inside a message row is treated as shared DM content', async () => {
  for (const wrapper of [
    '<div role="row"><a href="/reel/ABCDEF/">shared reel</a></div>',
    '<div role="listitem"><a href="/reel/ABCDEF/">shared reel</a></div>',
    '<article><a href="/p/ABCDEF/">shared post</a></article>',
    '<div aria-label="Message from someone"><a href="/reel/ABCDEF/">shared reel</a></div>',
  ]) {
    assert.equal(
      await instagramTreatsAsMessageSurface(wrapper),
      true,
      `a genuine DM bubble must keep its shared item usable: ${wrapper}`,
    );
  }
});

test('S2 instagram: a Reel elsewhere in <main> is NOT a message surface', async () => {
  // The regression. `inDirectMessageSurface` included `main` in its own allowlist, which
  // made the whole check unconditionally true because the line above had already proved
  // the anchor was inside `main`. Any recommendation strip or shared-profile card in the
  // thread body then counted as a genuine DM tap and could mint the sealed capability.
  for (const wrapper of [
    '<section aria-label="Suggested for you"><a href="/reel/ABCDEF/">suggested</a></section>',
    '<div class="rail"><a href="/reel/ABCDEF/">rail item</a></div>',
    '<aside><a href="/reel/ABCDEF/">aside</a></aside>',
    '<div><a href="/reel/ABCDEF/">bare div</a></div>',
  ]) {
    assert.equal(
      await instagramTreatsAsMessageSurface(wrapper),
      false,
      `must not count as a message surface: ${wrapper}`,
    );
  }
});

test('S2 instagram: a scripted click never mints a capability', async () => {
  // jsdom events are always untrusted, so this pins the isTrusted gate from the negative
  // side: a programmatic click must produce no capability regardless of placement.
  const { window, document, messages } = boot(
    'instagram',
    THREAD_URL,
    '<main><div role="row"><a href="/reel/ABCDEF/">shared reel</a></div></main>',
  );
  document.querySelector('a').click();
  await settle(window, 50);
  assert.equal(
    messages.some(m => m.type === 'intentional_instagram_shared_content'),
    false,
  );
});

// ---------------------------------------------------------------------------
// Instagram route classification
// ---------------------------------------------------------------------------

test('instagram: Direct routes are revealed and everything else is concealed', () => {
  const revealed = url => !isConcealed(boot('instagram', url).document, 'data-directonly-blocked');

  assert.equal(revealed('https://www.instagram.com/direct/inbox/'), true);
  assert.equal(revealed('https://www.instagram.com/direct/t/12345/'), true);
  assert.equal(revealed('https://www.instagram.com/accounts/login/'), true);
  for (const url of [
    'https://www.instagram.com/',
    'https://www.instagram.com/explore/',
    'https://www.instagram.com/reel/ABCDEF/',
    'https://www.instagram.com/p/ABCDEF/',
    'https://www.instagram.com/someprofile/',
    'https://www.instagram.com/stories/someone/1/',
  ]) {
    assert.equal(revealed(url), false, `${url} must be concealed`);
  }
});

// ---------------------------------------------------------------------------
// R2: DOM sanitation covers in-place href swaps and batched mutations
// ---------------------------------------------------------------------------

test('R2: an anchor repointed at a blocked route is re-sanitized', async () => {
  const { window, document } = boot(
    'facebook',
    'https://m.facebook.com/?filter=all&sk=h_chr',
    '<main><a id="recycled" href="/friends/">friends</a></main>',
  );
  const anchor = document.querySelector('#recycled');
  await settle(window, 150);
  assert.notEqual(anchor.getAttribute('aria-hidden'), 'true', 'a safe anchor stays visible');

  // React and Relay recycle nodes and swap href in place. childList-only observation
  // never re-evaluated these.
  anchor.setAttribute('href', '/reel/123456/');
  await settle(window, 250);
  assert.equal(
    anchor.getAttribute('aria-hidden'),
    'true',
    'a recycled anchor pointed at a blocked route must be hidden',
  );
});

test('R2: every subtree in one mutation batch is sanitized', async () => {
  const { window, document } = boot(
    'facebook',
    'https://m.facebook.com/?filter=all&sk=h_chr',
    '<main></main>',
  );
  const main = document.querySelector('main');
  // Several subtrees added in one microtask. queueScan used to keep the first root and
  // discard the rest, leaving later subtrees permanently unscanned.
  for (let i = 0; i < 6; i += 1) {
    const block = document.createElement('div');
    block.innerHTML = `<a class="bad" href="/reel/${i}/">reel ${i}</a>`;
    main.appendChild(block);
  }
  await settle(window, 300);
  const missed = Array.from(document.querySelectorAll('a.bad'))
    .filter(a => a.getAttribute('aria-hidden') !== 'true')
    .map(a => a.getAttribute('href'));
  assert.deepEqual(missed, [], `these anchors were never sanitized: ${missed.join(', ')}`);
});

// ---------------------------------------------------------------------------
// Wire-protocol and placeholder contracts
// ---------------------------------------------------------------------------

test('every guard placeholder is substituted by PlatformScriptInjector', () => {
  const injector = fs.readFileSync(
    path.join(
      __dirname, '..', 'app', 'src', 'main', 'java', 'dev', 'directonly', 'app',
      'web', 'PlatformScriptInjector.kt',
    ),
    'utf8',
  );
  for (const [platform, { script }] of Object.entries(PLATFORMS)) {
    const tokens = asset(script).match(/__[A-Z][A-Z0-9_]*__/g) || [];
    for (const token of new Set(tokens)) {
      assert.ok(
        injector.includes(`"${token}"`),
        `${platform}: ${token} is never replaced by PlatformScriptInjector, so it would ` +
          'survive as a bare identifier and throw ReferenceError at runtime',
      );
    }
  }
});

test('no unsubstituted placeholder survives into a built script', () => {
  for (const platform of Object.keys(PLATFORMS)) {
    const built = buildScript(platform);
    const leftover = built.match(/__[A-Z][A-Z0-9_]*__/g) || [];
    assert.deepEqual(leftover, [], `${platform} kept placeholders: ${leftover.join(', ')}`);
  }
});

test('guard event types exactly match the Kotlin bridge parser', () => {
  const injector = fs.readFileSync(
    path.join(
      __dirname, '..', 'app', 'src', 'main', 'java', 'dev', 'directonly', 'app',
      'web', 'PlatformScriptInjector.kt',
    ),
    'utf8',
  );
  const kotlinArms = new Set(
    Array.from(injector.matchAll(/^\s*"([a-z_]+)" ->/gm), m => m[1]),
  );
  const jsTypes = new Set();
  for (const { script } of Object.values(PLATFORMS)) {
    for (const m of asset(script).matchAll(/type:\s*'([a-z_]+)'/g)) jsTypes.add(m[1]);
  }
  const unhandled = [...jsTypes].filter(t => !kotlinArms.has(t));
  const unreachable = [...kotlinArms].filter(t => !jsTypes.has(t));
  assert.deepEqual(unhandled, [], `guards post types Kotlin ignores: ${unhandled.join(', ')}`);
  assert.deepEqual(unreachable, [], `Kotlin parses types no guard posts: ${unreachable.join(', ')}`);
});

// ---------------------------------------------------------------------------
// Runner
// ---------------------------------------------------------------------------

(async () => {
  let passed = 0;
  const failures = [];
  for (const { name, fn } of results) {
    try {
      await fn();
      passed += 1;
    } catch (error) {
      failures.push({ name, error });
    } finally {
      closeAllWindows();
    }
  }
  for (const { name, error } of failures) {
    console.error(`FAIL ${name}\n      ${error.message.split('\n')[0]}`);
  }
  if (failures.length > 0) {
    console.error(`\nGuard DOM tests: ${passed} passed, ${failures.length} failed`);
    process.exit(1);
  }
  console.log(`Guard DOM tests passed: ${passed}`);
})();
