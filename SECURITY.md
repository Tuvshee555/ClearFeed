# Security and privacy review

## Threat model

ClearFeed treats provider HTML, links, redirects, popups, SPA routing, shared cards, notification destinations, DOM regeneration, encoded URLs, and WebView history as untrusted. The security objective is to keep restricted surfaces inaccessible inside ClearFeed. The device owner, other applications, an external browser, a rooted/modified OS, and the providers' servers are outside this boundary.

## Defense in depth

- HTTPS-only exact origins and narrowly classified normalized routes; unknown provider paths fail closed.
- User-info, explicit ports, custom/non-network schemes, encoded traversal/separators, and deceptive provider-looking hosts rejected.
- Main-frame checks before load, at start, commit, finish, visited-history update, retry, resume, and Back.
- Document-start concealment plus click, `pushState`, `replaceState`, `popstate`, `pageshow`, and periodic location audits.
- Debounced semantic DOM sanitation with a continuing `MutationObserver`.
- Native transparency until an exact-origin, exact-version guard-health event confirms safe content.
- Origin-restricted AndroidX WebKit messages; no `addJavascriptInterface` and no broad wildcard origin.
- No arbitrary external page inside the privileged WebView. Managed social and lookalike domains cannot be opened externally from ClearFeed.
- A centralized IDN-normalized domain router owns Instagram, YouTube, Facebook and Messenger handoffs. Exact base/subdomain boundaries prevent query-string and suffix tricks; known outbound wrappers are unwrapped before classification. Protected targets are evaluated by their own policy and never become generic Android `ACTION_VIEW` data.
- Document-start guards report whether a handoff came from a browser-trusted event and replace `window.open` with the same authenticated route event. Scripted clicks, popups, redirects and subframes cannot mint cross-platform intent.
- File/content URL access and mixed content disabled; SSL errors canceled; Safe Browsing enabled; geolocation denied; popup WebViews, automatic downloads, and social link context menus refused.
- Release WebView debugging disabled using `BuildConfig.DEBUG`.
- App/WebView data excluded from Android backup and device transfer.
- No ads, analytics, crash SDK, backend, cloud database, private social API, cookie export, clipboard monitoring, accessibility service, notification listener, device admin, or device owner.

Local diagnostics are failure-only and memory-only. Copied reports contain the app, Android, and System
WebView versions; a short failure code; the selected service; and a redacted host/first-path segment.
Queries, fragments, message/thread identifiers, page content, credentials, and cookies are excluded.

## Platform-specific controls

Instagram retains its exact Direct/auth allowlist and unknown-route denial. The only content exception is a one-hop, in-memory capability minted from an authenticated document-start bridge event for a genuine tap in an exact Direct thread. It binds stable item type/ID, canonical path, origin thread, a 128-bit nonce, and monotonic creation time. While active, every non-matching navigation is blocked and recovers to the origin thread. The guard removes adjacent/related items and comments, blocks vertical Reel gestures and media-end propagation, and permits horizontal carousel movement only within the same approved post. Capabilities expire, are consumed by Back, and are cleared on service change, home, reset, error, renderer loss, guard timeout, and process death.

YouTube watch authorization is capability-like: only the exact video ID selected by a guarded click on Subscriptions/Search or a genuine normal-video handoff from another protected platform is accepted. A YouTube-to-YouTube description link cannot mint a next-video token. Home, Shorts, channels, playlists and untokened watch URLs remain blocked. The token is in-memory, exact-match, and cleared on non-watch content.

Facebook combines route classification with pre-reveal DOM classification. Its safe root requires the live-verified newest Feeds query; ranked Home is rejected. Video/Reel/Story/recommendation articles are removed, streams stop at eight accepted semantic articles, continuation/loading controls remain hidden, and late DOM mutations remain observed. Private message attachments are allowed only while the active route independently classifies as messaging.

Cross-platform return metadata contains only source platform, an independently policy-approved URL and an optional exact YouTube video ID. It is volatile, cleared on Home/reset/process recreation, and cannot carry Instagram shared-content permission or weaken the destination policy.

## Credential and private-content handling

Provider HTML handles credentials, E2EE browser state, messages, and video playback. Guards do not query input values, passwords, cookies, local storage, IndexedDB, message text, titles, handles, or media bytes. They inspect route metadata, semantic attributes, element types, and—only inside Facebook recommendation classification—a short local text sample that is never sent to native code or logged.

Cookies and DOM/database storage remain in Android WebView for normal provider session persistence. “Reset all web logins” explicitly deletes all WebView cookies and site storage; the UI does not imply per-service isolation that WebView cannot reliably guarantee.

## Permissions

`CAMERA` and `RECORD_AUDIO` are declared but not requested at startup. A WebView grant requires:

1. the selected platform's exact trusted origin;
2. an active Instagram Direct or Facebook/Messenger message route;
3. an audio/video capture resource only;
4. the matching user-granted Android runtime permission.

YouTube and public Facebook surfaces cannot receive capture permission. Pending requests are denied on activity pause. Message uploads use Android's system picker and accept returned `content:` or `android.resource:` URIs only. No broad storage permission is requested.

## Known limits

DOM filtering cannot be formally proven against all future provider changes. The design mitigates this with pre-reveal health checks, independent native routing, ongoing mutation enforcement, and failure closed. A provider could also change authentication, WebView support, E2EE requirements, or semantic markup and make a useful surface unavailable until compatibility code is updated.

After the user confirms an ordinary unrelated link, the system browser is outside ClearFeed's enforcement and that site may redirect elsewhere there. ClearFeed never intentionally hands a protected social URL or app scheme to Android's resolver, even when official apps are installed. It still cannot control links opened later by an external browser, direct use of official apps/Chrome, uninstalling, reflashing, or another device and makes no phone-wide “unbreakable” claim.
