# ClearFeed

ClearFeed watches the real YouTube app in the background and sends you back to your home
screen the moment its Shorts player opens. It does not reimplement YouTube, does not run a
sandboxed browser, and does not request a single Android permission — it uses an Accessibility
Service to detect one screen and react to it.

This is a personal-use, sideloaded app: no Play Store distribution, no backend, no analytics,
no network access of any kind.

## Why this exists, and why it changed shape

ClearFeed originally wrapped Instagram, YouTube, and Facebook in a hardened WebView and tried
to allowlist which pages could load and strip distracting elements from the DOM. That
approach chased three providers' web markup through every redesign, broke in ways that were
only debuggable after the fact, and — even when working — never gave you the real apps
(no push notifications, no camera roll, none of what makes the official apps actually usable
day to day).

The current approach: let the official YouTube app run exactly as published, and use an
Android Accessibility Service to detect its Shorts player and get you out of it. This is the
same mechanism blockers like NoScroll, Opal, and One Sec use — not a workaround, the standard
tool for this problem. The full history of the WebView approach, including its extensive test
suite, is preserved in this repository's git history if it's ever useful again.

## How it works

- `ShortsAccessibilityService` receives window-change events from the system whenever
  YouTube's foreground screen changes.
- `AccessibilityNodeWalker` turns the current screen into a bounded snapshot of structural
  view identifiers only — resource ids and class names. It never reads visible text,
  captions, or content.
- `ShortsDetector` (pure Kotlin, fully unit-tested) classifies the snapshot. A match sends the
  user home via `performGlobalAction(GLOBAL_ACTION_HOME)`.
- Detection is deliberately conservative: it matches on structural ids specific to the Shorts
  player surface, never on visible text — YouTube's bottom navigation tab is permanently
  labelled "Shorts" even on the Home screen, so text-based matching would misfire constantly.
- Every action, and every YouTube screen that was *not* detected as Shorts, is logged to a
  local, in-memory activity trace (`BlockerDiagnostics`), viewable and shareable from the app.
  Nothing is transmitted automatically. This is how detection gets corrected if it's ever
  wrong on a real device: there is no reliable way to inspect a live YouTube view hierarchy
  without one.

See [TESTING.md](TESTING.md) for what's actually verified and what remains manual.

## Build

Requirements:

- JDK 17
- Android SDK Platform 36
- Android SDK Build Tools 35.0.0 or newer

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
```

Artifacts:

```text
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release-unsigned.apk
```

Release signing credentials are intentionally not stored in the repository.

## Install or update

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`-r` requests an in-place update.

### Debug builds use a fixed signing key

`clearfeed-debug.keystore` is committed and the `debug` build type signs with it. Android's
default is a keystore generated per machine, so a debug APK built anywhere else is signed
with a different key, and Android refuses to install it over an existing app
("App not installed as package conflicts with an existing package"). Pinning the key makes any
machine — this one, yours, CI — produce an installable update. Only the `.debug` application
ID is signed with it; a release key is never stored in the repository.

### Enabling the blocker

Open the app and tap **Open Accessibility Settings**, then enable ClearFeed's Shorts blocker.

Android restricts this for any app installed outside the Play Store (Android 13+). If the
setting looks greyed out or Android says it's "currently unavailable for your security": open
**App info → ⋮ menu (top right) → Allow restricted settings**, then go back and enable it.
That's an Android requirement, not something ClearFeed can skip — the in-app screen explains
this same step.

## Privacy and permissions

ClearFeed requests zero Android permissions. It has no `INTERNET` permission, so it cannot
make a network request even if it wanted to. The accessibility service reads only structural
view identifiers (resource ids, class names) from YouTube's screen — never text, captions,
video titles, or account information — and nothing it reads leaves the device. See
[`public/privacy.html`](public/privacy.html).

## Known limits

- **YouTube Shorts only, for now.** Instagram Reels and Facebook Reels are not covered.
  `ShortsDetector`'s resource-id signals are specific to YouTube's Shorts player; extending
  coverage means adding a similarly-scoped detector per app.
- **Detection depends on YouTube's current view hierarchy**, assembled without access to a
  live, current build of the app. It can miss (fails open — Shorts plays normally) or, in
  principle, over-match (fails toward disruption) if a resource id changes meaning between
  YouTube versions. The activity log exists specifically to catch and correct this.
- **Global Home is blunt.** It exits YouTube entirely rather than just leaving Shorts, matching
  what was asked for ("kick me out of the app"), but it means there's no way back to
  Subscriptions without reopening YouTube.
- Enabling the accessibility service is a manual, one-time step Android requires; ClearFeed
  cannot enable it automatically.

## License

ClearFeed's original code and branding are available under the MIT License. YouTube is a
trademark of Google LLC. This independent project is not affiliated with or endorsed by
Google.
