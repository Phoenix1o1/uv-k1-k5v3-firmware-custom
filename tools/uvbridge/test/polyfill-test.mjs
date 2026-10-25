// Runs assets/serial-polyfill.js in Node against a fake native side (an echo
// device) and exercises it the way UV Studio does.
import fs from "node:fs";
import assert from "node:assert/strict";

const src = fs.readFileSync(process.argv[2], "utf8");

// ---- minimal browser globals ----
globalThis.window = globalThis;
globalThis.addEventListener = () => {};
const docListeners = [];
globalThis.document = { addEventListener: (t, f) => docListeners.push([t, f]) };
class HTMLAnchorElement {
  constructor(href, download) { this.href = href; this._dl = download; this.isConnected = false; this.clicked = false; }
  hasAttribute(n) { return n === "download" && this._dl !== undefined; }
  getAttribute(n) { return n === "download" ? this._dl : null; }
  click() { this.clicked = true; }
}
globalThis.HTMLAnchorElement = HTMLAnchorElement;

// ---- fake native: one CH340 device that echoes writes ----
const log = [];
let permission = false;
const native = {
  onmessage: null,
  reply(obj) { setTimeout(() => native.onmessage({ data: JSON.stringify(obj) }), 1); },
  postMessage(text) {
    const m = JSON.parse(text);
    log.push(m.op);
    const dev = { key: "/dev/bus/usb/001/004", info: { vendorId: 0x1a86, productId: 0x7523 } };
    switch (m.op) {
      case "requestPort": permission = true; return this.reply({ id: m.id, ok: true, result: dev });
      case "getPorts": return this.reply({ id: m.id, ok: true, result: permission ? [dev] : [] });
      case "open": assert.equal(m.baudRate, 38400); return this.reply({ id: m.id, ok: true, result: null });
      case "write":
        this.reply({ id: m.id, ok: true, result: null });
        return this.reply({ ev: "data", key: m.key, b64: m.b64 });           // echo
      case "close": return this.reply({ id: m.id, ok: true, result: null });
      case "save": native.saved = { name: m.name, data: Buffer.from(m.b64, "base64").toString() };
        return this.reply({ id: m.id, ok: true, result: null });
    }
  },
};
globalThis.UVBridgeNative = native;

new Function(src)();
assert.ok("serial" in navigator, "navigator.serial defined");

const enc = s => new TextEncoder().encode(s);
const dec = b => new TextDecoder().decode(b);

// requestPort / getPorts identity
assert.deepEqual(await navigator.serial.getPorts(), []);
const port = await navigator.serial.requestPort();
assert.deepEqual(port.getInfo(), { usbVendorId: 0x1a86, usbProductId: 0x7523 });
assert.equal((await navigator.serial.getPorts())[0], port, "same SerialPort object");
assert.equal(port.readable, null, "no streams before open");

// open, write, read the echo (as flash.js does)
await port.open({ baudRate: 38400, bufferSize: 65536 });
await assert.rejects(port.open({ baudRate: 38400 }), /already open/);
let reader = port.readable.getReader();
let writer = port.writable.getWriter();
await writer.write(enc("hello"));
let { value } = await reader.read();
assert.equal(dec(value), "hello");

// UVStudioSerial.closeResources(): cancel + releaseLock, close + releaseLock, close
await reader.cancel(); reader.releaseLock();
await writer.close(); writer.releaseLock();
await port.close();
assert.equal(port.readable, null);

// reopen on the same object (auto-reconnect path)
await port.open({ baudRate: 38400 });
reader = port.readable.getReader();
writer = port.writable.getWriter();
await writer.write(enc("again"));
({ value } = await reader.read());
assert.equal(dec(value), "again");

// unplug: pending read fails with NetworkError, "disconnect" with target === port
let disconnectedTarget = null;
navigator.serial.addEventListener("disconnect", e => { disconnectedTarget = e.target; });
const pendingRead = reader.read();
native.onmessage({ data: JSON.stringify({ ev: "disconnect", key: "/dev/bus/usb/001/004" }) });
await assert.rejects(pendingRead, e => e.name === "NetworkError");
assert.equal(disconnectedTarget, port);

// replug: "connect" with a port that getInfo() matches (reconnect manager)
let connected = null;
navigator.serial.addEventListener("connect", e => { connected = e.target; });
native.onmessage({ data: JSON.stringify({ ev: "connect", key: "/dev/bus/usb/001/005",
                                          info: { vendorId: 0x1a86, productId: 0x7523 } }) });
assert.ok(connected && connected.getInfo().usbVendorId === 0x1a86);
await connected.open({ baudRate: 38400 });
await connected.close();

// errors from native reach the caller as DOMException
native.postMessage = function (text) {
  const m = JSON.parse(text);
  this.reply({ id: m.id, ok: false, error: "No port selected.", name: "NotFoundError" });
};
await assert.rejects(navigator.serial.requestPort(), e => e.name === "NotFoundError");

// blob download: a detached <a download href="blob:..."> clicked from code
native.postMessage = function (text) {
  const m = JSON.parse(text);
  native.saved = { name: m.name, data: Buffer.from(m.b64, "base64").toString() };
  this.reply({ id: m.id, ok: true, result: null });
};
const blobUrl = URL.createObjectURL(new Blob(["CALDATA"]));
const a = new HTMLAnchorElement(blobUrl, "calibration.dat");
a.click();
await new Promise(r => setTimeout(r, 50));
assert.deepEqual(native.saved, { name: "calibration.dat", data: "CALDATA" });
assert.equal(a.clicked, false, "blob link handled, not navigated");
const plain = new HTMLAnchorElement("https://example.com/x", undefined);
plain.click();
assert.equal(plain.clicked, true, "ordinary links untouched");

console.log("all polyfill checks passed; native ops:", [...new Set(log)].join(", "));
