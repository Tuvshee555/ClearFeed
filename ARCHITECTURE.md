# Architecture

## Native shell and WebView lifetime

`ClearFeedApp` is a native Compose service picker. `ClearFeedCoordinator` owns the state machine and permits one selected platform at a time. Returning to the launcher removes and destroys the active WebView; WebView's domain-scoped cookies and site storage remain so provider sessions persist. Arbitrary WebView history is not restored after process death.

```text
HOME -> STARTING -> AUTHENTICATING -> CONTENT
          |              |             |
          |              |             +-- Instagram DM tap --> SHARED_CONTENT_VIEWER
          |              |                                      |
          |              +---------- policy block --------------+ -> BLOCKED_RECOVERY
          +---------- network ------------------------------------> OFFLINE
          +---------- guard/load/renderer ------------------------> WEB_ERROR
          +---------- unsupported modern WebView ------------------> WEBVIEW_UNAVAILABLE
```

`applicationId` remains `dev.directonly.app`; the renamed ClearFeed APK can update the original DirectOnly install when signing identity matches.

## Shared enforcement engine

`PlatformNavigationPolicy` defines a platform's safe root, exact top-level origins, document-start origin rules, subframe boundary, and URL evaluation. `PlatformPolicyRegistry` supplies:

- `DirectOnlyNavigationPolicy` for Instagram;
- `YouTubeNavigationPolicy`;
- `FacebookNavigationPolicy`.

`UrlNormalizer` requires an absolute URI, normalizes IDN hostnames, rejects user info, explicit ports, malformed or encoded separators/traversal, and exposes normalized path plus raw query/fragment for policy checks. HTTPS is mandatory. Known and lookalike social hosts cannot be handed to the external-browser prompt.

`ProtectedWebViewClient` evaluates top-level navigation in both `shouldOverrideUrlLoading` overloads, `onPageStarted`, `onPageCommitVisible`, `onPageFinished`, and `doUpdateVisitedHistory`. SSL errors and Safe Browsing hits are never bypassed. A committed unsafe location is concealed, stopped, and recovered to the current platform's safe root.

Every native `loadUrl` goes through `ProtectedNavigator`, which immediately re-evaluates the destination. `ProtectedWebViewFactory` disables file/content access, mixed content, geolocation, popup WebViews, downloads, and anchor/image context menus. Web contents debugging is debug-build only.

`ProtectedSocialLinkRouter` is the single top-level classifier for Instagram, YouTube, Facebook and Messenger domains. It uses parsed IDN-normalized exact-domain/subdomain relationships, recognizes current outbound wrappers (`l.instagram.com`, `l.facebook.com`, YouTube redirect) and conservatively recovers HTTPS targets from supported app/`intent:` schemes. A protected destination is never an external-browser candidate: it is evaluated by the target platform policy or blocked. Brand-lookalike hosts fail closed. An unrelated HTTPS URL requires a genuine tap and native confirmation.

## Document-start boundary

`PlatformScriptInjector` requires `DOCUMENT_START_SCRIPT` and `WEB_MESSAGE_LISTENER`; without both, social content is not loaded. It injects one platform guard on that platform's exact HTTPS origins and exposes an origin-restricted `clearFeedBridge`. Listener events are accepted only from the main frame and a top-level origin independently accepted by the native policy.

Each installed guard receives a fresh random bridge token in its document-start closure. Native code rejects messages without the exact token as well as messages from a subframe or untrusted origin. The bridge carries bounded JSON events only:

- normalized route candidate;
- blocked attempt;
- explicitly clicked ordinary external URL;
- exact YouTube watch path selected from an allowed source;
- exact Instagram Reel/post path genuinely tapped inside a Direct thread;
- guard version and structural health.

It carries no passwords, cookies, storage, message text, video titles, profile names, or page snapshots. No `addJavascriptInterface` is used.

The WebView starts transparent for each navigation. Native code reveals it only after the correct platform guard version reports a policy-allowed route and recognizable structure. Guard failure times out to an error instead of exposing an unverified page.

## Platform rules

### Instagram

