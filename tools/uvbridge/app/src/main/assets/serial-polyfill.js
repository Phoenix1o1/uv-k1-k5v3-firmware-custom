/* Web Serial for the Android WebView.
 *
 * The WebView has no navigator.serial, so this script (injected before the
 * page's own scripts) provides the subset UV Studio uses:
 *   navigator.serial.requestPort() / getPorts() / "connect" and "disconnect"
 *   SerialPort.open({ baudRate }) / close() / getInfo() / readable / writable
 * on top of the app's native USB serial code (usb-serial-for-android).
 *
 * Transport: the native side registers a WebMessageListener named
 * UVBridgeNative, reachable only from the UV Studio origin. Messages are JSON:
 *   page -> app  { id, op, ... }                 a request, answered once
 *   app  -> page { id, ok, result | error }      the answer
 *   app  -> page { ev: "data", key, b64 }        bytes received
 *   app  -> page { ev: "connect" | "disconnect", key, info }
 * It also saves the files the page offers as blob downloads (calibration
 * dumps...), which the WebView cannot save by itself, and reports the page's
 * background colour so the app paints the system bars to match. */
(function () {
  "use strict";
  if (window.__uvBridgeInstalled || !window.UVBridgeNative) return;
  window.__uvBridgeInstalled = true;

  const native = window.UVBridgeNative;
  const pending = new Map();      // request id -> { resolve, reject }
  const ports = new Map();        // device key -> SerialPort (stable identity)
  let nextId = 1;

  function request(op, args) {
    return new Promise((resolve, reject) => {
      const id = nextId++;
      pending.set(id, { resolve, reject });
      native.postMessage(JSON.stringify(Object.assign({ id, op }, args || {})));
    });
  }

  function toBase64(bytes) {
    let s = "";
    for (let i = 0; i < bytes.length; i += 0x8000)
      s += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
    return btoa(s);
  }

  function fromBase64(b64) {
    const s = atob(b64);
    const out = new Uint8Array(s.length);
    for (let i = 0; i < s.length; i++) out[i] = s.charCodeAt(i);
    return out;
  }

  /* Web Serial fires connect/disconnect at the port and they reach
   * navigator.serial with event.target === port. */
  function fire(type, port) {
    for (const target of [port, serial]) {
      const ev = new Event(type);
      Object.defineProperty(ev, "target", { value: port });
      target.dispatchEvent(ev);
    }
  }

  class SerialPort extends EventTarget {
    constructor(key, info) {
      super();
      this._key = key;
      this._info = info;
      this._open = false;
      this._readable = null;
      this._readCtl = null;
      this._writable = null;
    }

    getInfo() {
      return { usbVendorId: this._info.vendorId, usbProductId: this._info.productId };
    }

    async open(options) {
      if (this._open) throw new DOMException("The port is already open.", "InvalidStateError");
      const o = options || {};
      await request("open", {
        key: this._key,
        baudRate: o.baudRate,
        dataBits: o.dataBits || 8,
        stopBits: o.stopBits || 1,
        parity: o.parity || "none"
      });
      this._open = true;
      void this.readable;   // create the stream now: bytes arriving before getReader() are queued
    }

    /* A new stream after a cancel, as Web Serial does while the port is open. */
    get readable() {
      if (!this._open) return null;
      if (!this._readable) {
        this._readable = new ReadableStream({
          start: ctl => { this._readCtl = ctl; },
          cancel: () => { this._readable = null; this._readCtl = null; }
        });
      }
      return this._readable;
    }

    get writable() {
      if (!this._open) return null;
      if (!this._writable) {
        this._writable = new WritableStream({
          write: chunk => request("write", { key: this._key, b64: toBase64(new Uint8Array(chunk)) }),
          close: () => { this._writable = null; },
          abort: () => { this._writable = null; }
        });
      }
      return this._writable;
    }

    async close() {
      if (!this._open) return;
      this._open = false;
      this._endStreams(null);
      await request("close", { key: this._key });
    }

    async forget() {
      await this.close();
    }

    _data(bytes) {
      if (this._readCtl) {
        try { this._readCtl.enqueue(bytes); } catch (e) { /* stream already closed */ }
      }
    }

    /* error: null to end the streams quietly, or the error readers get. */
    _endStreams(error) {
      if (this._readCtl) {
        try { error ? this._readCtl.error(error) : this._readCtl.close(); } catch (e) {}
      }
      this._readable = null;
      this._readCtl = null;
      this._writable = null;
    }
  }

  function portFor(key, info) {
    let port = ports.get(key);
    if (!port) {
      port = new SerialPort(key, info);
      ports.set(key, port);
    }
    return port;
  }

  class Serial extends EventTarget {
    /* The app shows its own device list, then asks Android for permission. */
    async requestPort() {
      const r = await request("requestPort");
      return portFor(r.key, r.info);
    }

    async getPorts() {
      const list = await request("getPorts");
      return list.map(d => portFor(d.key, d.info));
    }
  }

  const serial = new Serial();
  Object.defineProperty(navigator, "serial", { value: serial, configurable: true });

  native.onmessage = event => {
    const msg = JSON.parse(event.data);
    if (msg.ev === "data") {
      const port = ports.get(msg.key);
      if (port) port._data(fromBase64(msg.b64));
    } else if (msg.ev === "disconnect") {
      const port = ports.get(msg.key);
      if (port) {
        const wasOpen = port._open;
        port._open = false;
        port._endStreams(wasOpen ? new DOMException("The device has been lost.", "NetworkError") : null);
        fire("disconnect", port);
        ports.delete(msg.key);
      }
    } else if (msg.ev === "connect") {
      fire("connect", portFor(msg.key, msg.info));
    } else if (msg.id) {
      const p = pending.get(msg.id);
      if (!p) return;
      pending.delete(msg.id);
      if (msg.ok) p.resolve(msg.result);
      else p.reject(new DOMException(msg.error || "Serial error", msg.name || "NetworkError"));
    }
  };

  /* ---- page colour, for the system bars (follows UV Studio's theme switch) ----
   * The colour of what sits at the top of the page (its header), so the
   * status bar blends into it. */
  let lastColor = null;
  function topColor() {
    for (let el = document.elementFromPoint(innerWidth / 2, 1); el; el = el.parentElement) {
      const m = getComputedStyle(el).backgroundColor.match(/[\d.]+/g);
      if (m && (m.length < 4 || +m[3] > 0)) return (+m[0] << 16) | (+m[1] << 8) | +m[2];
    }
    return null;
  }
  function reportColor() {
    const rgb = topColor();
    if (rgb !== null && rgb !== lastColor) {
      lastColor = rgb;
      request("pageColor", { rgb }).catch(() => {});
    }
  }
  function checkSoon() {                 // now, and after a colour transition
    reportColor();
    setTimeout(reportColor, 400);
  }
  document.addEventListener("DOMContentLoaded", () => {
    checkSoon();
    // UV Studio sets data-theme on <body>; watch it wherever it is set.
    new MutationObserver(checkSoon).observe(document.documentElement,
      { attributes: true, subtree: true, attributeFilter: ["data-theme"] });
  });
  window.addEventListener("load", checkSoon);

  /* ---- saving blob downloads ----
   * UV Studio offers files as <a download href="blob:...">. Read the blob here
   * and hand it to the app, which writes it to Downloads. */
  function saveIfBlob(a) {
    const href = a.href || "";
    if (!a.hasAttribute("download") || !(href.startsWith("blob:") || href.startsWith("data:")))
      return false;
    const name = a.getAttribute("download") || "download.bin";
    fetch(href)
      .then(r => r.arrayBuffer())
      .then(buf => request("save", { name, b64: toBase64(new Uint8Array(buf)) }))
      .catch(e => console.warn("Save failed:", e));
    return true;
  }

  document.addEventListener("click", e => {
    const a = e.target && e.target.closest ? e.target.closest("a") : null;
    if (a && saveIfBlob(a)) e.preventDefault();
  }, true);

  /* Links clicked from code, possibly not in the document. */
  const click = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    if (!this.isConnected && saveIfBlob(this)) return;
    return click.call(this);
  };
})();
