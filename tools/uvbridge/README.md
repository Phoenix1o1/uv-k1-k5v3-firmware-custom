# UV Bridge

**A companion Android app for the [F4HWN firmware](https://github.com/armel/uv-k1-k5v3-firmware-custom)
for the Quansheng UV-K1 and UV-K5 V3.**

The F4HWN firmware comes with its own tools: [UV Studio](https://armel.github.io/uvstudio/)
in the browser, and a CHIRP driver for the computer. Both need a computer:
UV Studio talks to the radio through Web Serial, which Chrome for Android
does not offer for USB serial cables, and CHIRP has no Android version.
UV Bridge brings them to the phone, with the same USB cable:

- **UV Studio**: the live UV Studio site, with the radio connected through
  the cable. Firmware flashing, multiboot slots, apps, calibration backup,
  K5Viewer and RF Log work as on a computer. UV Studio itself is unchanged
  and not included in the app.
- **CHIRP images**: download the radio to a CHIRP `.img` file, upload one,
  and view or edit its channels and settings on the phone. The files open in
  CHIRP on a computer, and CHIRP's files open here.
- **CHIRP drivers**: the image editor follows the memory layout of the F4HWN
  CHIRP driver for the firmware version the radio runs, downloaded from the
  firmware releases.
- **Firmware assets** (advanced): write the fonts and menu texts of a v7
  firmware flashed from a file.

UV Bridge is a companion to the firmware project, not part of it: it relies
on the firmware's programming protocol and on UV Studio, and follows them.

## Install

1. Download the APK from the
   [Releases page](https://github.com/Phoenix1o1/uv-k1-k5v3-firmware-custom/releases)
   (the `uvbridge-v…` releases) and install it. Android 11 or later.
2. Connect the radio with its USB cable through a USB OTG adapter (see
   [Cables](#cables)). If Android asks which app to open, pick UV Bridge;
   "Always" keeps the USB permission for that cable.

The app always opens on its home screen. Long press its icon for shortcuts to
UV Studio and CHIRP image.

## The home screen

- **Radio**: the radio last connected, the progress of what is running and
  its result.
- **UV Studio**: opens the site. Its round button at the bottom left (or Back)
  returns home.
- **CHIRP image**: Download from radio, Upload, View / edit. Below them, the
  driver in use and its buttons (Drivers, From repository, From file).
- **Advanced**: folded; firmware assets.
- **About, credits & licences**.

## CHIRP images

For the F4HWN firmware ("Quansheng UV-K1 & UV-K5 V3 (F4HWN)" in CHIRP), done
the way CHIRP's F4HWN driver does it:

- **Download from radio** reads the radio and saves `uvk1-<date>.img` to
  Downloads/CHIRP.
- **Upload** checks that the file is a CHIRP image for this model, reads the
  radio and saves a backup (`uvk1-backup-<date>.img`, also in
  Downloads/CHIRP), asks for confirmation, writes 0x0000-0xA177 (channels,
  names, scan lists, settings), reads the channel data back to verify it,
  and restarts the radio. **Calibration (0xB000 and up) is never written.**
- **View / edit** lists the channels (filter by scan list) and the settings.
  Tap a channel to change its name, frequency, offset, mode, power, step,
  tones, scan list and the other per-channel options, or delete it; "Add"
  creates one. Tap a setting to change it, with the radio's menu names.
  "Save" overwrites the file or saves a copy in Downloads/CHIRP; "Upload to
  radio" sends the edited image as above.

Not editable here: calibration, DTMF, FM radio presets, scan-list MIX
selection, VFO channels, and the driver's unlock settings (F Lock, 350 MHz).
Bytes the app does not edit are kept as they are.

### CHIRP drivers

Where each setting sits in the radio's memory depends on the firmware
version, and each firmware release publishes its CHIRP driver,
`f4hwn.chirp.v<version>.py`. The app reads a driver's memory map
(`MEM_FORMAT`), sizes and setting lists; it never runs the Python code.
When a newer driver moves a setting, the app follows; a setting it drops
disappears; a new one it shows as a list (such as the v7 font setting)
appears in the editor's "From the driver" group.

- The driver is picked automatically: the one made for the firmware version
  the radio reports (for an image, the version stored in it), else the last
  one used, else the built-in layout (the 6.3 driver's). The CHIRP card shows
  the driver in use and why.
- **From repository** lists the drivers of the firmware releases and
  downloads one (also saved to Downloads/CHIRP/drivers, for CHIRP's "Load
  module" on a computer); **From file** loads one; **Drivers** lists them,
  removes one or sets the one to use when none matches.
- A driver that would write calibration memory, or stores the channels
  differently from the 6.3 layout, is refused.

## Firmware assets (advanced)

F4HWN v7 firmwares keep their fonts and menu texts in an asset zone of the
external flash; the release `.bin` carries them after the firmware image.
UV Studio writes them when it flashes a firmware from its list, so this is
only needed for a firmware flashed from a file (a custom build, for
instance):

- **Install from firmware file**: pick the same `.bin` you flashed. The app
  keeps its asset pack, checks that the radio runs that exact build, and
  writes the pack (payload first, header last, so an interrupted write never
  counts as valid); the radio checks it and redraws.
- **Install again** (once a pack is kept) writes it again.

Firmwares before v7 have no asset zone; the app says so.

## Cables

| Connection | Result |
|---|---|
| USB cable through a USB OTG adapter (USB-A socket to USB-C plug) | Works |
| 2-pin (Kenwood plug) programming cable through a USB OTG adapter | Works |
| Straight USB-C to USB-C cable between phone and radio | Does not work |

Tested on a OnePlus 13 with a UV-K1(8). With the straight USB-C cable the
phone does not get a usable serial connection to the radio, so use the OTG
adapter. Supported USB serial chips: CH340/CH341, CP210x, FTDI, PL2303, and
USB CDC devices.

## Safety

Flash with the phone charged and a short, solid cable. Back up your
calibration first (UV Studio), try a calibration dump and a firmware slot
write before full DFU flashing, and keep a computer at hand in case a flash
has to be redone. Every CHIRP upload saves a backup of the radio first.

## How it works

- `app/src/main/assets/serial-polyfill.js` is injected before UV Studio's own
  scripts and defines `navigator.serial` (`requestPort()`, `getPorts()`,
  `connect` / `disconnect` events, `SerialPort` with `open()`, `close()`,
  `getInfo()`, `readable`, `writable`). It also saves blob downloads and
  reports the page colour for the system bars. `SerialBridge.kt` is the
  native side, on [usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android).
  The bridge only answers `https://armel.github.io`; links to other sites
  open in the browser. `page-fixes.js` adds a little CSS for phone-width
  screens (styling only).
- `chirp/` holds the CHIRP side: `K5Protocol.kt` (the serial protocol at
  38400 baud, as the CHIRP driver speaks it), `RadioClone.kt` (download and
  upload), `ChirpImage.kt` (the `.img` format), `ChirpDriver.kt` (reading a
  driver file), `F4hwnMemory.kt` (channels and settings), `FirmwareAssets.kt`
  (asset packs and the 0x075x commands UV Studio uses).

## Build and test

Requires JDK 17+ and the Android SDK (platform 35).

```
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew test                 # against a simulated radio
```

To check your own files (never commit them): `-Pchirp.img=/path/file.img`
round-trips an image, `-Pchirp.driver=/path/f4hwn.chirp.v6.3.0.py` compares
a driver with the built-in layout. `node test/polyfill-test.mjs
app/src/main/assets/serial-polyfill.js` runs the polyfill against a fake
device.

Releases: committing `release/uvbridge-<version>.apk` with its notes
(`release/uvbridge-<version>.md`) to the `uvbridge` branch publishes the
release `uvbridge-v<version>` (`.github/workflows/uvbridge-release.yml`).

## Credits and licence

- **UV Studio** is by Armel FAUVEAU (F4HWN), with K5Viewer and UVTools2 by
  the same author, under the Apache License 2.0. UV Bridge does not include
  it: it opens the live site and provides the serial link it needs.
- **The F4HWN firmware** (Edition Fusion for UV-K1 and UV-K5 V3) is by Armel
  FAUVEAU (F4HWN), muzkr and contributors, based on the Dual Tachyon and
  Egzumer firmwares, under the Apache License 2.0. Its programming protocol
  and asset format are what UV Bridge speaks.
- **CHIRP** (Dan Smith and contributors) and **the F4HWN CHIRP driver**
  (F4HWN, JOC2, Egzumer, from the UV-K5 driver by Jacek Lipkowski SQ5BPF)
  are under the GNU GPL. UV Bridge follows their image format and memory
  layout, includes neither, and only reads driver files the user downloads
  or loads.
- **usb-serial-for-android** (Google Inc., Mike Wakerly; MIT License),
  **AndroidX** (The Android Open Source Project) and the **Kotlin standard
  library** (JetBrains) are included in the app; AndroidX and Kotlin are
  under the Apache License 2.0.

UV Bridge is licensed under the Apache License 2.0, like the firmware
project ([LICENSE](../../LICENSE)). The notices and licence texts shipped in
the app are in [app/src/main/assets/NOTICE.txt](app/src/main/assets/NOTICE.txt)
and [app/src/main/assets/licenses/](app/src/main/assets/licenses/), and
under "About, credits & licences" in the app. It is not affiliated with
Quansheng.
