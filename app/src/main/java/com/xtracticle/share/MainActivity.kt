package com.xtracticle.share

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.provider.MediaStore
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream

/**
 * Thin driver over xtracticle.com.
 *
 * It does NOT reimplement extraction, PDF or Markdown generation. It:
 *   1. loads xtracticle.com/?url=<shared link>  (the site's own bookmarklet entry point,
 *      which auto-fills its link box and clicks Extract for us),
 *   2. waits for the preview to resolve (the site's own PDF/EPUB export reads it), then
 *      picks a format: whatever the user set as their default on the start page, or —
 *      when that default is "smart" — reads ONE thing from the DOM: is there any real
 *      content image (avatar is excluded) -> decide Markdown vs PDF,
 *   3. clicks the site's own format button,
 *   4. catches the Blob the site produces (client-side download) and saves it to Downloads.
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = false
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.addJavascriptInterface(Bridge(), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // Only patch the site — the help page has no downloads to catch.
                if (isXtracticle(url)) {
                    view?.evaluateJavascript(REFERRER_FIX_JS, null)
                    view?.evaluateJavascript(BLOB_HOOK_JS, null)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Only drive the site — never the local help page.
                if (isXtracticle(url)) {
                    view?.evaluateJavascript(DRIVER_JS, null)
                }
            }
        }

        handleIntent(intent)
    }

    private fun isXtracticle(url: String?) = url != null && url.startsWith("https://xtracticle.com")

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val shared = extractUrl(intent)
        if (shared == null) {
            // Opened from the launcher (not a share): show help instead of a blank
            // WebView. The neutral base URL keeps this page off xtracticle.com so the
            // site driver never runs against it.
            webView.loadDataWithBaseURL(
                "app://instructions",
                INSTRUCTIONS_HTML,
                "text/html",
                "utf-8",
                null
            )
            return
        }
        // The site reads ?url= (also link/text/title) and auto-extracts on load.
        val target = "https://xtracticle.com/?url=" + java.net.URLEncoder.encode(shared, "UTF-8")
        webView.loadUrl(target)
    }

    private fun extractUrl(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_SEND) return null
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)
            ?: return null
        // Pull the first URL out of whatever the sharing app sent.
        val match = URL_REGEX.find(text)
        return (match?.value ?: text).trim().ifEmpty { null }
    }

    /**
     * Called from page JS when the site has built a file and is downloading it
     * (as a Blob -> <a download>). Java can't read blob: URLs, so JS base64s it here.
     */
    inner class Bridge {
        /**
         * The default output format the user picked on the start page.
         * "smart" means decide per post (PDF if it has real media, Markdown otherwise);
         * anything else is a literal xtracticle format id (md/pdf/epub/zip/txt).
         * Read by DRIVER_JS on every run, so a change applies to the next share.
         */
        @JavascriptInterface
        fun getFormat(): String =
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(KEY_FORMAT, FORMAT_SMART) ?: FORMAT_SMART

        /** Called by the start page's option list. Ignores unknown ids. */
        @JavascriptInterface
        fun setFormat(value: String) {
            if (value != FORMAT_SMART && value !in FORMAT_IDS) return
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(KEY_FORMAT, value)
                .commit()
        }

        @JavascriptInterface
        fun onFile(base64: String, fileName: String, mime: String) {
            try {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                val safe = sanitizeFileName(fileName)
                val saved = saveToDownloads(bytes, safe, mime)
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Saved: $saved", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "Save failed: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        @JavascriptInterface
        fun onError(message: String) {
            runOnUiThread {
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun saveToDownloads(bytes: ByteArray, fileName: String, mime: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Scoped storage: MediaStore, no permission needed.
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Could not create file")
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw IllegalStateException("Could not open output stream")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            return fileName
        } else {
            // Legacy public Downloads dir (WRITE_EXTERNAL_STORAGE held via maxSdkVersion=28).
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, fileName)
            FileOutputStream(file).use { it.write(bytes) }
            MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), arrayOf(mime), null)
            return fileName
        }
    }

    private fun sanitizeFileName(name: String): String {
        var clean = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        if (clean.isEmpty()) clean = "xtracticle_download"
        // Avoid silently overwriting an earlier save.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return clean
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        var candidate = clean
        val dot = clean.lastIndexOf('.')
        val base = if (dot > 0) clean.substring(0, dot) else clean
        val ext = if (dot > 0) clean.substring(dot) else ""
        var n = 1
        while (File(dir, candidate).exists()) {
            candidate = "$base ($n)$ext"
            n++
        }
        return candidate
    }

    companion object {
        private val URL_REGEX = Regex("https?://\\S+")

        // --- Default output format (the start page's setting) --------------
        // Stored in SharedPreferences so it survives restarts. "smart" is the
        // app's own decision rule; the rest are xtracticle.com's format ids.
        private const val PREFS_NAME = "xtracticle_prefs"
        private const val KEY_FORMAT = "format"
        private const val FORMAT_SMART = "smart"
        private val FORMAT_IDS = setOf("md", "pdf", "epub", "zip", "txt")

        // Shown when the app is opened from the launcher instead of via Share.
        // Follows the system light/dark theme; the app UI itself has no chrome,
        // so this is the app's only "home" screen.
        private val INSTRUCTIONS_HTML = """
            <!doctype html>
            <html lang="en">
            <head>
            <meta charset="utf-8" />
            <meta name="viewport" content="width=device-width, initial-scale=1" />
            <title>Xtracticle Saver</title>
            <style>
              :root { color-scheme: light dark; }
              body {
                margin: 0;
                padding: 28px 22px 40px;
                font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
                line-height: 1.55;
                background: Canvas;
                color: CanvasText;
              }
              .wrap { max-width: 34rem; margin: 0 auto; }
              h1 { font-size: 1.35rem; margin: 0 0 .35rem; }
              .sub { margin: 0 0 1.6rem; opacity: .7; }
              h2 { font-size: .8rem; text-transform: uppercase; letter-spacing: .06em;
                   opacity: .55; margin: 1.8rem 0 .7rem; }
              ol { margin: 0; padding-left: 1.25rem; }
              li { margin-bottom: .55rem; }
              .rule { display: flex; gap: .75rem; align-items: baseline;
                      padding: .6rem 0; border-bottom: 1px solid color-mix(in srgb, CanvasText 12%, transparent); }
              .rule:last-child { border-bottom: 0; }
              .tag { font-weight: 600; white-space: nowrap; }
              .note { margin-top: 1.8rem; padding: .85rem 1rem; border-radius: .6rem;
                      background: color-mix(in srgb, CanvasText 7%, transparent);
                      font-size: .92rem; }
              .hint { margin: 0 0 .7rem; opacity: .7; font-size: .92rem; }
              .opt { display: block; width: 100%; text-align: left; font: inherit;
                     color: inherit; cursor: pointer; background: transparent;
                     border: 1px solid color-mix(in srgb, CanvasText 18%, transparent);
                     border-radius: .6rem; padding: .6rem .8rem; margin-bottom: .45rem; }
              .opt-t { display: block; font-weight: 600; }
              .opt-d { display: block; opacity: .65; font-size: .88rem; margin-top: .1rem; }
              .opt.on { border-color: Highlight;
                        background: color-mix(in srgb, Highlight 14%, transparent); }
            </style>
            </head>
            <body>
            <div class="wrap">
              <h1>Xtracticle Saver</h1>
              <p class="sub">Share an X post, get a file. That's it.</p>

              <h2>How to use it</h2>
              <ol>
                <li>On any X post, tap <strong>Share</strong>.</li>
                <li>Pick <strong>Xtracticle Saver</strong> from the list.</li>
                <li>Wait a moment. The file is saved to your <strong>Downloads</strong> — you'll see a confirmation when it's done.</li>
              </ol>

              <h2>Default format</h2>
              <p class="hint">Tap what you'd like. It applies to every share until you change it.</p>
              <div id="opts">
                <button type="button" class="opt" data-v="smart">
                  <span class="opt-t">Smart</span>
                  <span class="opt-d">PDF for posts with a photo or video, Markdown for text-only posts.</span>
                </button>
                <button type="button" class="opt" data-v="md">
                  <span class="opt-t">Markdown</span>
                  <span class="opt-d">Always a .md file.</span>
                </button>
                <button type="button" class="opt" data-v="pdf">
                  <span class="opt-t">PDF</span>
                  <span class="opt-d">Always a .pdf file.</span>
                </button>
                <button type="button" class="opt" data-v="epub">
                  <span class="opt-t">EPUB / Kindle</span>
                  <span class="opt-d">For reading on a Kindle or e-reader.</span>
                </button>
                <button type="button" class="opt" data-v="zip">
                  <span class="opt-t">ZIP + images</span>
                  <span class="opt-d">Markdown plus the photos, zipped together.</span>
                </button>
                <button type="button" class="opt" data-v="txt">
                  <span class="opt-t">Text</span>
                  <span class="opt-d">Plain text, no formatting.</span>
                </button>
              </div>

              <p class="note">
                Files are saved straight to <strong>Downloads</strong>. You don't need to pick a
                folder. If nothing appears, the post may be private or deleted.
              </p>
            </div>
            <script>
              (function () {
                var btns = document.querySelectorAll('.opt');
                var cur = 'smart';
                try { cur = AndroidBridge.getFormat() || 'smart'; } catch (e) {}

                function mark() {
                  for (var i = 0; i < btns.length; i++) {
                    var on = btns[i].getAttribute('data-v') === cur;
                    btns[i].className = on ? 'opt on' : 'opt';
                  }
                }

                for (var i = 0; i < btns.length; i++) {
                  btns[i].addEventListener('click', function () {
                    cur = this.getAttribute('data-v');
                    try { AndroidBridge.setFormat(cur); } catch (e) {}
                    mark();
                  });
                }
                mark();
              })();
            </script>
            </body>
            </html>
        """

        // --- Injected JS -------------------------------------------------
        // Stop the exported PDF from silently dropping the post's photos.
        //
        // xtracticle builds the PDF with html2canvas (useCORS:true), which
        // creates FRESH Image() objects. Those inherit neither the preview's
        // crossorigin nor its referrerPolicy, so they go out with the page's
        // default Referer. Cloudflare in front of pbs.twimg.com answers 351/403
        // to any request carrying a cross-site Referer, and with no
        // Access-Control-Allow-Origin the canvas draw is tainted and the image
        // never lands in the PDF. The photo is simply missing — no error surfaces.
        //
        // A document-level "no-referrer" policy makes those fresh images load
        // (verified on-device: every pbs request 200 + ACAO, PDF grew from
        // 408KB with two blank pages to 747KB with the real photo).
        // This is the same policy the site itself already uses — React sets
        // referrerPolicy="no-referrer" on the preview images, and its ZIP
        // exporter fetches with {referrerPolicy:"no-referrer"}.
        private val REFERRER_FIX_JS = """
            (function () {
              function install() {
                var head = document.head ||
                           document.getElementsByTagName('head')[0] ||
                           document.documentElement;
                if (!head) return false;   // not parsed yet — retry below
                var m = document.querySelector('meta[name="referrer"]');
                if (!m) {
                  m = document.createElement('meta');
                  m.setAttribute('name', 'referrer');
                  head.appendChild(m);
                }
                m.setAttribute('content', 'no-referrer');
                return true;
              }
              if (install()) return;
              // onPageStarted can fire before <head> exists.
              var n = 0;
              var t = setInterval(function () {
                if (install() || ++n > 60) clearInterval(t);
              }, 50);
            })();
        """

        // The site builds files client-side (Blob + <a download>). Patch the anchor
        // click so a blob download is captured as base64 and handed to the app
        // instead of vanishing. Does not touch any site logic.
        private val BLOB_HOOK_JS = """
            (function () {
              if (window.__xt_hooked) return;
              window.__xt_hooked = true;
              var orig = HTMLAnchorElement.prototype.click;
              HTMLAnchorElement.prototype.click = function () {
                var a = this;
                var dl = a.getAttribute('download');
                var href = a.getAttribute('href') || '';
                if (dl && (href.indexOf('blob:') === 0 || href.indexOf('data:') === 0)) {
                  fetch(href).then(function (r) { return r.blob(); }).then(function (b) {
                    var fr = new FileReader();
                    fr.onloadend = function () {
                      var res = fr.result || '';
                      var b64 = res.indexOf(',') >= 0 ? res.split(',')[1] : '';
                      AndroidBridge.onFile(b64, dl, b.type || 'application/octet-stream');
                    };
                    fr.readAsDataURL(b);
                  }).catch(function (e) {
                    AndroidBridge.onError('Could not capture download: ' + e);
                  });
                  return;
                }
                return orig.apply(a, arguments);
              };
            })();
        """

        // Wait for the page, pick a format, click the site's own button.
        //
        // The format comes from the start page's setting (AndroidBridge.getFormat()):
        //   "smart" -> decide per post from the preview (PDF if real media, MD otherwise)
        //   md/pdf/epub/zip/txt -> always that one, matching xtracticle's own ids.
        private val DRIVER_JS = """
            (function () {
              if (window.__xt_driven) return;
              window.__xt_driven = true;
              var start = Date.now();

              // xtracticle's format ids -> its exact button labels (English build).
              var LABELS = {
                md: 'Markdown',
                pdf: 'PDF',
                epub: 'EPUB / Kindle',
                zip: 'ZIP + images',
                txt: 'Text'
              };

              var fmt = 'smart';
              try { fmt = AndroidBridge.getFormat() || 'smart'; } catch (e) {}
              var fixed = (fmt !== 'smart' && !!LABELS[fmt]);

              // Real content media. The author avatar (profile_images/profile_banners)
              // always renders as an <img>, so it must be ignored or every post would
              // look like it has an image.
              function mediaCount() {
                var pv = document.getElementById('xt-preview');
                if (!pv) return 0;
                var n = 0;
                var nodes = pv.querySelectorAll('img, video');
                for (var i = 0; i < nodes.length; i++) {
                  var src = (nodes[i].getAttribute('src') || '') + ' ' +
                            (nodes[i].getAttribute('poster') || '');
                  if (/profile_images|profile_banners|emoji|twemoji/i.test(src)) continue;
                  n++;
                }
                return n;
              }

              // The preview is a lazy <Suspense> chunk: the format buttons can
              // render while #xt-preview still shows the "Loading preview…"
              // fallback, which has NO images yet. Counting images before the
              // chunk resolves reads 0 and wrongly picks Markdown for image
              // posts. So wait for real preview content first.
              //
              // It also gates epub/pdf directly: xtracticle's export handler does
              // `const Y = Fa()` where Fa() IS #xt-preview, and builds with
              // `T==="epub" && Y ? buildEpub(...) : T==="pdf" && Y && buildPdf(...)`.
              // With a falsy Y the pdf arm is &&-chained with no else, so it saves
              // NOTHING while still logging download_done; and if buildPdf throws,
              // the catch falls through to window.print() — a print dialog instead
              // of a file. md/txt/zip don't read the preview, so they don't wait.
              function previewReady() {
                var pv = document.getElementById('xt-preview');
                if (!pv) return false;
                if (pv.querySelector('img')) return true;          // avatar or content img present
                return (pv.textContent || '').trim().length > 40;  // real content, not the short fallback
              }

              function findBtn(label) {
                var btns = document.querySelectorAll('button');
                for (var i = 0; i < btns.length; i++) {
                  if ((btns[i].textContent || '').trim() === label) return btns[i];
                }
                return null;
              }

              // The chosen format needs the preview resolved before it's safe to
              // click: smart (image count) and epub/pdf (the export reads it).
              function needsPreview() {
                return !fixed || fmt === 'epub' || fmt === 'pdf';
              }

              function step() {
                // Format buttons only render once extraction finished.
                var probe = fixed ? findBtn(LABELS[fmt])
                                  : (findBtn(LABELS.md) || findBtn(LABELS.pdf));
                if (!probe) {
                  if (Date.now() - start < 30000) { setTimeout(step, 400); }
                  else { AndroidBridge.onError('Timed out waiting for the article to load.'); }
                  return;
                }
                if (needsPreview() && !previewReady()) {
                  // Buttons exist but the preview chunk is still suspended —
                  // don't count on it yet, keep waiting for it to resolve.
                  if (Date.now() - start < 30000) { setTimeout(step, 300); }
                  else { AndroidBridge.onError('Timed out waiting for the preview.'); }
                  return;
                }
                var label = fixed ? LABELS[fmt]
                                  : (mediaCount() > 0 ? LABELS.pdf : LABELS.md);
                var btn = findBtn(label);
                if (!btn) {
                  AndroidBridge.onError('Could not find the ' + label + ' button.');
                  return;
                }
                btn.click();
              }

              setTimeout(step, 500);
            })();
        """
    }
}
