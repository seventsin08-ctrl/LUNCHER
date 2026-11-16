# Luncher

An Android home-screen launcher with an iPhone-style "dark clear glass" look:
monochrome glass app icons, photo widgets, a live calendar widget, a live
analog clock icon, a batteries ring widget, a frosted dock, swipe-down search,
and an App Library page.

## Install on your phone

1. On your Android phone, open the repo's **Releases** page and download
   `Luncher.apk` from the **latest** release.
2. Open the file. If Android asks, allow "Install unknown apps" for your browser/files app.
3. Open **Luncher**, then tap the banner at the top (or press Home) and pick
   **Luncher** as your default Home app.
4. Long-press an empty spot on the home screen → **Use Dark Glass Wallpaper**
   to get the smoky gray background from the reference look.

## How to use

| Action | What it does |
| --- | --- |
| Swipe left/right | Change pages (the last page is the App Library) |
| Swipe down / tap **Search** | Search apps |
| Tap a photo widget | Pick a photo (long-press: change photo, color/B&W, remove) |
| Long-press an app | Add/remove from dock, App info, uninstall |
| Long-press empty space | Wallpaper options, default home settings |

Icons use Android 13+ themed (monochrome) icons when an app provides them,
and fall back to a desaturated version of the app's normal icon.

## Build

GitHub Actions builds the APK on every push (`.github/workflows/build.yml`).
Locally: `./gradlew assembleDebug` (requires the Android SDK).
