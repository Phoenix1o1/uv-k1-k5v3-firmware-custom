## Scan-list picker: choose the list to scan from a menu

During a memory scan, a short press on `*` opens a list of the scan lists, with their names and channel counts, over the main screen. Choose one with UP/DOWN and press MENU: the scan moves to that list without stopping.

![Scan-list picker: open, choose, scroll](https://raw.githubusercontent.com/Phoenix1o1/uv-k1-k5v3-firmware-custom/pr-images/picker-steps.png)

<sub>Screens rendered from the picker's code with the firmware's fonts; on the radio the status bar stays above it.</sub>

### Why

There are two ways to change list during a scan today:

- **F + UP/DOWN steps through the lists one at a time**, stopping about 1 s on each name. Reaching a list far from the current one means stepping through every list in between.
- **Typing two digits (`00`–`25`) jumps straight to a list**, but only if you remember its number. The radio never shows which number belongs to which list.

Neither shows what the lists are called or how many channels they hold. The picker does both and lets you choose in one place. The two existing methods stay as they are.

### The choices, and why

**Opening: a short `*`, only during a memory scan**
- During a scan a short `*` is unused (it only beeps an error), so no existing binding changes.
- `*` is already the scan key (a long press starts and stops the scan), so the picker sits on the key your thumb is already on.
- Frequency and range scans have no lists, so the picker opens only when the scan runs on memories.

**Drawn over the main screen, not a modal loop**
- A modal loop (like the Apps menu) would stop the main loop, and with it the scanner and RX audio. Drawing over the main screen keeps everything running, so a signal found while you're choosing is still heard.
- It reuses the shape of the existing action picker: state plus a `ProcessKey` hook in `APP_ProcessKey` right after `ACTION_PickerProcessKey`, a draw hook at the top of `UI_DisplayMain`, and a 500 ms tick. That should make it easy to review and maintain next to it.

**Which rows appear**
- `ALL` first, then `MIX` when the mix selects at least one list that holds channels, then the scan lists.
- A list appears only when it holds a channel. An empty list can't be scanned (`RADIO_NextValidList` skips it), so offering it would only bounce the scan somewhere else.
- Named lists show as `01 RPT` and unnamed ones as `LIST 05`, the same as in the Channels app.

**How channels are counted**
- The counts use the scanner's own rules: excluded channels and channels in no list are left out. The number shown is what will actually be scanned.
- A channel assigned to every list (value 25) counts in each list. On its own it doesn't make a list appear, though; otherwise a single "all lists" channel would fill the picker with 24 entries.

**Where the cursor starts**
- It opens on the list being scanned, and that list's name is in bold. You start from where you are, and one press of MENU keeps the current list.

**Navigation**
- UP/DOWN move, wrap around, and repeat when held, like the menu's category list.
- UP always means the previous row, whatever `SetNav` is set to, as in that category list. `SetNav` changes value-style keys; this is a vertical list.

**What MENU does**
- It applies the list with the same steps as the scan key pressed during a scan: set `SCAN_LIST_DEFAULT`, `UI_MAIN_NotifyScanListChanged`, `SETTINGS_WriteCurrentState`, `CHFRSCANNER_ManualResume`.
- The new list's name is shown, it's kept across power cycles with the resume state, and the scan moves on right away instead of sitting on a channel from the old list.

**Closing**
- EXIT closes the picker. So does `*` again, the key that opened it.
- PTT closes it and still transmits: the picker must never delay TX.
- It also closes by itself:
  - when the scan stops or leaves memory mode, since there's nothing left to pick;
  - on key lock, the low-battery popup or when another screen opens, as the action picker does;
  - after 10 s without a key. That's longer than the action picker's 5 s because reading a list of names takes longer than reading one action.

**Other keys are ignored while it's open**
- Digits would jump to another list, and a long MENU would exclude a channel the user can't see behind the picker.

**Resources**
- Counting is one pass over the 1024 memories when the picker opens. Nothing runs while it's closed.
- Its 82 B of state is static rather than on the stack, because the screen is redrawn from it on every refresh while it's open.
- Text formatting uses the firmware's own `printf` (`external/printf`). Newlib's would pull in the C library and a heap.

**Build flag: `ENABLE_FEAT_F4HWN_SCAN_LIST_PICKER`, enabled in no preset**
- Which editions get the picker is your call, so this PR doesn't touch `CMakePresets.json`. To build it, add `"ENABLE_FEAT_F4HWN_SCAN_LIST_PICKER": true` to a preset, or pass `-DENABLE_FEAT_F4HWN_SCAN_LIST_PICKER=ON`.
- `Fusion`, `Fusion_` and the editions built on them have room. `Max` doesn't: with the multiboot stub it would overflow by about 240 B.

### Cost

Nothing until the flag is enabled: every preset builds to exactly the size it has today. With the flag:

| Edition | Flash before | With the picker | Δ |
|---|---|---|---|
| Fusion | 106,796 | 108,008 | +1,212 |
| Labs | 116,940 | 118,148 | +1,208 |

### Files

- `App/app/scan_list_picker.c/.h`: the picker (state, keys, drawing, 500 ms tick).
- `App/app/app.c`, `App/app/main.c`, `App/ui/main.c`: one short, commented hook each (key routing and tick, opening on `*`, drawing).
- `App/CMakeLists.txt`: the build flag.

### Testing

- **Builds:** all presets build with no warnings, unchanged in size; Fusion and Labs also build with the flag on.
- **Host harness:** `scan_list_picker.c` was compiled against stubbed firmware state with the firmware fonts. It covered the counts (excluded, no-list, every-list and unnamed lists, memory 1024), opening on the current list, wrap-around, held repeat, MENU applying and resuming, EXIT/`*`/PTT, swallowed digits, auto-close when the scan stops and on the 10 s timeout, the MIX row hidden when the mix is empty, and falling back to `ALL` when the current list no longer exists.
- **On the radio:** tested on a UV-K1(8).

### Possible follow-up

- Open the picker before a scan, to start one directly in the chosen list.
