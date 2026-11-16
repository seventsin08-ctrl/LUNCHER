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
| Swipe down | Search (apps, calculator, web) — or Notifications, in Settings |
| Swipe up | App Switcher with your recent apps — or system Recents / App Library |
| Double-tap empty space | Lock the screen (turn on in Settings) |
| Long-press an app | Edit Home Screen, Dock, hide, App Info, delete |
| Long-press empty space | Edit Home Screen, Settings, wallpaper |
| Edit Home Screen | Icons jiggle: drag to reorder, drag across pages, drag into the dock, tap − to remove |

## Settings

Open from the long-press menu or the ⚙ button on the App Library page.

- **Appearance:** icon style (Clear, Tinted, Dark, Light, Color), accent color, glass on/off, tinted widgets, wallpaper dimming
- **Home Screen:** icon size, app names, 4 or 5 columns, badges, hidden apps, reset layout
- **Widgets:** turn each widget on or off, restore removed ones
- **Animations:** on/off, speed, app-opening style (Zoom, Fade, Slide Up), page swipe (Depth, Flat, Cube, Zoom), blur, haptics
- **Gestures:** swipe down, swipe up, double-tap to lock

Some features need a permission you grant once in Android settings:
notification access (badges, Now Playing), usage access (all recent apps in the
App Switcher), and the "Luncher Gestures" accessibility helper (system Recents,
double-tap to lock).

## Build

GitHub Actions builds the APK on every push (`.github/workflows/build.yml`).
Locally: `./gradlew assembleDebug` (requires the Android SDK).
