# Xtracticle Saver

An Android **share target** that turns the manual X → [xtracticle.com](https://xtracticle.com) workflow into one tap.

Share an X post → pick **Xtracticle Saver** from the share sheet → the file lands in `Downloads`.

- Post **has a real image** → saved as **PDF**
- Post is **text / emoji only** (or only an avatar/banner) → saved as **Markdown**

> This is a **thin WebView driver**, not a rebuild of xtracticle. It loads the site's own
> bookmarklet entry point, clicks the site's own buttons, and catches the file the site
> already produced. No extraction, PDF or Markdown logic is reimplemented here.

---

## Install

Grab the APK from the latest **[Release](../../releases)** and install it:

```bash
adb install -r Xtracticle-Saver.apk
```

Or copy the APK to the device and open it (allow *Install unknown apps* for your file manager).
It then appears in the Android share sheet as **Xtracticle Saver**.

Debug-signed — fine for personal use. Switching to a release signing key later would require
an uninstall first (Android rejects an update whose signing key changed).

---

## How it works

Three moving parts, all in [`MainActivity.kt`](app/src/main/java/com/xtracticle/share/MainActivity.kt):

> **Entry paths:** opened via *Share* → the flow below runs. Opened from the launcher (no shared
> link) → a short themed instructions page is shown instead of a blank WebView. Both injected JS
> blobs are gated on `https://xtracticle.com` so they never run against that help page.

### 1. Share → URL → site auto-extracts

`ACTION_SEND` (`text/plain`, `text/*`, `application/octet-stream`) → pull the first `https?://…`
out of the shared text → load:

```
https://xtracticle.com/?url=<url-encoded link>
```

`?url=` is xtracticle's own bookmarklet parameter. The bundle reads `["url","link","text","title"]`
from the query string and calls its extract function automatically — so the site fills its own
link box and clicks **Extract** for us. Tracking params (`?a=…&s=…`) are harmless; the site's
URL matcher isn't anchored to the end of the string.

### 2. Decide Markdown vs PDF, then click

Injected `DRIVER_JS` polls the page and:

1. waits for a **format button** to appear (they only render once extraction finished),
2. waits for the **preview to actually resolve** (see trap #2 below),
3. counts real content images in `#xt-preview`, **excluding** the author avatar/banner,
4. clicks the site's own **Markdown** or **PDF** button.

### 3. Catch the file and save it

The site builds files **client-side** as a `Blob` and downloads via `<a download>` + `createObjectURL`.
A plain WebView `DownloadListener` gets a `blob:` URL that Java cannot read — the file would vanish.

So injected `BLOB_HOOK_JS` patches `HTMLAnchorElement.prototype.click`: when an anchor has a
`download` attribute and a `blob:`/`data:` href, it fetches the blob, base64s it, and hands it to
the `AndroidBridge.onFile()` `JavascriptInterface`, which writes to `Downloads` via `MediaStore`
(Android 10+) or the legacy public directory (Android 8–9, `WRITE_EXTERNAL_STORAGE`).

---

## The two traps (why naive versions fail)

These are the non-obvious facts that make or break the Markdown-vs-PDF decision.

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

### Trap 2 — the preview is a lazy `<Suspense>` chunk (the bug that shipped v1)

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

---

## Format buttons

The buttons carry **no IDs**. They are produced by a factory in a stable order and located by
exact trimmed button text:

| format | order | matched text (English) |
|--------|-------|------------------------|
| `md`   | 0     | `Markdown`             |
| `pdf`  | 1     | `PDF`                  |
| `epub` | 2     | *(unused)*             |
| `zip`  | 3     | *(unused)*             |
| `txt`  | 4     | *(unused)*             |

**Known limitation:** the app loads the English site, so it matches English labels. If the site
is ever rendered in another language, the click targets would need that language's labels.

Other stable selectors: preview container `#xt-preview`, link input `#xt-url` (React-controlled).

---

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

---

## File map

```
app/src/main/AndroidManifest.xml                     SEND filters + launcher
app/src/main/java/com/xtracticle/share/MainActivity.kt   all logic: intent → WebView → JS → save
app/src/main/res/values/{strings,themes,colors}.xml  label "Xtracticle Saver", dark status bar
app/src/main/res/drawable/ic_launcher_foreground.xml adaptive icon (down-arrow)
```

Two injected JS blobs live in `MainActivity`'s companion object:
`BLOB_HOOK_JS` (installed `onPageStarted`, before the site's download code runs) and
`DRIVER_JS` (installed `onPageFinished`).

---

## If it misbehaves

- **Made a `.md` instead of a `.pdf` on an image post** → the preview-readiness gate or the
  avatar exclusion regressed. Check `previewReady()` and the `mediaCount()` regex.
- **Made nothing at all** → the blob-capture hook. Confirm `BLOB_HOOK_JS` is installed before
  the click, and that the anchor had a `download` attribute.
- **Toast says "Timed out waiting for the article to load"** → `?url=` didn't extract; the
  format buttons never rendered.
- **Toast says "Timed out waiting for the preview"** → buttons rendered but the Suspense chunk
  never resolved (slow network, or the site's structure changed).

Debug a live WebView with `adb shell webview_devtools_remote` / `chrome://inspect` if needed.

---

## Status

Personal-use tool, debug-signed. Verified against
`x.com/holonabove/status/2115652746443473324` (image post → PDF).

The APK is published on the **[Releases](../../releases)** page, not committed to the repo —
binaries don't belong in git history.

Not affiliated with xtracticle.com or X Corp. Public content only.
