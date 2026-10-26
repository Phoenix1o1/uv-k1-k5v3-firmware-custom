#!/usr/bin/env python3
"""Write the firmware asset pack (fonts, menu texts) of an F4HWN v7 firmware
into the asset zone of its multiboot slot, over the radio's serial link.

A v7 firmware flashed into a slot by a 6.3-era tool boots with an empty asset
zone: keys beep but the screen is blank. Boot that slot, connect the cable
(or USB-C), and run:

    python3 write_fw_assets.py COM5 f4hwn.testlab.bin
    python3 write_fw_assets.py /dev/ttyUSB0 f4hwn.testlab.bin

The file can be the release package (.bin: image + pack + "FWAT" trailer) or
the bare asset pack (.assets.bin, starts with "FWA1"). It must come from the
exact build that is running: the firmware refuses a pack with another build id.

Protocol (App/app/uart.c, App/driver/mb_flash.c):
  0x0514  session handshake, latches the timestamp the write commands require
  0x0750  asset info     -> 0x0751
  0x0752  asset erase    -> 0x0753   (16 KiB zone)
  0x0754  asset write    -> 0x0755   (payload first, 32-byte header last)
  0x0756  asset validate -> 0x0757   (the running slot reloads and redraws)

Requires pyserial:  pip install pyserial
"""

from __future__ import annotations

import argparse
import random
import struct
import sys
import time
import zlib
from pathlib import Path

try:
    import serial
except ImportError:
    sys.exit("pyserial is missing: pip install pyserial")

BAUD = 38400
RUNNING_SLOT = 0xFF
CHUNK = 128                     # bytes per write; the radio's RX buffer is 256

PACK_MAGIC = 0x31415746         # "FWA1"
PACK_VERSION = 1
PACK_HEADER_SIZE = 32
TRAILER_MAGIC = 0x54415746      # "FWAT"
TRAILER_SIZE = 16
ZONE_SIZE = 0x4000              # MB_ASSET_SLOT_SIZE

OBFUSCATION = bytes([0x16, 0x6C, 0x14, 0xE6, 0x2E, 0x91, 0x0D, 0x40,
                     0x21, 0x35, 0xD5, 0x40, 0x13, 0x03, 0xE9, 0x80])

MB_STATUS = ["OK", "no pack (bad magic)", "bad pack version", "not committed",
             "bad size", "CRC mismatch", "SPI timeout", "bad slot",
             "refused: session timestamp mismatch", "RAM load", "protected",
             "pack built for another firmware (build id mismatch)"]


def status_text(code: int) -> str:
    return MB_STATUS[code] if code < len(MB_STATUS) else f"error {code}"


# ---------------------------------------------------------------------------
# Pack file
# ---------------------------------------------------------------------------

def load_pack(path: Path) -> bytes:
    data = path.read_bytes()

    if len(data) >= TRAILER_SIZE:
        magic, image_size, pack_size, crc = struct.unpack_from("<IIII", data, len(data) - TRAILER_SIZE)
        if magic == TRAILER_MAGIC:
            if image_size + pack_size + TRAILER_SIZE != len(data):
                sys.exit(f"{path.name}: FWAT trailer sizes do not match the file")
            if zlib.crc32(data[:image_size + pack_size]) & 0xFFFFFFFF != crc:
                sys.exit(f"{path.name}: package CRC mismatch, the file is damaged")
            data = data[image_size:image_size + pack_size]

    if len(data) < PACK_HEADER_SIZE:
        sys.exit(f"{path.name}: no asset pack found")
    magic, version, hdr_size, payload_size, payload_crc = struct.unpack_from("<IHHII", data, 0)
    if magic != PACK_MAGIC:
        sys.exit(f"{path.name}: not a v7 release package or asset pack (no FWAT/FWA1)")
    if version != PACK_VERSION or hdr_size != PACK_HEADER_SIZE:
        sys.exit(f"{path.name}: unsupported pack version {version}")
    if PACK_HEADER_SIZE + payload_size != len(data) or len(data) > ZONE_SIZE:
        sys.exit(f"{path.name}: pack size does not match its header")
    if zlib.crc32(data[PACK_HEADER_SIZE:]) & 0xFFFFFFFF != payload_crc:
        sys.exit(f"{path.name}: pack payload CRC mismatch")
    return data


# ---------------------------------------------------------------------------
# Serial framing: AB CD | size | obfuscated(message + CRC16) | DC BA
# ---------------------------------------------------------------------------

def crc16(data: bytes) -> int:
    crc = 0
    for b in data:
        crc ^= b << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1021) if crc & 0x8000 else (crc << 1)
            crc &= 0xFFFF
    return crc


def obfuscate(data: bytes) -> bytes:
    return bytes(b ^ OBFUSCATION[i % 16] for i, b in enumerate(data))


def make_packet(msg_id: int, payload: bytes) -> bytes:
    msg = struct.pack("<HH", msg_id, len(payload)) + payload
    if len(msg) % 2:
        msg += b"\x00"
    body = msg + struct.pack("<H", crc16(msg))
    return b"\xAB\xCD" + struct.pack("<H", len(msg)) + obfuscate(body) + b"\xDC\xBA"


