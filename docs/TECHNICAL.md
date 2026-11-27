# BLRando — technical notes

For developers. The user guide is the [README](../README.md) (in French, like the app).

## Build from the sources

Android 11 or later (minSdk 30). Install [Android Studio](https://developer.android.com/studio) (or the Android SDK
and a JDK 17), plug in the phone with USB debugging on, then from the project folder:

```
gradlew assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Release builds are signed with a key kept outside the repo: a git-ignored `keystore.path` file at the root holds the
path of a `keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`). Without it, the release
build falls back to the debug key.

## Releases and self-update

`gradlew assembleRelease`, then a GitHub release on this repo: tag `vX.Y.Z` (= `versionName`), asset `BLRando.apk`.
The README's download link points to `releases/latest/download/BLRando.apk`.

`AppUpdater` checks `releases/latest` at the first opening of the app each day, never while recording, and only in
release builds (a debug build is signed with another key). It offers the new version and installs it through a
`PackageInstaller` session; if that fails, it opens the release page.

## Why a web page on the watch

Samsung no longer issues the developer certificates a Tizen watch trusts, so a real Tizen app can't be installed
on a Galaxy Watch 2018 any more. Instead, BLRando contains a small web server (`WatchServer`, port 8765).
The watch's Internet browser displays a page (`watch/`, bundled as `assets/watch/`) that polls the phone:

- `/frame`: a 360×360 map image drawn by the phone (`WatchRenderer`, `WatchFeed`), every 5 s while the watch
  screen is on (15 s when the phone battery is low), and at once after a change. Also carries the pixel
  positions of the track ends (car, checkered flag), placed by the watch so they stay upright;
- `/beat`: a small status every 10 s (recording, distance, off track, GPS lost, phone battery, sun and moon
  azimuth and altitude, moon phase, sunset time — `Sky`, SunCalc formulas);
- `/msg?b=<base64 JSON>`: watch to phone messages (zoom, pan, menu commands), handled by `WatchLink`.

The page reloads itself when BLRando is updated (`ver` field). It only answers requests coming through the
hotspot interface (or loopback, for `adb forward` tests), never a shared Wi-Fi.

## Zoom between the two screens

The phone zoom is the watch zoom plus a fixed offset computed from the two screen widths (`zoomOffset`, about +1.1
on a Galaxy S25). "Whole track" gives the same result whichever device asks, and fills both screens.
"Back to position" uses the walking zoom (`CENTER_ZOOM`, 15 on the watch).

## Watch browser quirks (handled in `watch/main.js`)

- The bezel sends real `rotarydetent` events, and also scrolls the page by 120 px per click. Scrolling is only
  used until a real event arrives.
- No compass events: the map turns with the GPS walking direction sent by the phone, so the arrow points up.
- The page is frozen while the watch screen is off. The watch's Wi-Fi sleeps while Bluetooth is connected.
- `?debug` in the address shows which events arrive.
- If the Internet browser is missing, it can be installed over `sdb` (Tizen Studio) from a package with the
  original Samsung signature: `pkgcmd -i -t tpk -p <file> -w`.

## Testing at the desk (debug builds only)

The debug build accepts fake GPS fixes and watch messages over adb (`app/src/debug/`, absent from release builds):

```
adb shell am broadcast -a fr.amandio.blrando.DEBUG_FIX -p fr.amandio.blrando --ef lat 48.5834 --ef lon 7.7458
adb shell am broadcast -a fr.amandio.blrando.DEBUG_MSG -p fr.amandio.blrando --es b64 eyJ0IjoiaGVsbG8ifQ==
```

Without a real watch polling the phone, the watch feed stops after 2 minutes: send `hello` again before other
messages. A fix counts as movement only beyond 3 m, and the walking direction is measured over 20 m.

- `watch-preview/preview.html`: the watch page on a PC, 360×360 round screen. The mouse wheel is the bezel and
  Esc the back key. `#menu` and `#armed` open the menu or the confirmation screen. By default a simulated wrist
  compass turns the map; `preview.html?watch` behaves like the Galaxy Watch 2018 browser instead (no compass).
- `watch-preview/bridge.py`: serves the preview with the phone's real frames over adb, and can walk the phone
  along its reference track.

## Icon

"BL" family icon (gradient, four outward arrows, center), see [iconkit/README.md](../iconkit/README.md).
