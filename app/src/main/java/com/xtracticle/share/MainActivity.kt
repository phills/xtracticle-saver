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
 *   2. waits for the preview, then reads ONE thing from the DOM: is there any real
 *      content image (avatar is excluded) -> decide Markdown vs PDF,
 *   3. clicks the site's own Markdown or PDF button,
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

              <h2>What you get</h2>
              <div class="rule">
                <span class="tag">PDF</span>
                <span>The post has a photo or video.</span>
              </div>
              <div class="rule">
                <span class="tag">Markdown</span>
                <span>It's text only — words, links and emoji.</span>
              </div>

              <p class="note">
                You don't need to do anything else. The app picks the format for you.
                If nothing appears in Downloads, the post may be private or deleted.
              </p>
            </div>
            </body>
            </html>
        """

        // --- Injected JS -------------------------------------------------
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

        // Wait for the preview, decide Markdown vs PDF, click the site's own button.
        private val DRIVER_JS = """
            (function () {
              if (window.__xt_driven) return;
              window.__xt_driven = true;
              var start = Date.now();

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

              function step() {
                var md = findBtn('Markdown');
                var pdf = findBtn('PDF');
                if (!md && !pdf) {
                  // Format buttons only render once extraction finished.
                  if (Date.now() - start < 30000) { setTimeout(step, 400); }
                  else { AndroidBridge.onError('Timed out waiting for the article to load.'); }
                  return;
                }
                if (!previewReady()) {
                  // Buttons exist but the preview chunk is still suspended —
                  // don't count images yet, keep waiting for it to resolve.
                  if (Date.now() - start < 30000) { setTimeout(step, 300); }
                  else { AndroidBridge.onError('Timed out waiting for the preview.'); }
                  return;
                }
                var hasImage = mediaCount() > 0;
                var btn = hasImage ? (pdf || md) : md;
                if (!btn) {
                  AndroidBridge.onError('Could not find the ' + (hasImage ? 'PDF' : 'Markdown') + ' button.');
                  return;
                }
                btn.click();
              }

              setTimeout(step, 500);
            })();
        """
    }
}
