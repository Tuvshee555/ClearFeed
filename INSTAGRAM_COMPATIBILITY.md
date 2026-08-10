# Instagram compatibility

Last inspected: **2026-08-09**

Inspection was read-only in a mobile-responsive Instagram Web session. No conversation screenshots, message bodies, credentials, cookies, or storage were captured.

## Live-verified routes and semantics

| Function | Current observation |
|---|---|
| Direct inbox | `https://www.instagram.com/direct/inbox/` |
| Message requests | `/direct/requests/` |
| New message | Inbox dialog; semantic control label `New message` |
| Inbox structure | `main` plus `[aria-label="Thread list"]` |
| Inbox tabs | Primary, General, and Requests use `role="tab"` |
| Unsafe chrome | Semantic labels included Instagram, Home, Reels, Search, Notifications, New post, Professional dashboard, Settings, and Also from Meta |
| Unsafe hrefs | Root, `/reels/`, `/explore/`, and profile paths appeared beside Direct |
| Singular Reel permalink | `/reel/<stable-id>/`; one video was present in the signed-out responsive shell |
| Plural item route | `/reels/<same-id>/`; the inspected page contained a vertical overflow stream with four video-bearing children, confirming adjacent preload behavior |
| Story route | `/stories/...`; remains unsupported and blocked even from Direct |

The inspection did not open a real thread because it could mark private content read. The exact `/direct/t/<id>/` pattern remains supported. `/direct/new/` remains a narrow legacy Direct route although the current composer is an inbox modal. A normal post shell could not be meaningfully inspected while signed out, so `/p/<id>/` support is protected by exact policy and synthetic guard fixtures and still needs a test-account device pass.

## DM-sent content boundary

A supported Reel or ordinary post permalink is visible only inside an exact Direct thread. The capturing click guard requires a browser-trusted event and a message-surface ancestor, then sends only the normalized supported path through the token-authenticated main-frame bridge. Native code binds a temporary capability to the exact stable item and origin thread and opens a canonical URL carrying a random fragment capability.

The sealed viewer is revealed only after sanitation and structural health. It hides preloaded sibling Reel items, related content, profile/creator/audio/caption escapes, unsafe chrome and comments; prevents vertical touch/wheel navigation and media-end propagation; and allows horizontal movement for media belonging to one approved post. Any different item, route, nonce, SPA transition, redirect, external link or Direct shortcut closes the viewer back to the exact origin thread. Stories remain blocked.

An exact Direct thread may also hand a genuinely tapped normal YouTube video or Facebook-safe destination to ClearFeed's centralized router. YouTube Shorts and Facebook Reel/video/Story destinations remain blocked. Facebook or YouTube origins cannot mint Instagram's DM-content capability. Back from a successful handoff reconstructs the exact origin thread using volatile URL metadata only.

## Authentication boundary

The policy permits only login/2FA/onetap, challenge/checkpoint, email/phone confirmation, password recovery, and narrow consent/privacy-check routes. These routes are unit-tested but were not completed inside an Android WebView because no device and no test credentials were available.

Do not broaden to `/accounts/` after a compatibility break. Reproduce with a test account, record the exact required route, add it to both `InstagramRouteClassifier.kt` and `instagram_guard.js`, add allow and escape tests, and keep pre-reveal concealment enabled.

## Preserved DirectOnly behavior

ClearFeed uses `DirectOnlyNavigationPolicy` and the semantic Instagram guard as its Instagram platform module. Instagram has Direct plus the exact supported item another person chose to send in Direct; it has no Feed, profile browsing, arbitrary post/Reel mode, Story viewer, Explore, notification, creation, recommendation chain, or unknown-route mode.
