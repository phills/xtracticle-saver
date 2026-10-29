# Xtracticle Saver — developer notes

Implementation, build, debug and test details. The user-facing README is
[`README.md`](README.md).

## How it works

Three moving parts, all in [`MainActivity.kt`](app/src/main/java/com/xtracticle/share/MainActivity.kt):

> **Entry paths:** opened via *Share* → the flow below runs. Opened from the launcher (no
> shared link) → a short themed instructions page is shown instead of a blank WebView. Both
> injected JS blobs are gated on `https://xtracticle.com` so they never run against that help
> page.

### 1. Share → URL → site auto-extracts

`ACTION_SEND` (`text/plain`, `text/*`, `application/octet-stream`) → pull the first
`https?://…` out of the shared text → load:

```
https://xtracticle.com/?url=<url-encoded link>
```

`?url=` is xtracticle's own bookmarklet parameter. The bundle reads `["url","link","text","title"]`
from the query string and calls its extract function automatically — so the site fills its own
link box and clicks **Extract** for us. Tracking params (`?a=…&s=…`) are harmless; the site's
URL matcher isn't anchored to the end of the string.

### 2. Decide a format, then click

Injected `DRIVER_JS` polls the page and:

1. waits for a **format button** to appear (they only render once extraction finished),
2. waits for the **preview to actually resolve** (see trap #2 below),
3. picks a format (see "Default format setting" below),
4. clicks the site's own format button.

### 3. Catch the file and save it

The site builds files **client-side** as a `Blob` and downloads via `<a download>` + `createObjectURL`.
A plain WebView `DownloadListener` gets a `blob:` URL that Java cannot read — the file would vanish.

So injected `BLOB_HOOK_JS` patches `HTMLAnchorElement.prototype.click`: when an anchor has a
`download` attribute and a `blob:`/`data:` href, it fetches the blob, base64s it, and hands it to
the `AndroidBridge.onFile()` `JavascriptInterface`, which writes to `Downloads` via `MediaStore`
(Android 10+) or the legacy public directory (Android 8–9, `WRITE_EXTERNAL_STORAGE`).

## The two traps (why naive versions fail)

### Trap 1 — the avatar is always a real `<img>`

The preview **always** renders the author avatar:

```js
M.author.avatar && <img src={M.author.avatar.replace("_normal", "_bigger")} … />
// → pbs.twimg.com/profile_images/…
```

So a naive `any <img> in #xt-preview → PDF` returns **true for every post** → always PDF.

**Rule:** count `img`/`video` **excluding** `/profile_images|profile_banners|emoji|twemoji/`.
Count > 0 → PDF; else (avatar-only, or text/emoji only) → Markdown.

Note `profile_banners/` also lives on `pbs.twimg.com` and must be excluded too, or a
banner-only post falsely becomes a PDF.

### Trap 2 — the preview is a lazy `<Suspense>` chunk

The format buttons render as soon as the extracted **data** object exists. But the preview is a
**separately lazy-loaded chunk** wrapped in `<Suspense>`, showing a `"Loading preview…"`
fallback `<p>` until it resolves — and that fallback contains **zero images**.

v1 counted images the instant it saw a button → always read 0 → **always picked Markdown**,
even for image posts. Reproduced with a post that clearly has a photo
(`x.com/holonabove/status/2115652746443473324`).

**Fix:** `previewReady()` gates the count — only proceed once `#xt-preview` contains an `<img>`
(the avatar renders with the real content) **or** its text is longer than 40 chars
(the fallback is ~16 chars). Until then, keep polling.

> Lesson: the buttons existing does **not** mean the preview exists. They are separate async trees.

## Trap 3 — exported PDFs silently dropped the photo (fixed in v1.1)

Cloudflare in front of `pbs.twimg.com` returns **403/351** to any request carrying a cross-site
`Referer`. xtracticle builds PDFs with `html2canvas(useCORS:true)`, which creates **fresh
`Image()` objects** inheriting neither `crossorigin` nor `referrerpolicy`, so they go out with
the page's default `Referer` and the canvas draw is tainted → image missing, no error surfaced.
The site's own preview images survive because React sets `referrerPolicy="no-referrer"` on them,
and its ZIP exporter fetches with `{referrerPolicy:"no-referrer"}`.

**Fix:** inject `<meta name="referrer" content="no-referrer">` at `onPageStarted`
(`REFERRER_FIX_JS`, with a retry loop for early injection). Verified on-device via CDP:
before = `FAIL` (Origin+Referer), after = `OK 200 + ACAO`. Real share run: 408 KB / two blank
pages → 747 KB with the real photo, zero CORS errors.

## Default format setting

The start page (launcher) offers a picker: **Smart**, Markdown, PDF, **EPUB / Kindle**,
**ZIP + images**, **Text**. Stored in `SharedPreferences` (`PREFS_NAME="xtracticle_prefs"`,
`KEY_FORMAT="format"`, default `FORMAT_SMART="smart"`, `FORMAT_IDS` = the five site ids).
`Bridge.setFormat()` rejects unknown ids and uses `commit()` so the choice survives an
immediate app kill. `DRIVER_JS` reads it via `AndroidBridge.getFormat()` once per share and
clicks the matching site button; `smart` falls back to the MD-vs-PDF rule above.

## Format buttons (site-side)

The buttons carry **no IDs**. They are produced by a factory in a stable order and located by
exact trimmed button text:

| format | order | matched text (English) |
|--------|-------|------------------------|
| `md`   | 0     | `Markdown`             |
| `pdf`  | 1     | `PDF`                  |
| `epub` | 2     | `EPUB / Kindle`        |
| `zip`  | 3     | `ZIP + images`         |
| `txt`  | 4     | `Text`                 |

**Known limitation:** the app loads the English site, so it matches English labels. If the site
is ever rendered in another language, the click targets would need that language's labels.
Other stable selectors: preview container `#xt-preview`, link input `#xt-url` (React-controlled).

## Build

Any JDK 17+ and an Android SDK (API 34). The Gradle wrapper is included, so no Gradle
install needed:

```bash
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

`local.properties` is gitignored — point `sdk.dir` at your SDK, or let Android Studio create it.

Requires `platforms;android-34` and `build-tools;34.0.0` (Android Studio installs these on
first sync, or grab them with `sdkmanager "platforms;android-34" "build-tools;34.0.0"`).

**Build notes:**

- `MediaStore` is `android.provider.MediaStore`, **not** `android.content.MediaStore`.
- **Project shape:** single module, Kotlin, framework-only APIs (WebView, MediaStore) — **zero
  external dependencies**. `minSdk 26`, `targetSdk 34`.

Toolchain on this box: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk`,
`ANDROID_HOME=/home/pls/android-sdk`, Gradle 8.9 at `/home/pls/gradle-8.9`.

## File map

```
app/src/main/AndroidManifest.xml                     SEND filters + launcher
app/src/main/java/com/xtracticle/share/MainActivity.kt   all logic: intent → WebView → JS → save
app/src/main/res/values/{strings,themes,colors}.xml  label "Xtracticle Saver", dark status bar
app/src/main/res/drawable/ic_launcher_foreground.xml adaptive icon (down-arrow)
```

Two injected JS blobs live in `MainActivity`'s companion object:
`REFERRER_FIX_JS` (installed `onPageStarted`, before the site's download code runs),
`BLOB_HOOK_JS` (installed `onPageStarted`), and `DRIVER_JS` (installed `onPageFinished`).

## If it misbehaves

- **Made a `.md` instead of a `.pdf` on an image post** → the preview-readiness gate or the
  avatar exclusion regressed. Check `previewReady()` and the `mediaCount()` regex.
- **Made nothing at all** → the blob-capture hook. Confirm `BLOB_HOOK_JS` is installed before
  the click, and that the anchor had a `download` attribute.
- **Toast says "Timed out waiting for the article to load"** → `?url=` didn't extract; the
  format buttons never rendered.
- **Toast says "Timed out waiting for the preview"** → buttons rendered but the Suspense chunk
  never resolved (slow network, or the site's structure changed).
- **PDF missing the photo** → the referrer meta isn't being injected, or the site changed its
  request shape. Re-run the CDP probe (`/tmp/opencode/cdp_fix.py`) on-device.

Debug a live WebView with `adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>`
and `chrome://inspect` if needed (socket name is PID-suffixed; re-forward on every app restart).

## Tampermonkey userscript (desktop)

A desktop companion lives at [`xtracticle-saver.user.js`](xtracticle-saver.user.js). It drives
the same flow inside a real browser (Firefox / Chrome / Edge / Opera on desktop) via Tampermonkey:
referrer fix, format selection, and the MD-vs-PDF decision. Desktop browsers download `blob:`
URLs natively, so no blob-capture hook is needed. Install directions are in [`README.md`](README.md).

## Status

Personal-use tool, debug-signed. Verified against
`x.com/holonabove/status/2110737815919038646` (image post → PDF with embedded photo).

The APK is published on the **[Releases](../../releases)** page, not committed to the repo —
binaries don't belong in git history.

Not affiliated with xtracticle.com or X Corp. Public content only.