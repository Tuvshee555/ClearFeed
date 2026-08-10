# Testing

## Automated verification

Run the complete local gate:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-17.0.20.8-hotspot'
$env:ANDROID_SDK_ROOT = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleRelease --no-daemon
node tools/guard-fixture-tests.js
node tools/check-guard-syntax.js
```

The JVM suite covers:

- Instagram Direct and narrow authentication routes; exact DM-originated Reel/post identity and nonce binding; manual/profile/Explore/recommendation origin denial; one-hop route sealing; session expiry/Back/process recreation; every known content escape, unknown route, redirect and deceptive/encoded URL;
- YouTube Subscriptions/Search, exact deliberate-video and cross-platform normal-video tokens, same-platform next-video denial, Home/Shorts/trending/playlist/channel rejection, Google authentication boundaries and managed-social escapes;
- Facebook newest Feeds query/subfilters, ranked-Home denial, Messages/Search/Notifications/Friends/Pages/Groups/Events/post routes, every known Reel/Story/video/live/Marketplace/Gaming path, unknown routes, auth boundaries and deceptive hosts;
- centralized protected-domain boundaries, Instagram/Facebook/YouTube outbound-wrapper unwrapping, every directional platform handoff, Shorts/Facebook-video/Instagram-non-DM denial, crafted app/`intent:` schemes, genuine-tap requirements and volatile repeated-Back return order;
- URL normalization, user info, explicit ports, malformed paths and unsafe encoding;
- guard-source contracts for mutation enforcement, authenticated restricted-bridge use, absence of cookie/JavaScript-interface access, Instagram sealed-viewer controls, YouTube removal markers and Facebook's immutable eight-post marker/end card.

The Node fixture suite also exercises Instagram trusted versus scripted DM taps, singular/plural canonical identity, different-item/nonce rejection, creator/audio/hashtag/comment/recommendation blocking, up/down swipe blocking, horizontal same-post carousel allowance, media-control allowance, preload rejection and stop-without-advance on video completion.

## Live inspection record

On 2026-08-08 and 2026-08-09, current mobile/responsive structure was inspected in isolated and signed-in browser contexts. No credentials, cookies, storage, screenshots, or message data were retained.

- Instagram: Direct inbox/request routes and semantic thread-list/navigation labels.
- YouTube mobile: Subscriptions, Search and Watch structures; Shorts present in Subscriptions/Search; related/Shorts/comments modules present on Watch.
- Facebook responsive web: Menu's current newest “Feeds” entry, `/?filter=all&sk=h_chr`, current followed-source subfilters, semantic Feed structure, Search/Notifications, Facebook Messages redirect shape, and Messenger login/thread route shape.

See the three compatibility documents for exact non-private observations.

## Required phone test: install and migration

No ADB device or emulator was connected during implementation, so every item below remains **needs device** until performed.

1. On a current Android device, install the original DirectOnly debug APK signed by the same local debug key.
2. Sign into a non-private Instagram test account and confirm Direct inbox.
3. Install ClearFeed with `adb install -r <absolute-app-debug.apk>`; do not uninstall first.
4. Confirm Android treats it as an update, the launcher label becomes ClearFeed, and the native three-service picker opens.
5. Confirm the Instagram WebView session persists. Repeat session-persistence checks for YouTube and Facebook after signing in.
6. If Android reports `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, compare signing certificates; do not claim migration compatibility across different keys.

## Instagram regression matrix

Use a non-private test account and try every route repeatedly, including Back and edge-swipe gestures.

| Scenario | Expected result |
|---|---|
| Login, 2FA, checkpoint, recovery | Provider flow works; no unrelated account pages |
| Inbox, requests, new conversation, thread | Works and remains inside Direct |
| Text/image/video/voice attachment | Works where Instagram Web supports it; system picker/capture requested only in thread |
| Logo, Home, Search/Explore, Reels, notifications, create, settings, profile/avatar/username | Hidden or returns immediately to Direct inbox with no content flash |
| DM-shared supported Reel or normal post | A genuine tap opens only that stable item; caption may remain; a same-post horizontal carousel works; Back returns to the exact thread |
| Same Reel/post by manual URL, profile, Explore, recommendation, redirect or scripted click | Blocked; destination identity alone never authorizes it |
| Swipe up/down, wait for Reel end, tap next/creator/audio/hashtag/comment profile/related item, or force `pushState` to another ID | No second item appears; viewer returns to the origin thread on unsafe navigation |
| DM-shared Story/profile/unsupported Instagram link | Destination never opens; neutral hidden-content treatment where recognized |
| Popup, target-blank, `window.open`, long press | No second WebView or social-app escape |
| Fullscreen then repeated Back | First Back exits fullscreen to the same sealed item; second Back returns to the origin thread; later Back cannot reopen the item |
| Background/resume, rotation, process kill, session expiry | Route re-audited; rotation preserves active view; stale capability never restores; process restart begins at native home |

## YouTube escape matrix

