# Xtracticle Saver

One tap to save an X post — as **PDF** when it has a real image, **Markdown** when it's
text/emoji only.

Share an X post → pick **Xtracticle Saver** → the file lands in `Downloads`. That's it.

> A **thin driver**, not a rebuild of [xtracticle.com](https://xtracticle.com). It loads the
> site's own page, clicks the site's own buttons, and catches the file the site already
> produced. No extraction, PDF or Markdown logic is reimplemented here.

---

## Install — Android app

Grab the APK from the latest **[Release](../../releases)** and install it:

```bash
adb install -r Xtracticle-Saver.apk
```

Or copy the APK to your phone and open it (allow *Install unknown apps* for your file manager /
browser). It then appears in the Android share sheet as **Xtracticle Saver**.

Debug-signed — fine for personal use. Switching to a release signing key later would require
an uninstall first (Android rejects an update whose signing key changed).

### How to use it

1. On any X post, tap **Share**.
2. Pick **Xtracticle Saver**.
3. Wait a moment — the file is saved to **Downloads** and a toast confirms it.

### Default format (optional)

Open the app from your launcher (no shared link) to set a **default output format**:

- **Smart** *(default)* — PDF for posts with a photo/video, Markdown for text-only posts.
- **Markdown**, **PDF**, **EPUB / Kindle**, **ZIP + images**, **Text** — always that format.

Tap your choice; it applies to every share until you change it. You don't need to pick a folder
— files go straight to `Downloads`.

### What you get

- **PDF** — the post has a real photo or video (avatar/banner images don't count).
- **Markdown** — text only, or only an avatar/banner.
- **EPUB / Kindle, ZIP + images, Text** — when you've set that as your default.

If nothing appears in `Downloads`, the post may be private or deleted.

---

## Install — desktop (Tampermonkey userscript)

For Firefox / Chrome / Edge / Opera on desktop, there's a companion userscript:
[`xtracticle-saver.user.js`](xtracticle-saver.user.js).

1. Install the **Tampermonkey** extension for your browser.
2. Open `xtracticle-saver.user.js` (raw) and Tampermonkey will prompt to install it.
3. On any X post page, open the Tampermonkey menu and choose **"Save this post with
   Xtracticle"** — it opens xtracticle.com and drives the rest automatically.
4. Set your default format from the Tampermonkey menu (*Format: Smart / Markdown / PDF / …*).

Desktop browsers download the resulting file natively, so no extra step is needed.

---

## Troubleshooting

- **Made a `.md` instead of a `.pdf` on an image post** — the preview-readiness check or the
  avatar exclusion regressed. See [`DEV_README.md`](DEV_README.md).
- **Made nothing at all** — the blob-capture hook didn't fire. Confirm `BLOB_HOOK_JS` runs
  before the click.
- **"Timed out waiting for the article to load"** — `?url=` didn't extract; format buttons
  never rendered.
- **"Timed out waiting for the preview"** — buttons rendered but the preview chunk never
  resolved (slow network, or the site changed).

Debug a live WebView with `adb forward` + `chrome://inspect` if needed (socket name is
PID-suffixed; re-forward on every app restart).

---

## Status

Personal-use tool, debug-signed. Verified against
`x.com/holonabove/status/2110737815919038646` (image post → PDF with embedded photo).

The APK is published on the **[Releases](../../releases)** page, not committed to the repo —
binaries don't belong in git history.

Developer details (build, file map, the two traps, test notes) live in
[`DEV_README.md`](DEV_README.md).

Not affiliated with xtracticle.com or X Corp. Public content only. See the repo's **About**
section for the short description and topics.