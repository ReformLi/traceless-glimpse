(function () {
  if (window.__BGV_HOOK__) return;
  window.__BGV_HOOK__ = true;

  var MEDIA_RE = /\.(m3u8|mp4|m4v|webm|mkv|flv|mov)(?:[?#]|$)/i;
  var TYPE_RE = /(mpegurl|dash\+xml|video\/|audio\/)/i;
  var seen = Object.create(null);
  var seenCount = 0;
  var MAX = 200;

  function abs(u) {
    try { return new URL(u, location.href).href; } catch (e) { return String(u || ""); }
  }

  function post(payload) {
    try {
      var s = JSON.stringify(payload);
      var b = window.__BGV_BRIDGE__;
      if (b && b.report) b.report(s);
    } catch (e) {}
  }

  function emit(url, source, meta) {
    url = String(url || "");
    if (!url) return;
    var isBlob = url.indexOf("blob:") === 0;
    if (!isBlob && !/^https?:/i.test(url)) return;
    var key = isBlob ? url : url.split("#")[0];
    if (seen[key] || seenCount >= MAX) return;
    seen[key] = true;
    seenCount++;
    var m = MEDIA_RE.exec(key);
    post({
      url: key,
      kind: isBlob ? "blob" : (m ? m[1].toLowerCase() : ((meta && meta.kind) || "unknown")),
      source: source,
      contentType: (meta && meta.contentType) || "",
      frameUrl: location.href,
      referrer: document.referrer || "",
      width: (meta && meta.width) || 0,
      height: (meta && meta.height) || 0,
      ts: Date.now()
    });
  }

  // XHR：open 记 URL，loadend 再读 responseURL / Content-Type（重定向后的真实地址）。
  var xOpen = XMLHttpRequest.prototype.open;
  var xSend = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function (method, url) {
    this.__bgv = { url: abs(url), method: String(method || "").toUpperCase() };
    return xOpen.apply(this, arguments);
  };
  XMLHttpRequest.prototype.send = function () {
    var xhr = this, info = xhr.__bgv;
    if (info) {
      if (MEDIA_RE.test(info.url)) emit(info.url, "xhr");
      xhr.addEventListener("loadend", function () {
        try {
          var ct = xhr.getResponseHeader("Content-Type") || "";
          var finalUrl = xhr.responseURL || info.url;
          if (MEDIA_RE.test(finalUrl) || TYPE_RE.test(ct)) {
            emit(finalUrl, "xhr", { contentType: ct });
          }
        } catch (e) {}
      });
    }
    return xSend.apply(this, arguments);
  };

  // fetch：请求发出前先记，响应回来再按 Content-Type / 重定向地址补一次。
  var origFetch = window.fetch;
  if (origFetch) {
    window.fetch = function (input, init) {
      var url = abs(typeof input === "string" ? input : (input && input.url) || "");
      if (MEDIA_RE.test(url)) emit(url, "fetch");
      return origFetch.apply(this, arguments).then(function (res) {
        try {
          var ct = res.headers.get("content-type") || "";
          var finalUrl = res.url || url;
          if (MEDIA_RE.test(finalUrl) || TYPE_RE.test(ct)) {
            emit(finalUrl, "fetch", { contentType: ct });
          }
        } catch (e) {}
        return res;
      });
    };
  }

  function reportVideo(el, source) {
    if (!el) return;
    var src = el.currentSrc || el.src || "";
    if (!src) {
      var children = el.querySelectorAll ? el.querySelectorAll("source") : [];
      for (var i = 0; i < children.length; i++) {
        if (children[i].src) { src = children[i].src; break; }
      }
    }
    if (!src) return;
    emit(src, source, { width: el.videoWidth || 0, height: el.videoHeight || 0 });
  }

  var play = HTMLMediaElement.prototype.play;
  HTMLMediaElement.prototype.play = function () {
    if (this.tagName === "VIDEO") reportVideo(this, "play");
    return play.apply(this, arguments);
  };

  ["loadstart", "loadedmetadata", "canplay", "playing"].forEach(function (ev) {
    document.addEventListener(ev, function (e) {
      if (e.target && e.target.tagName === "VIDEO") reportVideo(e.target, ev);
    }, true);
  });

  try {
    new MutationObserver(function (list) {
      list.forEach(function (m) {
        if (m.type === "attributes" && m.target.tagName === "VIDEO") {
          reportVideo(m.target, "src");
        }
        if (m.addedNodes) {
          for (var i = 0; i < m.addedNodes.length; i++) {
            var n = m.addedNodes[i];
            if (n.nodeType !== 1) continue;
            if (n.tagName === "VIDEO") reportVideo(n, "dom");
            if (n.querySelectorAll) {
              n.querySelectorAll("video").forEach(function (v) { reportVideo(v, "dom"); });
            }
          }
        }
      });
    }).observe(document.documentElement || document, {
      subtree: true, childList: true, attributes: true, attributeFilter: ["src"]
    });
  } catch (e) {}

  // 已经发生的请求 + 页面内嵌配置（player_aaaa 里的 https:\/\/...m3u8 也能认）。
  function scanPerf() {
    try {
      performance.getEntriesByType("resource").forEach(function (e) {
        if (MEDIA_RE.test(e.name)) emit(e.name, "performance");
      });
    } catch (e) {}
  }

  function scanInline() {
    try {
      var html = (document.documentElement && document.documentElement.innerHTML) || "";
      if (html.length > 2000000) return;
      var text = html.replace(/\\\//g, "/").replace(/&amp;/g, "&");
      var re = /https?:\/\/[^"'\s<>]+?\.(?:m3u8|mp4|m4v|webm|mkv|mpd)(?:\?[^"'\s<>]*)?/gi;
      var m, n = 0;
      while ((m = re.exec(text)) && n++ < 50) emit(m[0], "inline");
    } catch (e) {}
  }

  function scanAll() {
    scanPerf();
    scanInline();
  }

  document.addEventListener("DOMContentLoaded", scanAll, { once: true });
  setTimeout(scanAll, 1200);
  setTimeout(scanAll, 3500);
  setTimeout(scanAll, 7000);
})();