1. Sign in through the current Google flow and verify the service lands on mobile Subscriptions, never Home.
2. Confirm Subscriptions and explicit Search work.
3. Confirm every Shorts shelf/result/navigation item is absent. Try direct `/shorts/<id>` and Home URLs; both must return to Subscriptions without a visible flash.
4. Select a normal video from Subscriptions, go Back, then select one from Search. Both should play.
5. Paste/navigate a `/watch?v=` URL without selecting it in ClearFeed; it must be blocked.
6. On Watch, confirm related/next videos, Shorts shelves, comments, end-screen cards and autoplay-next are absent. Let the video end; another video must not start or navigate.
7. Try channel, playlist, trending, logo/Home, notification, profile and modified/target-blank links. None may become a content rabbit hole.
8. Enter and exit fullscreen, rotate, press Back while fullscreen, background/resume, lose network, and restart the app. Back exits fullscreen first and then returns to the previously safe Search/Subscriptions state.
9. Try camera/microphone and file-upload prompts on YouTube. They must be denied.

## Facebook escape and cap matrix

1. From a cleared session, verify the logged-out Feeds form automatically moves to Facebook's dedicated `/login/` route before credential submission. A failed load must show a `CF-*` code and a privacy-redacted report under More options → Diagnostics.
2. Sign in and verify ClearFeed lands on Facebook's newest Feeds route with `filter=all&sk=h_chr`, not ranked Home. Verify current All/Favorites/Friends/Groups/Pages filters, Messages/Messenger, Search, Notifications, Friends, an intentional profile/Page, Group and Event.
3. In Feed, count only retained normal text/photo/link articles. At most eight may remain visible. After the eighth, the ClearFeed end card must appear; no load-more/unlock/increase control may exist.
4. Scroll aggressively and wait for React/infinite-loading mutations. No ninth safe card may become visible and loading sentinels must remain hidden.
5. Repeat the eight-safe-post test on a profile/Page, Friends view and Group stream.
6. Confirm video-containing articles, Reels, Stories, live/Watch/video shelves and obvious recommendation filler are removed before content reveal.
7. Try direct Reel, Story, Watch, video, live, Marketplace and Gaming URLs and links. Each must recover to the limited Feed.
8. From Search and Notifications, open a text/photo post (allowed) and a video/Reel/Story result (blocked). Confirm video-oriented tabs/results are absent.
9. In Messenger, play a private video attachment if the web client supports it (allowed), then tap a shared Facebook Reel/video link (blocked).
10. Exercise E2EE threads, new/group conversation, conversation search, image/video/voice attachments, login challenge and session persistence without inspecting private content natively.
11. Try popups, long press, repeated Back/forward gestures, fullscreen attachment, rotation, background/resume, network loss and process restart.

## Cross-platform and attacker checks

- Instagram Direct normal YouTube watch or `youtu.be` link: exact protected watch; no Home/Shorts/related/comments/autoplay; Back returns to the exact thread. YouTube Shorts and Facebook Reel/video links stay blocked in Direct.
- Facebook safe content normal YouTube link: exact protected watch; Short blocked. Instagram profile/Reel/post stays blocked because Facebook cannot mint the DM capability. An allowed normal Facebook text/photo/Page/group/event link from YouTube is re-evaluated by Facebook policy; Facebook Reel/video stays blocked.
- From a YouTube description, Instagram profile/post/Reel stays blocked. A Facebook safe destination may switch internally; a Facebook video/Reel may not.
- Verify nested repeated Back using `Instagram thread -> YouTube video -> Facebook safe page -> Back -> protected YouTube video -> Back -> exact Instagram thread -> Back -> Instagram inbox`. No destroyed WebView history may appear.
- Install or simulate official Instagram/YouTube/Facebook apps and try protected HTTPS, `instagram:`, `youtube:`, `vnd.youtube:`, `fb:`, `messenger:` and `intent:` links. They must route internally when a safe HTTPS destination is recoverable or remain blocked; Android's resolver must not receive them.
- From each service, tap an unrelated HTTPS article. It must require the native external-browser confirmation. Repeat with a scripted click/redirect; no prompt or handoff may be created.
- Try HTTP, custom schemes, ports, user info, encoded slashes/traversal, mixed-case/punycode/lookalike hosts, redirect parameters and malformed percent encoding.
- Search production source for toggles or bypasses: `strictMode`, `filterEnabled`, `reelsEnabled`, `shortsEnabled`, `allowReels`, `allowShorts`, `allowFeed`, `feedLimitEnabled`, `temporaryUnlock`, `disableProtection`, `bypass`, `focusMode`, `unrestricted`.
- Review every `loadUrl`, `shouldOverrideUrlLoading`, `goBack`, `goForward`, `window.open`, `pushState`, `replaceState`, and `Intent.ACTION_VIEW` call after navigation changes.

## Performance checks

On a mid-range phone, observe memory and jank with Android Studio Profiler or `dumpsys meminfo`:

- switch among all three services repeatedly and confirm only one WebView/render process remains active for ClearFeed;
- scroll each dynamic page for several minutes and verify debounced observers do not cause sustained CPU use;
- play a long YouTube video and a private Messenger attachment, enter/exit fullscreen, and verify the WebView is not recreated;
- return native home and confirm the selected WebView is destroyed while subsequent service selection remains responsive.

Re-run the full automated gate and every relevant device escape test whenever a route, origin, guard selector, WebKit dependency, or platform authentication flow changes.