class Radio:
    def __init__(self, port: str, timeout: float):
        self.ser = serial.Serial(port, BAUD, timeout=0.05)
        self.timeout = timeout
        self.rx = bytearray()
        self.ts = random.getrandbits(32)

    def close(self):
        self.ser.close()

    def _read_reply(self, want_id: int) -> bytes:
        deadline = time.monotonic() + self.timeout
        while time.monotonic() < deadline:
            self.rx += self.ser.read(256)
            while True:
                start = self.rx.find(b"\xAB\xCD")
                if start < 0:
                    del self.rx[:-1]
                    break
                del self.rx[:start]
                if len(self.rx) < 8:
                    break
                size = self.rx[2] | (self.rx[3] << 8)
                end = 4 + size + 2
                if len(self.rx) < end + 2:
                    break
                if self.rx[end:end + 2] != b"\xDC\xBA":
                    del self.rx[:2]
                    continue
                msg = obfuscate(bytes(self.rx[4:4 + size]))
                del self.rx[:end + 2]
                if len(msg) >= 4 and struct.unpack_from("<H", msg)[0] == want_id:
                    return msg[4:]
        raise TimeoutError(f"no reply 0x{want_id:04X} from the radio")

    def command(self, msg_id: int, payload: bytes, reply_id: int) -> bytes:
        self.ser.write(make_packet(msg_id, payload))
        return self._read_reply(reply_id)

    def handshake(self) -> str:
        reply = self.command(0x0514, struct.pack("<I", self.ts), 0x0515)
        return reply[:16].split(b"\x00")[0].decode("ascii", "replace")

    def asset_info(self, slot: int):
        r = self.command(0x0750, bytes([slot, 0]), 0x0751)
        if len(r) < 4 + PACK_HEADER_SIZE + 16:
            raise RuntimeError("short asset info reply: this firmware has no asset support (not v7?)")
        return {"slot": r[0], "status": r[1], "running": r[2], "loaded": bool(r[3]),
                "zone_header": r[4:4 + PACK_HEADER_SIZE],
                "running_build_id": r[4 + PACK_HEADER_SIZE:4 + PACK_HEADER_SIZE + 16]}

    def asset_erase(self, slot: int) -> int:
        return self.command(0x0752, bytes([slot, 0]) + struct.pack("<I", self.ts), 0x0753)[1]

    def asset_write(self, slot: int, offset: int, data: bytes) -> int:
        payload = bytes([slot, 0]) + struct.pack("<IHI", offset, len(data), self.ts) + data
        return self.command(0x0754, payload, 0x0755)[1]

    def asset_validate(self, slot: int) -> int:
        return self.command(0x0756, bytes([slot, 0]), 0x0757)[1]


# ---------------------------------------------------------------------------

def main() -> int:
    ap = argparse.ArgumentParser(description="Write an F4HWN v7 asset pack into a multiboot slot.")
    ap.add_argument("port", help="serial port, e.g. COM5 or /dev/ttyUSB0")
    ap.add_argument("file", type=Path, help="release package (.bin) or asset pack (.assets.bin)")
    ap.add_argument("--slot", type=int, default=RUNNING_SLOT,
                    help="firmware slot 0-4 (default: the running one)")
    ap.add_argument("--info", action="store_true", help="only show the zone state, write nothing")
    ap.add_argument("--timeout", type=float, default=3.0, help="reply timeout in seconds")
    args = ap.parse_args()

    pack = load_pack(args.file)
    pack_build_id = pack[16:32]
    print(f"Asset pack: {len(pack)} B, build id {pack_build_id.hex()}")

    radio = Radio(args.port, args.timeout)
    try:
        print(f"Radio firmware: {radio.handshake()}")

        info = radio.asset_info(args.slot)
        slot, running = info["slot"], info["running"]
        print(f"Running slot {running}, target slot {slot}: zone {status_text(info['status'])}, "
              f"assets loaded: {'yes' if info['loaded'] else 'no'}")

        if slot == running and info["running_build_id"] != pack_build_id:
            print(f"Running firmware build id {info['running_build_id'].hex()} differs from the pack's.\n"
                  "Use the package from the exact build that is flashed in this slot.")
            return 1
        if args.info:
            return 0

        st = radio.asset_erase(slot)
        if st:
            print(f"Erase failed: {status_text(st)}")
            return 1
        print("Zone erased")

        # Payload first, header last: an interrupted upload never validates.
        payload = pack[PACK_HEADER_SIZE:]
        for off in range(0, len(payload), CHUNK):
            st = radio.asset_write(slot, PACK_HEADER_SIZE + off, payload[off:off + CHUNK])
            if st:
                print(f"\nWrite failed at offset {PACK_HEADER_SIZE + off}: {status_text(st)}")
                return 1
            print(f"\rWriting {min(off + CHUNK, len(payload))}/{len(payload)} B", end="", flush=True)
        print()
        st = radio.asset_write(slot, 0, pack[:PACK_HEADER_SIZE])
        if st:
            print(f"Header write failed: {status_text(st)}")
            return 1

        st = radio.asset_validate(slot)
        print(f"Validate: {status_text(st)}")
        if st == 0 and slot == running:
            print("The radio has reloaded its fonts and texts; the screen should now draw.")
        return 0 if st == 0 else 1
    except (TimeoutError, RuntimeError, serial.SerialException) as e:
        print(f"\n{e}")
        return 1
    finally:
        radio.close()


if __name__ == "__main__":
    sys.exit(main())
