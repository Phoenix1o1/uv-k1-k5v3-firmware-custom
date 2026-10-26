## Channels: browse memories by scan list, and tune one

This PR adds **app API level 4**, which lets overlay apps read the memory channels and scan lists. It also adds **Channels**, an overlay app that uses it. Channels shows the memories the way CHIRP organises them: grouped by scan list, with names and frequencies. You can tune any of them in two key presses.

![Channels: groups, a list, 6.25 kHz channels, tuning](https://raw.githubusercontent.com/Phoenix1o1/uv-k1-k5v3-firmware-custom/pr-images/channels-steps.png)

<sub>Screens rendered from the app's code with the firmware's fonts.</sub>

### Why the app

CHIRP organises the memories into scan lists, but the radio never shows what a list contains. MR mode steps through up to 1024 memories one at a time, and the only trace of the lists on screen is the name of the list being scanned, in the status bar. Finding "the repeater I put in list 03" means scrolling, or remembering channel numbers.

Channels puts the CHIRP organisation on the radio: each list with its channel count, then the channels of a list with names and frequencies. Tuning one takes a single key press.

### Why the main firmware had to change

The app can't do this on its own. An overlay app reaches the radio only through `app_api_t`, and nothing in that table covers the memory channels: `beam_get` and `vfo_stage` only see the current VFO. There's no workaround from inside the app:

- **Reading the memories:** the app runs from the PY25Q16 sector cache and can't drive the flash itself. `sys_storage_read` exists only in builds with the system information, and it would tie the app to the storage layout (channel records, attribute words, list names, the Multiconfig bank mapping), which belongs to the firmware and may change.
- **Tuning a memory:** selecting a memory saves the VFO indices to flash, and the persistence rule in `app_api.h` forbids any flash write while an app runs, because it would overwrite the running app. The change has to be staged and applied by the loader after the app returns.

So this PR adds four small services to the firmware (app API level 4). They're useful beyond Channels: any app can now offer a channel picker (for example APRS, Fox Hunt or Sig Finder).

### API level 4

These services are appended to `app_api_t`. The table only grows, as the header requires: existing fields keep their offsets, the `_Static_assert`s are extended, and every existing app builds and runs unchanged.

| Service | What it does |
|---|---|
| `int16_t mr_list(uint16_t ch)` | Returns the scan list of memory `ch`: `1..24`, `0` for no list, or `APP_MR_EVERY` for a channel in every list (attribute value 25, as `RADIO_IsChannelInScanList` reads it). Returns `APP_MR_EMPTY` for an empty memory and `APP_MR_END` past the last one, so apps don't hard-code 1024. |
| `bool mr_read(uint16_t ch, app_beam_channel_t *out)` | Returns a memory in the same `app_beam_channel_t` that `beam_get` uses: frequency, offset, codes, modulation, power, step, scan list, and the name NUL-terminated. |
| `void list_name(uint8_t list, char out[5])` | Returns a scan list's name with trailing spaces trimmed, or `""` if it has none. |
| `bool mr_stage(uint16_t ch)` | Selects memory `ch` on the current VFO once `app_main()` returns, then goes to the main screen. |

Design notes:

- **Respects the persistence rule.** The reads go through `MR_GetChannelAttributes`, `SETTINGS_FetchChannelScanDisplayInfo` and `SETTINGS_FetchChannelName`, which all read with `PY25Q16_ReadBuffer`. That function bypasses the sector cache the app runs from. `mr_stage` writes nothing while the app runs: the loader applies it in `app_mr_commit()` after the app returns, next to `app_beam_commit()`.
- **Same behaviour as the keypad.** The commit does what `channelMove()` does for a typed channel number (MR/screen channel, RescueOps flags, `RADIO_ConfigureChannel`, deferred `SETTINGS_SaveVfoIndices`).
- **Existing code untouched.** `beam_save` and `vfo_stage` are not modified. `mr_stage` accepts one memory per run, applied after them if an app also used those.
- **Multiconfig-aware.** Reads go through the bank mapping, so an app sees the active `CFG` bank, like the rest of the radio.
- **Doesn't depend on the BEAM bridge.** It's available wherever overlay apps are. `gAppVfoExit` is reused to return to the main screen after a tune.

### The Channels app (2.3 KB of 4 KB)

- **Groups view:** `ALL`, each scan list that holds a channel (`01 RPT`, or `LIST 05` when unnamed), then `NO LIST`, each with its channel count. A channel that is in every list is counted and shown in each list, but doesn't make otherwise empty lists appear.
- **Channels view:** the name (or `CH-n` when unnamed) and RX frequency (3 to 5 decimals, so 6.25 kHz PMR channels show in full), with the position in the group (`2/16`). The bottom line shows the selected channel's number, modulation, bandwidth, offset and tone type.
- **Keys:** UP/DOWN to move (hold to repeat), MENU to open a group or tune the channel, EXIT to go back or quit.
- **Nothing is cached per channel**, since 1024 memories don't fit in the overlay. The cursor is a channel number, and moving it scans the memories in between.
- On firmware older than API level 4 the Apps menu refuses the app cleanly.

### Cost

- **Resident:** +444 B of flash and +0 B of RAM (the new field fits in existing padding), only in builds with `ENABLE_FEAT_F4HWN_OVERLAY_APPS`. Fusion goes from 108,728 B to 109,172 B (90.4% of 118 KB). Custom and Service, which have no overlay apps, are unchanged.

### Files

- `App/apps/app_api.h`: the four services and their documentation (append-only).
- `App/apps/app_overlay.c`: their implementation, and the commit after the app returns.
- `App/apps/chanlist/`: the app (`chanlist_app.c`, plus `build.sh` and `app.ld` like every app), picked up by `compile-app.sh`.

The README's app list isn't touched; that's yours to update if you take it.

### Testing

- **Builds:** based on `feature_update_v7`. All presets build (`Custom`, `Fusion`, `Service`) with no warnings. All 48 apps build with no warnings, including the existing ones against the extended header.
- **Host harness:** `chanlist_app.c` was run against a mock `app_api_t` with the firmware's own fonts. It covered group counts, held-key scrolling to memory 1024, long names, 6.25 kHz frequencies, repeater offset and tone, the empty-list, unnamed-list and every-list cases, and `mr_stage`.
- **On the radio:** tested on a UV-K1(8) with the same change on the previous base (a 6.3 Labs build). The patch applied to `feature_update_v7` without conflicts.

### Possible follow-ups

- Jump to a group with the number keys.
- Start a scan of the selected group from the app.
