# UV Bridge

UV Studio on Android, with a USB programming cable, plus CHIRP images,
CHIRP drivers and firmware assets, in one app.

The app opens on its home screen, light or dark with the system:

- **UV Studio**: the live UV Studio site, with the cable (below).
- **CHIRP image**: download, upload, view and edit CHIRP `.img` files.
- **Firmware assets**: install the fonts and menu texts of a v7 firmware.
- **CHIRP drivers**: the driver files whose memory layout the image code follows.

A card at the top shows the radio last connected and the driver picked
for it, and the progress of what is running.

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
3. Open UV Studio and start any action that connects: UV Bridge lists the
   USB serial devices, then Android asks for permission.

Files UV Studio offers for download (calibration dumps, logs...) are saved to
**Downloads**. The screen stays on while the app is open, and rotating the
phone does not close the connection.

## CHIRP images

The **CHIRP image** card reads, writes and edits CHIRP `.img` files for the
F4HWN firmware ("Quansheng UV-K1 & UV-K5 V3 (F4HWN)" in CHIRP), the way
CHIRP's F4HWN driver does.

- **Download from radio** reads the radio and saves `uvk1-<date>.img` to
  Downloads/CHIRP. CHIRP opens it.
- **Upload image to radio** checks that the file is a CHIRP image for this
  model, reads the radio and saves a backup (`uvk1-backup-<date>.img`, also
  in Downloads/CHIRP), asks for confirmation, then writes 0x0000-0xA177
  (channels, names, scan lists, settings) as CHIRP does, reads the channel
  data back to verify it, and restarts the radio. Calibration (0xB000 and
  up) is never written.
- **View / edit** lists the channels (filter by scan list) and the
  settings. Tap a channel to change its name, frequency, offset, mode, power,
  step, tones, scan list and the other per-channel options, or delete it;
  "Add" creates one. Tap a setting to change it: the basic, display, F4HWN,
  side-key and scan-list settings of CHIRP's settings tab, with the radio's
  menu names. "Save" overwrites the file or saves a copy in Downloads/CHIRP;
  "Upload" sends the edited image to the radio as above.

  Channels and settings are encoded as the driver's get_memory/set_memory and
  get_settings/set_settings do (`chirp/F4hwnMemory.kt`); bytes it does not
  touch are kept. Not editable here: calibration, DTMF, FM radio presets,
  scan-list MIX selection, VFO channels, and the driver's unlock settings
  (F Lock, 350 MHz...).

## CHIRP drivers

Where each setting sits in the radio's memory comes from a CHIRP driver
file, `f4hwn.chirp.v<version>.py`, published with each firmware release.
The app reads the file's `MEM_FORMAT` (the memory map), its sizes and its
setting lists; it never runs the Python code. When a newer driver moves a
setting, the app follows; a setting the driver drops disappears; a new one
the driver shows as a list (such as the font setting of v7) appears in the
editor's "From the driver" group.

- **From repository** lists the drivers of the firmware releases and
  downloads one; **From file** loads one from the phone. Downloaded drivers
  are also saved to Downloads/CHIRP/drivers, for CHIRP on a computer
  ("Load module").
- **Drivers** lists them; a driver can be removed, or set as the one to use
  when none matches.
- The driver is picked automatically: the one made for the firmware
  version the radio reports (for an image, the version stored in it), else
  the last one used, else the built-in layout (the 6.3 driver's).

A driver that would write calibration memory, or stores the channels
differently from the 6.3 layout, is refused.

## Firmware assets

F4HWN v7 firmwares keep their fonts and menu texts in an asset zone of the
external flash. The release `.bin` carries them as an asset pack after the
firmware image; UV Studio writes it after flashing. **Install on radio**
does the same on a radio that already runs the firmware (for instance when
the pack was not written after a flash):

1. it asks the radio which firmware build it runs,
2. picks the asset pack of that exact build: one the app keeps, else it
   looks in the firmware repository (the firmware of the radio's version,
   then the development build),
3. writes it (payload first, header last, so an interrupted write never
   counts as valid), and the radio checks it and redraws.

**From repository** downloads a firmware `.bin` to Downloads/F4HWN and keeps
its asset pack; **From file** keeps the pack of a `.bin` (or `.pack`) on the
phone. Firmwares before v7 have no asset zone; the app says so.

## Tests

The protocol code is in `app/src/main/java/.../chirp/`; `./gradlew test` runs
it against a simulated radio. To check your own files (never committed):
`-Pchirp.img=/path/file.img` round-trips an image and
`-Pchirp.driver=/path/f4hwn.chirp.v6.3.0.py` compares a driver with the
built-in layout.

## Cables

| Connection | Result |
|---|---|
| USB cable through a USB OTG adapter (USB-A socket to USB-C plug) | Works |
| 2-pin (Kenwood plug) programming cable through a USB OTG adapter | Works |
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
