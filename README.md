# One Click Time Note

![Platform](https://img.shields.io/badge/platform-Android-3DDC84)
![Min SDK](https://img.shields.io/badge/minSdk-26-blue)
![Language](https://img.shields.io/badge/language-Java-orange)
![APK](https://img.shields.io/badge/APK-9.7%20KB-lightgrey)

An Android home screen widget that appends the current date and time to a text file.
Tap it, one line is written. That is all it does.

There is no launcher activity, so the app never appears in the app drawer — the widget is the
whole application.

## Behaviour

- Dropping the widget opens the system *create document* dialog. You name a `.txt` file and
  pick a folder.
- The widget then shows that name, white on a semi transparent black plate.
- Tapping it appends one line, buzzes for 40 ms and shows a toast of what was written:
  `a.txt 2026.08.27 20.15.19`
- A tap within 4 seconds of an accepted one is ignored. Each widget keeps its own file and its
  own window, so you can place several.
- Nothing reports success before `close()` has returned on the output stream, so a buzz always
  means the bytes are on disk. A failed write says so and stays retryable.

Line format is `yyyy.MM.dd HH.mm.ss` with `Locale.US`, CRLF terminated.

## What it does not do

- No service, no alarm, no boot receiver, `updatePeriodMillis="0"` — nothing runs in the
  background.
- `cache` and `code_cache` are wiped after every tap, so nothing accumulates in
  *Settings → Storage*.
- No dependencies, plain `android.*`. `VIBRATE` is the only permission.

## Building

Fully offline. `Derle.ps1` expects a portable toolchain (JDK 17, Android SDK with build‑tools
30.0.3 and platform android‑33, Gradle 8.0, keystore) in the folder its `$API_DIR` points at.

```powershell
pwsh -File .\Derle.ps1
```

Output: `Publish\OneClickTimeNote.apk` — 9,934 bytes, targetSdk 29, v1 signature, installs on
every Android version. `-TargetSdk 33` or `-V2` switch to a v1+v2+v3 signature, which costs
about 8 KB of alignment padding.

The manifest sets `android:debuggable="true"`, which is what keeps the *installed* size at the
APK size: Android runs the app straight from the APK (`status=run-from-apk`) instead of
generating `oat/base.odex`, `base.vdex` and `base.art`. It also makes `lintVitalRelease` fail,
so the script passes `-x lintVitalRelease`. Fine for personal use, not for store distribution.

## Installing

```powershell
adb install -r "Publish\OneClickTimeNote.apk"
```

Then long press the home screen → **Widgets** → **One Click Time Note**.

If the toast does not appear on MIUI/HyperOS, notifications are off for the app. The app
targets SDK 29 so it cannot ask at runtime — turn them on in Settings, or:

```
adb shell pm grant com.oneclick.timenote android.permission.POST_NOTIFICATIONS
```

The buzz does not depend on that permission. It is sent as `AudioAttributes.USAGE_ALARM`,
because a plain `vibrate(long)` goes out as `USAGE_TOUCH`, which the platform drops from a
background process, and `USAGE_NOTIFICATION` is dropped by MIUI when notification vibration is
off.

## Layout

```
OneClickTimeNote\
├─ Derle.ps1                  build + zipalign + sign
├─ Publish\                   signed APK (not versioned)
└─ Proje\src\main\
   ├─ AndroidManifest.xml
   ├─ java\com\oneclick\timenote\
   │  ├─ TimestampWidget.java   render, tap, debounce, append, buzz, cache wipe
   │  └─ SetupActivity.java     translucent config activity, opens the save dialog
   └─ res\
      ├─ drawable\icon.webp     64x64, quality 20, 806 bytes
      ├─ layout\widget.xml
      └─ xml\widget_info.xml
```

State lives in one `SharedPreferences` file, three short keys per widget id: the document URI,
the label, and the timestamp of the last accepted tap.

## Authors

- **[muhammetozeski](https://github.com/muhammetozeski)** — owner, requirements and direction 😊
- **Claude (Opus 5, via [Claude Code](https://claude.com/claude-code))** — implementation,
  APK size analysis, on-device debugging
