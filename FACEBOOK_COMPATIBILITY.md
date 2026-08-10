# Facebook compatibility

Last inspected: **2026-08-09**

Inspection used current responsive Facebook and isolated Messenger/login pages. It recorded only public route shapes and semantic element counts/labels. No message text, thread identifier, profile name, credentials, cookies, or storage were retained.

## Live-verified behavior

| Function | Current observation |
|---|---|
| Ranked Home | `https://www.facebook.com/`; now blocked as a safe root rather than used for the eight-post sample |
| Newest Feeds | Facebook's current Menu exposed “Feeds” as the most-recent view for friends, groups, Pages and more; verified route `https://www.facebook.com/?filter=all&sk=h_chr` |
| Feeds subfilters | Current links used `filter=favorites`, `friends`, `groups`, and `pages` with `sk=h_chr`; these remain inside the finite sanitized Feed policy |
| Navigation | Semantic controls/routes included Home, Friends, Reels, Marketplace, Gaming, Stories, Groups, Messages and profiles |
| Search | Root exposed an input with placeholder `Search Facebook`; search remains a client-side flow under `/search/...` |
| Notifications | `https://www.facebook.com/notifications/` |
| Facebook Messages | `/messages/` may redirect directly to `/messages/e2ee/t/<id>/` in a signed-in session |
| Messenger | `https://www.messenger.com/` and exact login action `/login/password/`; thread routes use `/t/<id>/` |
| Login | Signed-out Facebook may canonicalize between mobile and `www` origins; all supported origins remain exact HTTPS rules |

When the verified Feeds route presents Facebook's logged-out form, ClearFeed detects its password
surface and moves to Facebook's dedicated `/login/` route before credentials are submitted. This
prevents the logged-out form's `/` action from being confused with the deliberately blocked ranked Home.

## Two-stage content classification

Route classification admits only the verified newest Feeds query (including its current followed-source subfilters), narrow message/auth routes, Search, Notifications, Friends, profiles/Pages, Groups, Events and selected text/photo post routes. Ranked `/`, `/home.php`, Reels, Stories, Watch/video/live, Marketplace, Gaming and unknown paths are rejected. Facebook root links are rewritten to the verified Feeds URL before navigation.

DOM classification then hides top-level cards containing `video`, Reel/Story/video links or semantic play labels, and obvious recommendation/discovery filler. Feed/profile/Page/Friends/Group streams retain at most `FACEBOOK_FEED_POST_LIMIT = 8` safe cards for the loaded stream lifetime. Once the limit is reached, later cards, busy/infinite-feed sentinels and load-more controls remain hidden and the caught-up card remains active. Messenger routes do not remove private video attachments, but shared Facebook video/Reel links remain blocked.

The signed-in 412×915 responsive inspection reached a page headed “Feeds” at the verified route. Its sampled structure contained semantic Feed articles and no video element at that moment. Facebook's own UI is the evidence for “most recent”; ClearFeed does not use a private API or independently reorder server results. The eight-post and removal behavior is fixture-tested and still requires the documented on-device account matrix.

## Compatibility risks and update procedure

Facebook React markup and route forms vary by account, locale, experiments, E2EE state and WebView version. The guard uses semantic roles, hrefs, accessible labels, media elements and a small local recommendation-text heuristic instead of generated class names. If safe content cannot be proven, the page remains concealed and the app reports a compatibility error.

For a change:

1. reproduce with a non-private test account without reading private content;
2. record exact main-frame routes and semantic DOM evidence;
3. update both `FacebookNavigationPolicy.kt` and the guard together;
4. add allow, block, redirect, dynamic-video and ninth-post tests;
5. verify Messenger attachment exceptions separately;
6. never expose a generic Facebook path or disable pre-reveal content classification.

Device/account validation is still required for sign-in/checkpoint, E2EE threads, attachments, Search/Notifications destinations, all eight-post stream variants, localization, fullscreen private attachments and session migration.
