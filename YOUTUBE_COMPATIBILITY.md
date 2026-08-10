# YouTube compatibility

Last inspected: **2026-08-08**

Inspection used current mobile YouTube routes in an isolated signed-out browser plus mobile-responsive structural checks. No private titles, subscription names, account identifiers, cookies, or storage were retained.

## Live-verified mobile behavior

| Function | Current observation |
|---|---|
| Subscriptions | `https://m.youtube.com/feed/subscriptions?persist_app=1&app=m`; root `ytm-app` and mobile pivot components |
| Search | `/results?search_query=...`; `ytm-search`, GET action `/results`, and `ytm-video-with-context-renderer` results |
| Watch | `/watch?v=...&persist_app=1&app=m`; `ytm-watch`, `video`, and single-column watch-next structure |
| Shorts leakage | `/shorts/<id>` links and Reel shelves occurred in Subscriptions/Search/Watch contexts |
| Recommendations | Watch contained multiple other `/watch` links and related item sections |
| Comments/autoplay UI | Mobile watch structure exposes comment and autoplay/next surfaces that the guard targets |
| Authentication | Current sign-in leaves YouTube for exact Google account/consent origins and then returns |

## Supported policy

- Mobile Subscriptions and Search are safe roots.
- Watch is allowed only for the exact `v` ID carried by a guarded click from Subscriptions/Search or a genuine cross-platform tap on a normal watch/`youtu.be` link and authorized in memory by native code. A YouTube-to-YouTube link cannot mint the latter token.
- YouTube Home, Shorts, trending, playlists, channels/handles, gaming/live discovery, and unknown routes fail closed.
- Cross-platform `/shorts/<id>` and recovered Shorts intents remain blocked; the router never converts an explicit Shorts route into a watch URL.
- Current narrow Google account chooser/sign-in/OAuth/consent routes are allowed only during the protected flow. Arbitrary account-management pages are blocked.

## Compatibility update procedure

If Google changes sign-in, add only the exact origin/path needed and test arbitrary neighboring paths. If YouTube changes mobile DOM, update semantic selectors in `youtube_guard.js`/`.css`, add a guard-contract or fixture test, and verify no Shorts, comments, related cards, end-screen recommendation or autoplay-next flash appears before reveal.

Device/account validation is still required for sign-in, paid/age-restricted content, captions, quality controls, fullscreen/rotation, playback completion and session persistence.
