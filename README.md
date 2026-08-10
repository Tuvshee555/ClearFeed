# ClearFeed

ClearFeed is one permanent, low-distraction Android gateway for the useful parts of Instagram, YouTube, and Facebook. It uses the providers' current websites in a hardened Android WebView; it has no backend and no unrestricted mode.

| Platform | Allowed | Removed |
|---|---|---|
| Instagram | Direct Messages, supported DM attachments, and the exact Reel/post deliberately opened from a DM | Home, Feed, Explore, Stories, profiles, arbitrary posts/Reels, recommendations, adjacent items and every unknown route |
| YouTube | Subscriptions, explicit Search, and a normal video deliberately selected from either | Home, Shorts, channels/playlists, comments, related/recommended videos, autoplay-next and end-screen recommendations |
| Facebook | Newest Feeds from followed sources, Messages, Search, Notifications, Friends/Pages/Groups/Events, and at most 8 safe non-video stream posts | Ranked Home, Reels, Stories, Watch/video/live, Marketplace, Gaming, recommendation filler, Feed video and infinite streams |

The restrictions are fixed policy, not preferences. There is no focus timer, filter switch, temporary unlock, feed-limit setting, or path to a normal social-media mode.

The More options menu includes local, privacy-redacted diagnostics for WebView, HTTP, TLS, renderer,
and protected-interface failures. Diagnostics remain on the device and contain no query strings,
fragments, message identifiers, analytics, or backend reporting.

## How it works

- A native Compose launcher creates only the selected service's WebView and destroys it when returning home. Normal domain-scoped WebView cookies and storage keep sessions signed in.
- A pure Kotlin HTTPS/host/path policy checks every main-frame request, redirect, commit, SPA update, resume, retry, and Back transition.
- Origin-restricted AndroidX WebKit messaging carries authenticated route, deliberate-selection, and guard-health events. ClearFeed never installs `addJavascriptInterface`.
- Per-platform scripts run at document start, conceal unverified pages, wrap SPA history, intercept clicks, and continuously sanitize regenerated DOM.
- Instagram unknown routes fail closed. A genuine tap on a supported Reel/post inside an exact Direct thread mints one short-lived, in-memory capability for that stable content ID. The sealed viewer removes adjacent/recommended content, blocks vertical Reel gestures and autoplay-next, then consumes the capability when returning to the origin thread.
- YouTube watch URLs require a short-lived native authorization for the exact video ID selected on Subscriptions or Search.
- Facebook starts on the live-verified newest Feeds route. It performs URL and DOM/content classification before reveal; video/Reel/Story/recommendation cards are removed and the lifetime stream count stops at eight.
- A centralized protected-social router intercepts Instagram, YouTube, Facebook, Messenger, redirect-wrapper, popup and recoverable app-deep-link destinations. Each target is re-evaluated by its own policy; normal YouTube videos can receive one exact intentional-watch token, while Shorts, Facebook video and non-DM Instagram content remain blocked. Only unrelated HTTPS URLs can reach the external-browser confirmation.

See [ARCHITECTURE.md](ARCHITECTURE.md), [SECURITY.md](SECURITY.md), and [TESTING.md](TESTING.md).

## Build

Requirements:

- JDK 17
- Android SDK Platform 36
- Android SDK Build Tools 35.0.0 or newer

From PowerShell:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-17.0.20.8-hotspot'
$env:ANDROID_SDK_ROOT = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleRelease
```

Artifacts:

```text
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release-unsigned.apk
```

Release signing credentials are intentionally not stored in the repository.

For professional distribution, use the signed App Bundle and Google Play internal testing workflow in
[`PLAY_STORE.md`](PLAY_STORE.md). Vercel is useful for a public privacy-policy page, not for running the app.

## Install or update DirectOnly

The production application ID remains `dev.directonly.app`; ClearFeed 3.6.3 is designed to update earlier DirectOnly/ClearFeed packages when both APKs use the same signing key. The debug ID remains `dev.directonly.app.debug`.

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r .\app\build\outputs\apk\debug\app-debug.apk
```

`-r` requests an in-place update and preserves WebView app data. Android will reject an update signed with a different key; never uninstall merely to conceal a signing mismatch.

## Privacy and permissions

Provider pages handle sign-in and content. ClearFeed does not read passwords, export cookies, log messages, include analytics, or use private social APIs. Camera/microphone and the system file picker are available only on an active Instagram Direct or Facebook/Messenger conversation route. YouTube playback never receives capture permission.

## Important limitations

- Provider routes and DOM change. ClearFeed intentionally fails closed when its native policy or guard cannot prove a page safe.
- Browser versions of messaging sites may not provide every native-app feature, notification, E2EE flow, or attachment format.
- The eight-post Facebook cap is a per-loaded stream lifetime guard, not an account-wide or daily quota.
- ClearFeed controls only its own WebView. It cannot stop the device owner from using official apps, Chrome, another device, or uninstalling ClearFeed. Optional phone-level ideas are documented in [HARDENING.md](HARDENING.md).

Compatibility observations are recorded in [INSTAGRAM_COMPATIBILITY.md](INSTAGRAM_COMPATIBILITY.md), [YOUTUBE_COMPATIBILITY.md](YOUTUBE_COMPATIBILITY.md), and [FACEBOOK_COMPATIBILITY.md](FACEBOOK_COMPATIBILITY.md).

## License

ClearFeed's original code and branding are available under the MIT License. Instagram and Facebook are trademarks of Meta Platforms, Inc.; YouTube and Google are trademarks of Google LLC. This independent project is not affiliated with or endorsed by those companies.
