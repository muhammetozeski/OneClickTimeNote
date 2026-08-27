# One Click Time Note

![Platform](https://img.shields.io/badge/platform-Android-3DDC84)
![Min SDK](https://img.shields.io/badge/minSdk-26-blue)
![Target SDK](https://img.shields.io/badge/targetSdk-29-blue)
![Language](https://img.shields.io/badge/language-Java-orange)
![APK](https://img.shields.io/badge/APK-9.7%20KB-lightgrey)

An Android home screen widget that appends the current date and time to a text file.
Tap the widget, one line is written. That is all it does.

The app has no launcher activity, so it never shows up in the app drawer — the widget is
the entire application.

---

## Behaviour

| Step | What happens |
|---|---|
| Drop the widget on the home screen | The system *create document* dialog (Storage Access Framework) opens. You type a name and pick a folder; the `.txt` file is created there. |
| Name entered | The widget shows that name, white text on a semi transparent black plate. |
| Tap the widget | One line is appended, the phone buzzes for 40 ms and a toast shows what was written: `a.txt 2026.08.27 20.15.19` |
| Tap again within 4 seconds | Ignored. Nothing is written, nothing buzzes. |
| The write fails | A toast says so and the tap stays retryable — the debounce is only armed after a successful write. |
| Cancel the dialog | The widget is not placed at all. |
| Remove the widget | Its stored file reference, label and debounce entry are deleted. |

Place the widget more than once to log into more than one file. Each instance keeps its own
file and its own 4 second window; they never block each other.

Nothing reports success before `close()` has returned on the output stream. Up to that point
the bytes may still be sitting in a buffer, so a buzz or a toast then would be a lie.

### Line format

`yyyy.MM.dd HH.mm.ss` formatted with `Locale.US`, terminated with CRLF so the file opens
cleanly in Windows editors as well.

```
2026.08.27 20.13.59
2026.08.27 20.14.57
2026.08.27 20.15.19
```

### What it deliberately does not do

- No launcher icon and no app drawer entry — there is no `MAIN`/`LAUNCHER` activity.
- No service, no alarm, no boot receiver, and `updatePeriodMillis="0"` in the widget
  metadata, so the system never wakes the app up on its own.
- `cache` and `code_cache` are wiped after every tap and when the setup dialog closes, so the
  app leaves nothing behind in *Settings → Storage*.
- No third party library at all: plain `android.*`, zero dependencies. `VIBRATE` is the only
  permission it declares.

---

## How it works

| Component | Role |
|---|---|
| `TimestampWidget` (`AppWidgetProvider`) | Renders the widget, receives the tap broadcast, applies the debounce, appends the line, buzzes, wipes the cache. |
| `SetupActivity` | Widget configuration activity with a translucent theme and no UI of its own. It launches `ACTION_CREATE_DOCUMENT`, takes a persistable URI permission on the result, stores it and finishes. |
| `res/layout/widget.xml` | A single `TextView`: `#B3000000` background, centred white label, no padding and no margin so the widget can be resized down to one launcher cell. |
| `res/xml/widget_info.xml` | Widget metadata: 40×40 dp minimum, resizable, `updatePeriodMillis="0"`, points at `SetupActivity` as the configuration activity. |
| `res/drawable/icon.webp` | 64×64 WebP at quality 20, 806 bytes. Shown in the widget picker. |

There is no `res/values/strings.xml`. The two labels live as literals in the manifest and the
four toast texts as literals in the sources, which keeps them out of `resources.arsc`. Because
those literals are Turkish, `build.gradle` pins `compileOptions.encoding` to UTF-8 so javac
does not fall back to the Windows default charset.

### Stored state

One `SharedPreferences` file (`w`), three short keys per widget id:

| Key | Value |
|---|---|
| `u<id>` | Document URI as a string |
| `n<id>` | Label shown on the widget, extension included |
| `t<id>` | Timestamp of the last accepted tap — the whole debounce state, one `long` |

The debounce is written with `commit()` rather than `apply()`, because the process is expected
to be reclaimed right after the broadcast returns and an asynchronous write could be lost.

### Writing

`ContentResolver.openOutputStream(uri, "wa")` — append mode, so the existing content is never
read back into memory no matter how long the file gets.

### Tap delivery

Each widget gets its own `PendingIntent.getBroadcast` with the widget id as the request code
and an explicit component, so no implicit broadcast is involved and widgets never share a
pending intent.

### Why the buzz is an alarm vibration

A plain `Vibrator.vibrate(long)` goes out as `VibrationAttributes` `USAGE_TOUCH`, and the
platform drops that from a background process — which a widget tap always is:

```
VibratorManagerService: Ignoring incoming vibration as process with uid=... is background,
attrs=VibrationAttributes{mUsage=TOUCH}
```

`USAGE_NOTIFICATION` clears that check but runs into a vendor rule on MIUI/HyperOS:

```
VibratorManagerServiceImpl: Vibrate ignored, not vibrating for ringtones or notify for MIUI
... ended with status IGNORED_RINGTONE_OR_NOTIFY_MIUI
```

`USAGE_ALARM` passes both, and the device then logs `Vibrator on for timeoutMs: 40`. The
vibration is sent with `AudioAttributes` rather than `VibrationAttributes` so it works below
API 33.

### If the toast does not appear

On MIUI/HyperOS toasts are suppressed while the app's notification permission is off. The app
targets SDK 29, so it cannot ask for `POST_NOTIFICATIONS` at runtime; turn notifications on for
the app in Settings, or grant it once over adb:

```
adb shell pm grant com.oneclick.timenote android.permission.POST_NOTIFICATIONS
```

The buzz does not depend on that permission, which is why it is there.

---

## Size

Signed APK: **9,934 bytes**, no dependencies, two classes in `classes.dex`. Where those bytes
go:

| Entry | Bytes (deflated) |
|---|---|
| `classes.dex` | 3,807 |
| `AndroidManifest.xml` | 1,065 |
| `resources.arsc` | 1,060 |
| `META-INF` signature | 1,342 |
| `res/drawable/icon.webp` | 806 (stored, already compressed) |
| `res/layout` + `res/xml` | 656 |

And inside the 7,116 byte uncompressed dex, read from its `map_list`:

| Section | Bytes |
|---|---|
| String data + string ids | 2,979 |
| Bytecode | 1,832 |
| Method / prototype / type / field reference tables | 1,918 |

Only about 70 bytes of the string data are this app's own texts. The rest is the price of the
API surface: every distinct framework method the code calls writes its class descriptor, its
name and its signature into the dex. This app makes 88 such calls where an empty activity makes
12, so the reference tables and their names, not the logic, are what set the floor here.

The manifest sets `android:debuggable="true"`, `vmSafeMode="true"`,
`hardwareAccelerated="false"` and `allowBackup="false"`. `debuggable` is the one that matters
for installed size: Android then runs the app straight from the APK (`status=run-from-apk`) and
never produces `oat/base.odex`, `base.vdex` or `base.art`, so the installed size stays close to
the APK size. Measured on a Redmi running Android 15: `base.apk` is the only file under the
install path, `oat/` is empty, and the app's data directory holds an empty `shared_prefs` with
no `cache` or `code_cache` beside it. The trade off is that the app is debuggable, which is
fine for personal use and not appropriate for store distribution.

Verify on a device with:

```
adb shell dumpsys package com.oneclick.timenote | findstr status=
```

---

## Building

The build is fully offline and expects a portable toolchain (JDK 17, Android SDK with
build‑tools 30.0.3 and platform android‑33, Gradle 8.0, and a keystore) in the folder that
`$API_DIR` at the top of `Derle.ps1` points at.

```powershell
pwsh -File .\Derle.ps1
```

The signed APK is written to `Publish\OneClickTimeNote.apk`.

| Command | targetSdk | Signature |
|---|---|---|
| `pwsh -File .\Derle.ps1` | 29 | v1 only (9,934 bytes, measured) |
| `pwsh -File .\Derle.ps1 -TargetSdk 33` | 33 | v1+v2+v3 |
| `pwsh -File .\Derle.ps1 -V2` | 29 | v1+v2+v3 |

targetSdk stays at 29 by default. Android 11 rejects an APK with `targetSdk >= 30` that has no
v2 signature, and turning v2 on adds roughly 8 KB of alignment padding that `apksigner` cannot
be told to skip. At targetSdk 29 a v1 only signature installs on every Android version
including 11 and later.

`android:debuggable="true"` makes `lintVitalRelease` fail on a release build, so the script
runs Gradle with `-x lintVitalRelease`.

## Installing

```powershell
adb install -r "Publish\OneClickTimeNote.apk"
```

Then add the widget from the launcher's widget picker: long press the home screen →
**Widgets** → **One Click Time Note**.

## Project layout

```
OneClickTimeNote\
├─ Derle.ps1                  build + zipalign + sign, output goes to Publish\
├─ Publish\                   signed APK (not versioned)
└─ Proje\
   ├─ build.gradle            applicationId, minSdk 26, R8 + resource shrinking
   ├─ settings.gradle
   └─ src\main\
      ├─ AndroidManifest.xml
      ├─ java\com\oneclick\timenote\
      │  ├─ TimestampWidget.java
      │  └─ SetupActivity.java
      └─ res\
         ├─ drawable\icon.webp
         ├─ layout\widget.xml
         └─ xml\widget_info.xml
```
