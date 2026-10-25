# UV Bridge

UV Studio on Android, with a USB programming cable.

[UV Studio](https://armel.github.io/uvstudio/) talks to the radio through Web
Serial, which Chrome for Android does not provide for every USB serial adapter.
UV Bridge opens the live UV Studio site in a WebView and supplies the Web
Serial API itself, on top of
[usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android).
UV Studio is unchanged: firmware flashing, firmware slots, apps, calibration,
K5Viewer and RF Log run as they do on a computer.

## Use

1. Install the APK from the Releases page (sideload; it is not on the Play
   Store). Android 11 or later.
2. Connect the radio to the phone with a USB cable through a USB OTG adapter
   (see Cables below). If Android asks which app to open, pick UV Bridge;
   "Always" keeps the USB permission for that cable.
3. In UV Studio, start any action that connects: UV Bridge lists the USB
   serial devices, then Android asks for permission.

Files UV Studio offers for download (calibration dumps, logs...) are saved to
**Downloads**. The screen stays on while the app is open, and rotating the
phone does not close the connection.

## Cables

| Connection | Result |
|---|---|
| USB cable through a USB OTG adapter (USB-A socket to USB-C plug) | Works |
| Straight USB-C to USB-C cable between phone and radio | Does not work |

Tested on a OnePlus 13 with a UV-K1(8). With the straight USB-C
cable the phone does not get a usable serial connection to the radio, so use
the OTG adapter.

Supported USB serial chips: CH340/CH341, CP210x, FTDI, PL2303, and USB CDC
devices.

## How it works

- `app/src/main/assets/serial-polyfill.js` is injected before UV Studio's own
  scripts and defines `navigator.serial`: `requestPort()`, `getPorts()`, the
  `connect` / `disconnect` events, and `SerialPort` with `open()`, `close()`,
  `getInfo()`, `readable` and `writable`. It also saves blob downloads, which
  a WebView cannot save itself, and reports the page colour so the system bars
  match UV Studio's light or dark theme.
- `SerialBridge.kt` is the native side: device list, USB permission, port
  settings, reads on a background thread, writes off the UI thread, unplug and
  replug events.
- The bridge is a WebMessageListener restricted to `https://armel.github.io`;
  other sites cannot reach it, and links to them open in the browser.
- `app/src/main/assets/page-fixes.js` adds a little CSS for phone-width
  screens: UV Studio's top bar actions and Live Viewer screen have fixed
  widths (424 and 524 CSS px) wider than a phone, so below 600 px the bar
  wraps and the screen scales down. Styling only.
- The page sits in a container padded off the status bar, navigation bar and
  camera cutout, painted in the page's colour. The WebView lays the page out
  as mobile Chrome does, at 100 % text size (the system font size would
  enlarge the text past what the layout allows; pinch zoom works).

## Build

Requires JDK 17+ and the Android SDK (platform 35).

```
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

`test/polyfill-test.mjs` runs the polyfill in Node against a fake echo device:

```
node test/polyfill-test.mjs app/src/main/assets/serial-polyfill.js
```

## Safety

Flash with the phone charged and a short, solid cable. Try a calibration dump
and a firmware slot write before full DFU flashing, and keep a computer at hand
in case a flash has to be redone. Back up your calibration first.
