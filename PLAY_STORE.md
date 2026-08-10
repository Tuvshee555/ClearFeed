# Google Play release checklist

ClearFeed is an Android app. Vercel can host its public privacy-policy page, but it cannot run,
sign, or verify the Android app. Use a Google Play testing track for professional phone installs.

> **Public-release blocker:** a valid signed AAB is necessary but not sufficient. Google Play can
> reject apps whose primary purpose is an unauthorized WebView of third-party websites. Obtain and
> retain permission from the relevant website owners before a public submission. Google also does not
> support Google sign-in inside embedded Android WebViews, so YouTube authentication needs a compliant
> native/system-browser identity architecture rather than user-agent spoofing or other bypasses.

## Fastest official link for the next phone test

Play Console **Internal app sharing** accepts the current debug APK, re-signs it with Google's
internal-sharing certificate, and gives you a phone download link. This is the closest match to a
simple Vercel-style link while still using Google Play distribution. It is only for testing; the
artifact cannot later be promoted to a production release.

Upload:

```text
app\build\outputs\apk\debug\app-debug.apk
```

Use a signed AAB and the testing tracks below only after the public-release blockers above are resolved.

## 1. Create and protect an upload key

Run this once from the repository root and choose strong, unique passwords:

```powershell
keytool -genkeypair -v -keystore clearfeed-upload.jks -alias clearfeed-upload -keyalg RSA -keysize 4096 -validity 10000
```

Never commit or casually share the `.jks` file or its passwords. Back them up securely.

Copy `keystore.properties.example` to `keystore.properties`, then fill in the real path,
alias, and passwords. Both files containing signing secrets are ignored by this project.

## 2. Build the signed Android App Bundle

```powershell
.\gradlew.bat clean testDebugUnitTest testReleaseUnitTest lintDebug lintRelease bundleRelease
```

The bundle is written to:

```text
app\build\outputs\bundle\release\app-release.aab
```

Before upload, verify that Gradle reports a successful release signing task and keep the generated
mapping file from `app\build\outputs\mapping\release\mapping.txt` with the release records.

## 3. Use Play Console internal testing first

Create the app with application ID `dev.directonly.app`, enroll in Play App Signing, upload the
signed AAB to an internal testing release, add testers, and install only from the Play testing link.
This removes debug-build and unknown-source distribution from the testing workflow.

If this is a personal developer account created after November 13, 2023, Google currently requires a
closed test with at least 12 continuously opted-in testers for 14 days before production access. That
requirement does not prevent using internal testing first.

## 4. Complete the listing and policy work

- Publish a public privacy-policy URL. Vercel is suitable for that static page.
- Complete Data safety accurately, including WebView sign-in data and optional camera/microphone use.
- Explain that camera and microphone access is requested only from trusted messaging pages and only
  after the user initiates the feature.
- Supply screenshots, app icon, feature graphic, support contact, content rating, and test credentials
  or review instructions if Google cannot reach the restricted experiences unaided.
- Run the internal track on real devices before promoting to closed, open, or production testing.

## 5. Preserve release identity

Never change the production application ID or lose the upload key. Increase `versionCode` for every
Play upload. The current build is ClearFeed 3.6.3, version code 18.
