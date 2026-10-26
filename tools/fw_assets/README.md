# write_fw_assets.py: load a v7 slot's fonts and menu texts

From v7 (commit 45ce208f, "Move firmware UI assets to per-slot external
flash"), the fonts, menu texts and QR codes are no longer inside the firmware
image. Each multiboot slot has its own 16 KiB asset zone in external flash,
from `0x1CA000` (`App/driver/mb_flash.h`). At boot,
`FW_ASSETS_Init(MB_GetRunningSlot())` checks that zone: the `FWA1` header, a
CRC, and the exact build ID of the running firmware (`App/fw_assets.c`). If
the check fails, every asset read returns zeros, so the firmware runs but
draws a blank screen.

A v7 `.bin` is a package: the firmware image, then its asset pack, then a
16-byte `FWAT` trailer (`cmake/pack_fw_assets.py`). UV Studio for v7 flashes
the image and then sends the pack over serial. A 6.3-era tool, or the 6.3
slot manager, copies only the image into the slot. The slot boots, keys
beep, but nothing ever fills its asset zone.

This script fills it from the running v7 firmware, over the programming cable
or USB-C.

## When you need it

You flashed a v7 build (for example `f4hwn.testlab.bin` or `f4hwn.fusion.bin`)
into a slot with a 6.3-era tool, and that slot now boots with a blank screen.

You don't need it if you flash with a UV Studio version that supports v7 asset
packs, because it already sends the pack.

## Use

1. Install pyserial: `pip install pyserial`
2. Boot the v7 slot (a blank screen is fine) and connect the cable.
3. Check first. This writes nothing:

   ```
   python3 tools/fw_assets/write_fw_assets.py COM5 build/Testlab/f4hwn.testlab.bin --info
   ```

   On Linux or macOS the port is `/dev/ttyUSB0`, `/dev/tty.usbserial-…` or
   similar.
4. Write:

   ```
   python3 tools/fw_assets/write_fw_assets.py COM5 build/Testlab/f4hwn.testlab.bin
   ```

When it ends with `Validate: OK`, the radio reloads its fonts and texts and
redraws at once, with no reboot. The pack is about 9 KB, which takes a few
seconds at 38400 baud.

The file can be the package (`.bin`) or the bare pack
(`build/<preset>/f4hwn.<edition>.assets.bin`). It must come from **the same
build as the firmware in the slot**. The script reads the running firmware's
build ID first and stops before writing anything if the two differ, since the
radio would refuse the pack anyway. After every rebuild, use the new `.bin`.

## What it does

The script uses the radio's serial protocol (`App/app/uart.c`,
`App/driver/mb_flash.c`):

| Command | Reply | Purpose |
|---|---|---|
| `0x0514` | `0x0515` | Session handshake, which sets the timestamp the write commands require |
| `0x0750` | `0x0751` | Asset zone info: status, running slot, running build ID |
| `0x0752` | `0x0753` | Erase the slot's 16 KiB zone |
| `0x0754` | `0x0755` | Write 128-byte pieces: the payload first, the 32-byte header last |
| `0x0756` | `0x0757` | Validate; the running slot reloads and redraws |

The header goes last, so an interrupted run never leaves a pack that
validates. Run it again to finish.

`--slot N` targets another slot (0–4) instead of the running one. In that case
the build ID can't be checked against the running firmware.

## Status

Tested on a UV-K1(8): it brought back the screen of a v7 slot that a 6.3 tool
had flashed. It was also tested against a simulated radio, where the zone's
contents matched the pack byte for byte.
