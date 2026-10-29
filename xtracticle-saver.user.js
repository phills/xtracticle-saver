// ==UserScript==
// @name         Xtracticle Saver
// @namespace    https://github.com/phills/xtracticle-saver
// @version      1.1
// @description  Drive xtracticle.com to save an X post as PDF / Markdown / EPUB / ZIP / Text. Desktop companion to the Xtracticle Saver Android app.
// @author       phills
// @match        https://xtracticle.com/*
// @match        https://x.com/*
// @match        https://twitter.com/*
// @grant        GM_getValue
// @grant        GM_setValue
// @grant        GM_registerMenuCommand
// @grant        GM_notification
// @run-at       document-start
// ==/UserScript==

(function () {
  'use strict';

  // ---------- constants ----------
  var FORMAT_SMART = 'smart';
  var FORMAT_IDS = ['md', 'pdf', 'epub', 'zip', 'txt'];
  var LABELS = {
    md: 'Markdown',
    pdf: 'PDF',
    epub: 'EPUB / Kindle',
    zip: 'ZIP + images',
    txt: 'Text'
  };

  function getFormat() {
    try { return GM_getValue('format', FORMAT_SMART) || FORMAT_SMART; }
    catch (e) { return FORMAT_SMART; }
  }
  function setFormat(v) {
    if (v !== FORMAT_SMART && FORMAT_IDS.indexOf(v) < 0) return;
    GM_setValue('format', v);
    toast('Default format: ' + (v === FORMAT_SMART ? 'Smart' : LABELS[v]));
  }
  function toast(msg) {
    try { GM_notification({ text: msg, title: 'Xtracticle Saver', timeout: 2500 }); }
    catch (e) { console.log('[Xtracticle Saver]', msg); }
  }

  // ---------- menu commands ----------
  // (work on every matched page)
  GM_registerMenuCommand('Format: Smart (auto)', function () { setFormat('smart'); });
  GM_registerMenuCommand('Format: Markdown', function () { setFormat('md'); });
  GM_registerMenuCommand('Format: PDF', function () { setFormat('pdf'); });
  GM_registerMenuCommand('Format: EPUB / Kindle', function () { setFormat('epub'); });
  GM_registerMenuCommand('Format: ZIP + images', function () { setFormat('zip'); });
  GM_registerMenuCommand('Format: Text', function () { setFormat('txt'); });

  // On an X post page, offer a one-click "save this post".
  if (/^https:\/\/(x|twitter)\.com\//.test(location.href)) {
    GM_registerMenuCommand('Save this post with Xtracticle', function () {
      var url = location.href.split('#')[0];
      location.href = 'https://xtracticle.com/?url=' + encodeURIComponent(url);
    });
  }

  // ---------- only drive xtracticle.com ----------
  if (!/^https:\/\/xtracticle\.com\//.test(location.href)) return;

  // ---------- referrer fix (document-start) ----------
  // Cloudflare in front of pbs.twimg.com returns 403 to any request carrying a
  // cross-site Referer. xtracticle's html2canvas pass creates fresh Image()
  // objects that inherit neither crossorigin nor referrerpolicy, so they go out
  // with the page's default Referer and the image never reaches the canvas.
  (function installReferrer() {
    function install() {
      var head = document.head ||
                 (document.getElementsByTagName('head')[0]) ||
                 document.documentElement;
      if (!head) return false;
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
    var n = 0;
    var t = setInterval(function () { if (install() || ++n > 60) clearInterval(t); }, 50);
  })();

  // ---------- driver ----------
  // Only auto-run when the page was opened with a shared link.
  var params = new URLSearchParams(location.search);
  var hasLink = params.has('url') || params.has('link') || params.has('text') || params.has('title');
  if (!hasLink) return;   // manual use of the site: do nothing

  if (window.__xt_driven) return;
  window.__xt_driven = true;

  var start = Date.now();

  function mediaCount() {
    var pv = document.getElementById('xt-preview');
    if (!pv) return 0;
    var nodes = pv.querySelectorAll('img, video');
    var n = 0;
    for (var i = 0; i < nodes.length; i++) {
      var src = (nodes[i].getAttribute('src') || '') + ' ' +
                (nodes[i].getAttribute('poster') || '');
      if (/profile_images|profile_banners|emoji|twemoji/i.test(src)) continue;
      n++;
    }
    return n;
  }

  function previewReady() {
    var pv = document.getElementById('xt-preview');
    if (!pv) return false;
    if (pv.querySelector('img')) return true;
    return (pv.textContent || '').trim().length > 40;
  }

  function findBtn(label) {
    var btns = document.querySelectorAll('button');
    for (var i = 0; i < btns.length; i++) {
      if ((btns[i].textContent || '').trim() === label) return btns[i];
    }
    return null;
  }

  var fmt = getFormat();
  var fixed = (fmt !== FORMAT_SMART && !!LABELS[fmt]);

  function needsPreview() {
    return !fixed || fmt === 'epub' || fmt === 'pdf';
  }

  function step() {
    var probe = fixed ? findBtn(LABELS[fmt])
                      : (findBtn(LABELS.md) || findBtn(LABELS.pdf));
    if (!probe) {
      if (Date.now() - start < 30000) { setTimeout(step, 400); }
      else { console.warn('[Xtracticle Saver] Timed out waiting for the article to load.'); }
      return;
    }
    if (needsPreview() && !previewReady()) {
      if (Date.now() - start < 30000) { setTimeout(step, 300); }
      else { console.warn('[Xtracticle Saver] Timed out waiting for the preview.'); }
      return;
    }
    var label = fixed ? LABELS[fmt]
                      : (mediaCount() > 0 ? LABELS.pdf : LABELS.md);
    var btn = findBtn(label);
    if (!btn) {
      console.warn('[Xtracticle Saver] Could not find the ' + label + ' button.');
      return;
    }
    toast('Saving as ' + label + '…');
    btn.click();
  }

  setTimeout(step, 500);
})();
