# Phoenix1o1's workshop for the F4HWN firmware (UV-K1 / UV-K5 V3)

This repository is a **fork of [armel/uv-k1-k5v3-firmware-custom](https://github.com/armel/uv-k1-k5v3-firmware-custom)**,
the F4HWN firmware for the Quansheng UV-K1 and UV-K5 V3. It is used to add
features, test new ones on a real radio (a UV-K1(8)), and prepare them as
pull requests for the original project.

**It is not a separate firmware.** For the firmware itself, its
documentation, releases and CHIRP driver, go to the
[original repository](https://github.com/armel/uv-k1-k5v3-firmware-custom)
and to [UV Studio](https://armel.github.io/uvstudio/). The `main` branch here
follows the original `main`. Only this README and the removal of the
original's donation and sponsor links differ.

> [!WARNING]
> Everything here is experimental work in progress, tested on one radio.
> Use it at your own risk. Back up your calibration data first, for example with
> [UV Studio](https://armel.github.io/uvstudio/#dump-calib).

## What is being worked on

Each piece of work lives on its own branch.

### Scan-list picker: choose the list to scan from a menu

**Branch [`scan-list-picker`](https://github.com/Phoenix1o1/uv-k1-k5v3-firmware-custom/tree/scan-list-picker)**.
Proposed upstream as [armel#733](https://github.com/armel/uv-k1-k5v3-firmware-custom/pull/733) (open).

During a memory scan, a short press on `*` opens a list of the scan lists
over the main screen, with their names and channel counts. UP/DOWN and MENU
move the scan to the chosen list without stopping it. Today the radio offers
a long `*` (next list), F + UP/DOWN (one list at a time) and typing a list
number. None of them shows what the lists are called or what they hold.

The picker is a firmware change behind the build flag
`ENABLE_FEAT_F4HWN_SCAN_LIST_PICKER`, based on `feature_update_v7`. It costs
about 1.2 KB of flash where enabled, and nothing otherwise.

### UV Bridge: an Android companion app

**Branch [`uvbridge`](https://github.com/Phoenix1o1/uv-k1-k5v3-firmware-custom/tree/uvbridge/tools/uvbridge)**, folder `tools/uvbridge`.
APKs on the [Releases page](https://github.com/Phoenix1o1/uv-k1-k5v3-firmware-custom/releases).

UV Studio and CHIRP both need a computer. UV Bridge brings them to an Android
phone with the same USB programming cable, through a USB OTG adapter:

- **UV Studio**: the live UV Studio site, with the radio connected over the cable.
- **CHIRP images**: download the radio to a CHIRP `.img`, upload one, and
  view or edit its channels and settings on the phone.
- **Firmware assets**: write the fonts and menu texts of a v7 firmware
  (see below).

See [its README](https://github.com/Phoenix1o1/uv-k1-k5v3-firmware-custom/blob/uvbridge/tools/uvbridge/README.md)
for the details, the cables that work, and how to build it.

### Asset loading for v7 firmware not yet released

From v7 (`feature_update_v7`, not yet released), the firmware keeps its
fonts and menu texts in a separate asset zone for each multiboot slot. A v7
build flashed into a slot by an older tool boots with a blank screen,
because nothing writes that zone.

- [`tools/fw_assets/write_fw_assets.py`](https://github.com/Phoenix1o1/uv-k1-k5v3-firmware-custom/tree/testlab/tools/fw_assets)
  (branch `testlab`) writes a build's asset pack to the running slot over the
  serial cable, using the firmware's own asset commands. The README there
  explains when it is needed.
- UV Bridge does the same from a phone.

A UV Studio version with v7 support does this by itself. These tools only
cover testing v7 builds before then.

### Test builds

**Branch [`testlab`](https://github.com/Phoenix1o1/uv-k1-k5v3-firmware-custom/tree/testlab)**:
`feature_update_v7` as of 26 October (before ChannelBook), plus the
scan-list picker, the earlier Channels app, the asset-loading script and a
`Testlab` CMake preset (Fusion with the picker on). It is for testing on the
radio only and is not meant for upstream.

### Finished or superseded

- **Channels app and memory-channel API**: branch
  [`channels-app`](https://github.com/Phoenix1o1/uv-k1-k5v3-firmware-custom/tree/channels-app),
  proposed as [armel#727](https://github.com/armel/uv-k1-k5v3-firmware-custom/pull/727).
  It was closed in favour of Armel's own **ChannelBook** app and memory API,
  now in `feature_update_v7`. The branch stays for reference. Its
  `Channels.app` uses a different API level 4 layout and **must not be run on
  official v7 firmware**; use ChannelBook there.

## Credits

The firmware, and everything here that builds on it, is the work of many
people:

- **Armel Fauveau, F4HWN**: the F4HWN firmware and this port, UV Studio, the
  v7 firmware asset zone and its serial commands, ChannelBook and the
  memory-channel API, and the F4HWN CHIRP driver.
- **[muzkr](https://github.com/muzkr)**: the UV-K1 / UV-K5 V3 (PY32F071) port,
  joint work with Armel.
- **[DualTachyon](https://github.com/DualTachyon)**: the first open-source
  UV-K5 [firmware](https://github.com/DualTachyon/uv-k5-firmware), on which
  everything else is built.
- **[Egzumer](https://github.com/egzumer)**, **[OneOfEleven](https://github.com/OneOfEleven)**,
  **[fagci](https://github.com/fagci)** (spectrum analyser) and the
  contributors credited in the original project, among them
  [mrkusypl](https://github.com/mrkusypl), [Tunas1337](https://github.com/Tunas1337),
  [manujedi](https://github.com/manujedi) and [spm81](https://github.com/spm81).
  See the original repository's
  [contributors](https://github.com/armel/uv-k1-k5v3-firmware-custom/graphs/contributors)
  and [`NOTICE`](NOTICE).
- **The CHIRP UV-K5 driver**: Jacek Lipkowski SQ5BPF (driver and
  [reverse engineering](https://github.com/sq5bpf/uvk5-reverse-engineering)),
  adapted by Egzumer, JOC2 and F4HWN. It is based on CHIRP's template by Dan Smith.
  UV Bridge's CHIRP support follows that driver's memory layout.
- **[usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android)**
  by Mike Wakerly (mik3y) and contributors (MIT): UV Bridge's USB serial support.
- **Phoenix1o1**: the work in this fork, namely the scan-list picker, UV
  Bridge, the asset-loading script, and the earlier Channels app.

## License

The firmware is licensed under the [Apache License, Version 2.0](LICENSE),
carrying forward the original copyright of Dual Tachyon. The
[`NOTICE`](NOTICE) file is kept as the license requires. UV Bridge lists
its own credits and licences in
[its README](https://github.com/Phoenix1o1/uv-k1-k5v3-firmware-custom/blob/uvbridge/tools/uvbridge/README.md#credits-and-licence)
and in the app.

    Copyright 2023 Dual Tachyon
    https://github.com/DualTachyon

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
