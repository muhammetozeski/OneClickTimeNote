# Timestamp Logger Widget

![Platform](https://img.shields.io/badge/platform-Android-3DDC84)
![Min SDK](https://img.shields.io/badge/minSdk-23-blue)
![Target SDK](https://img.shields.io/badge/targetSdk-29-blue)
![Language](https://img.shields.io/badge/language-Java-orange)
![APK](https://img.shields.io/badge/APK-8.5%20KB-lightgrey)

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
| Tap the widget | One line is appended to the file and a toast shows the line that was written: `2026.08.27 18.03.18` |
| Tap again within 4 seconds | Ignored. Nothing is written and no toast is shown. |
| The write fails | A toast says so and the tap stays retryable — the debounce is only armed after a successful write. |
| Cancel the dialog | The widget is not placed at all. |
| Remove the widget | Its stored file reference, label and debounce entry are deleted. |

Place the widget more than once to log into more than one file. Each instance keeps its own
file and its own 4 second window; they never block each other.

### Line format

`yyyy.MM.dd HH.mm.ss` formatted with `Locale.US`, terminated with CRLF so the file opens
cleanly in Windows editors as well.

```
2026.08.27 18.03.18
2026.08.27 18.11.02
2026.08.27 19.44.57
```

### What it deliberately does not do

- No launcher icon and no app drawer entry — there is no `MAIN`/`LAUNCHER` activity.
- No service, no alarm, no boot receiver, and `updatePeriodMillis="0"` in the widget
  metadata, so the system never wakes the app up on its own.
- `cache` and `code_cache` are wiped after every tap and when the setup dialog closes, so the
  app leaves nothing behind in *Settings → Storage*.
- No third party library at all: plain `android.*`, zero dependencies.

---

## How it works

| Component | Role |
|---|---|
| `TimestampWidget` (`AppWidgetProvider`) | Renders the widget, receives the tap broadcast, applies the debounce, appends the line, wipes the cache. |
| `SetupActivity` | Widget configuration activity with a translucent theme and no UI of its own. It launches `ACTION_CREATE_DOCUMENT`, takes a persistable URI permission on the result, stores it and finishes. |
| `res/layout/widget.xml` | A single `TextView`: `#B3000000` background, centred white label. |
| `res/xml/widget_info.xml` | Widget metadata: 72×72 dp minimum, resizable, `updatePeriodMillis="0"`, points at `SetupActivity` as the configuration activity. |

There is no `res/values/strings.xml`. The two labels live as literals in the manifest and the
four toast texts as literals in the sources, which keeps them out of `resources.arsc`. Because
those literals are Turkish, `build.gradle` pins `compileOptions.encoding` to UTF-8 so javac
does not fall back to the Windows default charset.

### Stored state

One `SharedPreferences` file (`w`), three short keys per widget id:

| Key | Value |
|---|---|
| `u<id>` | Document URI as a string |
| `n<id>` | Label shown on the widget |
| `t<id>` | Timestamp of the last accepted tap — the whole debounce state, one `long` |

The debounce is written with `commit()` rather than `apply()`, because the process is expected
to be reclaimed right after the broadcast returns and an asynchronous write could be lost.
The `t<id>` entry is only updated after a successful write, so a failed tap can be retried
immediately instead of being silently swallowed for four seconds.

### Writing

`ContentResolver.openOutputStream(uri, "wa")` — append mode, so the existing content is never
read back into memory no matter how long the file gets. Any failure is logged under the `TsW`
tag and surfaced as a toast.

### Tap delivery

Each widget gets its own `PendingIntent.getBroadcast` with the widget id as the request code
and an explicit component, so no implicit broadcast is involved and widgets never share a
pending intent. `FLAG_IMMUTABLE` is added on API 23 and above.

---

## Size

Signed APK: **8,550 bytes**, no dependencies, three classes in `classes.dex` (the two
components plus one R8 generated helper). Where those bytes go:

| Entry | Bytes (deflated) |
|---|---|
| `classes.dex` | 3,648 |
| `AndroidManifest.xml` | 988 |
| `resources.arsc` | 884 (stored, not deflated) |
| `META-INF` signature | 1,259 |
| `res/layout` + `res/xml` | 672 |

And inside the 7,000 byte uncompressed dex, read from its `map_list`:

| Section | Bytes |
|---|---|
| String data + string ids | 2,806 |
| Bytecode | 1,783 |
| Method / proto / type reference tables | 1,870 |
| R8 build marker | 143 |

Only 67 bytes of the string data are this app's own texts. The rest is the price of the API
surface: every distinct framework method the code calls writes its class descriptor, its name
and its signature into the dex. This app makes 86 such calls where an empty activity makes 12,
so the reference tables and their names, not the logic, are what set the floor here.

The manifest sets `android:debuggable="true"`, `vmSafeMode="true"`,
`hardwareAccelerated="false"` and `allowBackup="false"`. `debuggable` is the one that matters
for installed size: Android then runs the app straight from the APK (`status=run-from-apk`) and
never produces `oat/base.odex`, `base.vdex` or `base.art`, so the installed size stays close to
the APK size. The trade off is that the app is debuggable, which is fine for personal use and
not appropriate for store distribution.

Verify on a device with:

```
adb shell dumpsys package com.timestamp.widget | findstr status=
```

---

## Building

The build is fully offline and expects a portable toolchain (JDK 17, Android SDK with
build‑tools 30.0.3 and platform android‑33, Gradle 8.0, and a keystore) in the folder that
`$API_DIR` at the top of `Derle.ps1` points at.

```powershell
pwsh -File .\Derle.ps1
```

The signed APK is written to `Publish\TimestampLoggerWidget.apk`.

| Command | targetSdk | Signature |
|---|---|---|
| `pwsh -File .\Derle.ps1` | 29 | v1 only (8,550 bytes, measured) |
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
adb install -r "Publish\TimestampLoggerWidget.apk"
```

Then add the widget from the launcher's widget picker: long press the home screen →
**Widgets** → **Zaman Damgası**.

## Project layout

```
TimestampLoggerWidget\
├─ Derle.ps1                  build + zipalign + sign, output goes to Publish\
├─ Publish\                   signed APK (not versioned)
└─ Proje\
   ├─ build.gradle            applicationId, minSdk 23, R8 + resource shrinking
   ├─ settings.gradle
   └─ src\main\
      ├─ AndroidManifest.xml
      ├─ java\com\timestamp\widget\
      │  ├─ TimestampWidget.java
      │  └─ SetupActivity.java
      └─ res\
         ├─ layout\widget.xml
         └─ xml\widget_info.xml
```
