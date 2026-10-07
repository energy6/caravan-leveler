#!/usr/bin/env python3
"""Verify 16 KiB PT_LOAD alignment for all 64-bit Android libraries in an APK."""

from __future__ import annotations

import struct
import sys
import zipfile
from pathlib import Path


MINIMUM_ALIGNMENT = 0x4000
ABI_PREFIXES = ("lib/arm64-v8a/", "lib/x86_64/")
PT_LOAD = 1


def load_segment_alignments(elf: bytes, name: str) -> list[int]:
    if elf[:4] != b"\x7fELF":
        raise ValueError(f"{name}: not an ELF file")
    if elf[4] != 2:
        raise ValueError(f"{name}: expected a 64-bit ELF file")

    byte_order = {1: "<", 2: ">"}.get(elf[5])
    if byte_order is None:
        raise ValueError(f"{name}: unsupported ELF byte order {elf[5]}")

    program_header_offset = struct.unpack_from(f"{byte_order}Q", elf, 32)[0]
    program_header_size = struct.unpack_from(f"{byte_order}H", elf, 54)[0]
    program_header_count = struct.unpack_from(f"{byte_order}H", elf, 56)[0]
    if program_header_size < 56:
        raise ValueError(f"{name}: invalid ELF program-header size")

    alignments: list[int] = []
    for index in range(program_header_count):
        offset = program_header_offset + index * program_header_size
        if offset + program_header_size > len(elf):
            raise ValueError(f"{name}: truncated ELF program-header table")
        segment_type = struct.unpack_from(f"{byte_order}I", elf, offset)[0]
        if segment_type == PT_LOAD:
            alignments.append(struct.unpack_from(f"{byte_order}Q", elf, offset + 48)[0])

    if not alignments:
        raise ValueError(f"{name}: no PT_LOAD segments found")
    return alignments


def verify(apk: Path) -> int:
    if not apk.is_file():
        print(f"APK does not exist: {apk}", file=sys.stderr)
        return 2

    failed = False
    checked = 0
    with zipfile.ZipFile(apk) as archive:
        libraries = sorted(
            name
            for name in archive.namelist()
            if name.endswith(".so") and name.startswith(ABI_PREFIXES)
        )
        if not libraries:
            print("No arm64-v8a or x86_64 libraries found in APK.", file=sys.stderr)
            return 2

        for name in libraries:
            try:
                alignments = load_segment_alignments(archive.read(name), name)
            except ValueError as error:
                print(error, file=sys.stderr)
                failed = True
                continue
            checked += 1
            minimum = min(alignments)
            state = "ALIGNED" if minimum >= MINIMUM_ALIGNMENT else "UNALIGNED"
            print(f"{state:9} {name} (minimum PT_LOAD alignment: 0x{minimum:x})")
            failed |= minimum < MINIMUM_ALIGNMENT

    if failed:
        print("16 KiB ELF alignment verification failed.", file=sys.stderr)
        return 1
    print(f"Verified 16 KiB ELF alignment for {checked} 64-bit libraries.")
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(f"Usage: {Path(sys.argv[0]).name} APK", file=sys.stderr)
        raise SystemExit(2)
    raise SystemExit(verify(Path(sys.argv[1])))
