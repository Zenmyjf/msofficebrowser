# Office Desktop Browser (Android)

A minimal Android app that opens Office Online (Excel/Word/PowerPoint) in a
locked-landscape, fullscreen WebView with a **draggable virtual mouse
cursor** — so hover menus, ribbons, and right-click context menus behave
close to how they do on a real desktop.

## Features
- Forces landscape orientation and true fullscreen (immersive mode).
- Loads pages with a desktop Chrome user-agent, so Office serves the full
  desktop layout instead of the mobile-cut-down version.
- **Virtual mouse / trackpad mode**: drag one finger anywhere on the screen
  to move the on-screen cursor, like a laptop trackpad.
  - Tap = left click
  - Double-tap = double-click
  - Long-press = right-click (context menu)
  - Two-finger drag = scroll
- Toggle button to switch to "direct touch" mode (native scrolling/pinch-zoom,
  no virtual cursor) whenever you prefer it.
- Keeps the screen awake, supports Microsoft login redirects and cookies,
  and forwards file downloads/uploads to the system.

## 1. Put this on GitHub
1. Create a new **empty** repository on GitHub (no README/license, so it
   doesn't conflict with these files).
2. Upload every file/folder from this project into that repo, preserving
   the folder structure exactly (the `.github`, `app`, and root files).
   Easiest way: on the repo page, use "Add file → Upload files" and drag
   the whole extracted folder in, or use GitHub Desktop / `git push` if
   you're comfortable with git.

## 2. Let GitHub Actions build the APK for you
This repo includes `.github/workflows/build.yml`, which automatically
compiles the app into an installable APK every time you push to `main`.

1. After uploading, go to the **Actions** tab of your repo.
2. You should see a workflow run called "Build APK" running (or click
   "Run workflow" to trigger it manually).
3. Wait for it to finish (a few minutes).
4. Click the finished run → scroll down to **Artifacts** →
   download `OfficeDesktopBrowser-debug-apk`. It's a zip containing
   `app-debug.apk`.

## 3. Install it on your Android phone
1. Transfer `app-debug.apk` to your phone (e.g. via Google Drive, email
   to yourself, or a USB cable).
2. Tap the file to install. Android will warn about "unknown sources" —
   you'll need to allow installs from that app (Files/Chrome/Drive)
   in Settings the first time.
3. Open "Office Desktop" from your app drawer.

## Changing which site it opens
Open `app/src/main/java/com/example/officedesktop/MainActivity.kt` and edit
this line near the top:

```kotlin
private const val START_URL = "https://excel.new"
```

Some options: `https://word.new`, `https://powerpoint.new`,
`https://www.office.com`, or any other website — this works as a general
"desktop browser" shell, not just for Excel.

## Known limitations
- Microsoft's sign-in page occasionally blocks embedded WebViews for
  security reasons. Using a desktop user-agent (already set in the code)
  works around this in most cases, but if you ever see a
  "try a different browser" message, sign in once inside your phone's
  regular Chrome browser first (so the Microsoft account session/cookie
  exists), then reopen this app.
- The virtual mouse dispatches real `MouseEvent`s into the page's DOM
  (not real OS-level input), so it works for click/hover/scroll-driven
  UI, but a few very custom canvas-based widgets in Office may not
  respond perfectly to hover.
- This builds a **debug** APK for personal use/testing. If you eventually
  want to publish it or share it more widely, it should be signed as a
  release build instead.

## Project structure
```
OfficeDesktopBrowser/
├── .github/workflows/build.yml   <- builds the APK automatically
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/.../MainActivity.kt   <- all the app logic
│       └── res/                       <- layout, icons, colors, theme
├── build.gradle
├── settings.gradle
└── gradle.properties
```