Exact Direct inbox, thread, request, new-message, and narrow authentication/recovery routes load. A trusted click on a supported Reel/post permalink inside an exact Direct thread can create one volatile capability bound to stable content ID, canonical path, origin thread, random nonce, and creation time. An unapproved or manually loaded `/reel`, `/reels/<id>` or `/p/<id>` remains blocked.

While that capability exists, the native policy rejects every route except the exact approved item—even Direct, auth, and external routes. The document-start guard hides neighboring preloaded Reel nodes, recommendations, creator/profile links, captions' navigation links, comments and unsafe chrome; blocks vertical touch/wheel Reel navigation; prevents media-end propagation; and continuously audits history/location changes. A post's horizontal same-object carousel remains usable. Back, an unsafe transition, or close consumes the capability before loading the exact origin thread.

### YouTube

The safe root is mobile Subscriptions. Search results are allowed. A `/watch` URL is blocked unless its exact `v` identifier matches an in-memory native intent token created by a guarded click on Subscriptions/Search or by a genuine cross-platform tap on an exact normal YouTube video. A same-platform watch-to-watch link cannot mint this handoff token. The token is cleared when leaving watch content and is never restored after process death.

The YouTube guard removes Shorts shelves/results, comments, related/next-video modules, autoplay-next controls, cards and end screens. It disables the media element's `autoplay` attribute while retaining deliberate playback and WebChrome fullscreen. Watch-to-watch navigation with a different ID is stopped by both SPA and native checks.

### Facebook

The safe root is Facebook's live-verified newest Feeds route, `/?filter=all&sk=h_chr`; current Favorites/Friends/Groups/Pages Feeds subfilters are also allowed. Ranked Home and `/home.php` are blocked. Messenger routes, Search, Notifications, Friends, intentionally opened profiles/Pages/Groups/Events, and narrow post/photo permalinks remain available. Reels, Stories, Watch/video/live, Marketplace and Gaming routes are blocked before load.

URL-safe Facebook content receives a second DOM classification before reveal. On Feeds, profile/Page, Friends, and Group streams, top-level `[role="article"]` cards are classified. Cards containing video/Reel/Story signals or obvious recommendation filler are hidden. Safe text/photo/link cards are counted once; after `FACEBOOK_FEED_POST_LIMIT = 8`, later cards, infinite-load sentinels and continuation controls are hidden and the caught-up card is kept active. The observer continues enforcing the cap as React recycles DOM.

Private Messenger video attachments are exempt from DOM video removal. A shared Facebook Reel/video destination inside a message remains route-blocked.

## Back, fullscreen, and external links

- Back at Instagram inbox, YouTube Subscriptions, or Facebook limited Feed returns to the native launcher.
- Back from an Instagram sealed viewer clears its one-item capability and loads the exact Direct thread that created it. Back from another nested safe page uses only an independently policy-approved previous history entry; otherwise it loads the safe root.
- A genuine cross-platform tap stores only the source platform, exact independently safe return URL and—when needed—the selected YouTube video ID. The old WebView is destroyed and the target receives a fresh platform policy/context. Back pops this volatile return stack and reconstructs the prior protected page; it never exposes the destroyed WebView's history.
- Back exits a WebChrome fullscreen custom view before changing WebView history.
- Fullscreen is accepted only for an allowed YouTube watch page, exact Instagram shared Reel/post, or private message attachment. Back exits the custom view first.
- Ordinary external HTTPS links require native confirmation and open via the system browser chooser. The `ACTION_VIEW` boundary re-runs the centralized classifier, so social-provider, recoverable deep-link and lookalike destinations are never passed to Android's resolver.

## Session reset and process behavior

The maintenance menu exposes one explicit “Reset all web logins” action because Android WebView does not provide a reliable cross-version API for enumerating and deleting all data for exactly one provider. The action clearly clears every provider's WebView cookies, cache, form/history, and site storage. It does not alter policy.

Rotation is handled without recreating the activity, preserving an active conversation/video. Process restart returns to native home and reconstructs only the selected safe root after the user chooses a service. Instagram shared-content capabilities have no persistence API and therefore cannot survive process death.
