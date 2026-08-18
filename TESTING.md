# Testing

## Automated verification

CI runs this on every push and pull request — see
[`.github/workflows/verify.yml`](.github/workflows/verify.yml). To run it locally:

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_SDK_ROOT="$HOME/Android/Sdk"
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
```

`gradlew` must stay committed as mode `100755`. It was once committed `100644`, which made
`./gradlew` fail with `Permission denied` on every non-Windows machine; CI asserts the
committed mode explicitly. If it's ever lost again: `git update-index --chmod=+x gradlew`.

## What's actually verified, and what isn't

**`ShortsDetectorTest`** — the classifier's entire decision logic, exhaustively, because this
environment has no Android emulator or device and this pure-Kotlin class is where the real
behaviour lives before anything ever reaches a phone:

- YouTube Home, Search, and a normal watch page are never misclassified as Shorts — including
  the specific trap of YouTube's bottom navigation tab being permanently labelled "Shorts,"
  which a text-based detector would misfire on constantly
- the Shorts player is detected via its resource id, case-insensitively
- every known signal in the resource-id and class-name sets is individually sufficient
- an unrelated resource id, an empty screen, and nodes with no identifiers at all do not match
- the activity-log summary is deduplicated, capped, and reports correctly on an empty screen

**`BlockerStatsTest`** — the local block-count store, against an in-memory fake
`SharedPreferences` (the real class is a non-functional stub under plain JVM tests): today's
count accumulates, resets at a local-date rollover, and the running total is unaffected by
that reset.

**`LocalDiagnosticsTest`** — the activity trace shared between the service and the UI: bounded
history, relative timestamps, and full-sequence report rendering.

### What cannot be verified here

- **`AccessibilityNodeWalker`** depends on `android.view.accessibility.AccessibilityNodeInfo`,
  which — like `org.json` and `SharedPreferences` before it — is a non-functional stub under
  plain JVM tests. It is deliberately kept thin and dumb (walk the tree, read two fields) so
  that the actual decision logic lives entirely in the tested `ShortsDetector`.
- **`ShortsAccessibilityService`** itself: whether Android actually delivers the expected
  events, whether `rootInActiveWindow` returns what's expected mid-transition, whether
  `performGlobalAction(GLOBAL_ACTION_HOME)` behaves as expected from within Shorts, and
  whether the debounce timings feel right in practice. None of this is verifiable without a
  real device.
- **The resource-id signal list itself.** It was assembled without access to a live, current
  build of the YouTube app. It may be incomplete (misses — Shorts plays normally, fails open)
  or, in principle, could include something too broad (fails toward disruption). The in-app
  activity log exists specifically to close this gap: it records the actual resource ids seen
  on a real device when detection does *not* fire, which is exactly the data needed to correct
  the signal list without guessing.
- Whether Android's "restricted settings" flow, and the in-app instructions for it, actually
  match what a specific Android build shows.

## Manual verification checklist

Every item below needs a real device.

1. Install the APK. Confirm the app opens to a single screen showing "Not protected" and an
   **Open Accessibility Settings** button.
2. Tap it. If Android blocks the accessibility toggle as a restricted setting, follow the
   in-app instructions (App info → ⋮ → Allow restricted settings) and confirm they work.
3. Enable the service. Return to ClearFeed and confirm the status flips to "Protected" without
   needing to force-close and reopen the app.
4. Open YouTube, use it normally (Home, Search, a normal video) for a few minutes. Confirm
   none of that triggers a block.
5. Open YouTube Shorts. Confirm you're sent to the home screen promptly and confirm the
   "Blocked today" counter in ClearFeed increments.
6. Repeat step 5 a few times across a session; confirm no repeated/rapid re-triggering and no
   missed detections.
7. Reboot the device and confirm the service is still enabled and still works without
   reopening ClearFeed first.
8. Open **View activity** and confirm the log is legible and useful — this is the channel for
   reporting any of the above if it goes wrong.
