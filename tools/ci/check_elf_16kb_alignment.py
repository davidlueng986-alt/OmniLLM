#!/usr/bin/env python3
"""
Scan ELF shared objects for 16 KB page-size LOAD segment alignment (ANDROID-NATIVE).

Authority: specs/platform-policy-register.yaml ANDROID-16KB,
           docs/60-android/native-packaging-16kb.md

Usage:
  python tools/ci/check_elf_16kb_alignment.py [--min-align 16384] [paths...]

If no paths are given, scans common monorepo locations for *.so.
FAIL CLOSED: no .so found is an error (missing packaged natives), not a pass.
Exit 0 when all found .so pass.
Exit 1 when no .so found or any LOAD segment p_align < min-align.
"""

from __future__ import annotations

import argparse
import os
import struct
import sys
from pathlib import Path
from typing import Iterable, List, Optional, Tuple


ELF_MAGIC = b"\x7fELF"
PT_LOAD = 1


def find_so_files(roots: Iterable[Path]) -> List[Path]:
    out: List[Path] = []
    for root in roots:
        if not root.exists():
            continue
        if root.is_file() and root.suffix == ".so":
            out.append(root)
            continue
        for dirpath, _, filenames in os.walk(root):
            for name in filenames:
                if name.endswith(".so"):
                    out.append(Path(dirpath) / name)
    return sorted(set(out))


def read_load_alignments(path: Path) -> Tuple[List[int], Optional[str]]:
    """Return list of PT_LOAD p_align values, or error string."""
    data = path.read_bytes()
    if len(data) < 64 or data[:4] != ELF_MAGIC:
        return [], f"not an ELF file"

    ei_class = data[4]  # 1=32, 2=64
    ei_data = data[5]  # 1=LE, 2=BE
    if ei_data != 1:
        return [], f"unsupported endianness ei_data={ei_data}"

    if ei_class == 1:
        # ELF32
        if len(data) < 52:
            return [], "truncated ELF32 header"
        e_phoff = struct.unpack_from("<I", data, 28)[0]
        e_phentsize = struct.unpack_from("<H", data, 42)[0]
        e_phnum = struct.unpack_from("<H", data, 44)[0]
        aligns: List[int] = []
        for i in range(e_phnum):
            off = e_phoff + i * e_phentsize
            if off + 32 > len(data):
                return [], "truncated program header"
            p_type = struct.unpack_from("<I", data, off)[0]
            if p_type != PT_LOAD:
                continue
            # ELF32 Phdr: p_align at offset 28
            p_align = struct.unpack_from("<I", data, off + 28)[0]
            aligns.append(p_align)
        return aligns, None

    if ei_class == 2:
        # ELF64
        if len(data) < 64:
            return [], "truncated ELF64 header"
        e_phoff = struct.unpack_from("<Q", data, 32)[0]
        e_phentsize = struct.unpack_from("<H", data, 54)[0]
        e_phnum = struct.unpack_from("<H", data, 56)[0]
        aligns = []
        for i in range(e_phnum):
            off = e_phoff + i * e_phentsize
            if off + 56 > len(data):
                return [], "truncated program header"
            p_type = struct.unpack_from("<I", data, off)[0]
            if p_type != PT_LOAD:
                continue
            # ELF64 Phdr: p_align at offset 48
            p_align = struct.unpack_from("<Q", data, off + 48)[0]
            aligns.append(int(p_align))
        return aligns, None

    return [], f"unknown ELF class {ei_class}"


def main(argv: List[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--min-align",
        type=int,
        default=16384,
        help="Minimum PT_LOAD p_align (default 16384 for 16 KB pages)",
    )
    parser.add_argument(
        "paths",
        nargs="*",
        help="Files or directories to scan (default: common monorepo roots)",
    )
    args = parser.parse_args(argv)

    if args.paths:
        roots = [Path(p) for p in args.paths]
    else:
        repo = Path(__file__).resolve().parents[2]
        roots = [
            repo / "android" / "native",
            repo / "engines",
            repo / "build",
        ]

    so_files = find_so_files(roots)
    if not so_files:
        print(
            "check_elf_16kb_alignment: FAIL — no .so files found under "
            f"{[str(r) for r in roots]} (fail closed: packaged natives missing). "
            "Run :android:native:assembleDebug (NDK required) before this gate.",
            file=sys.stderr,
        )
        return 1

    min_align = args.min_align
    failures: List[str] = []
    checked = 0
    for so in so_files:
        aligns, err = read_load_alignments(so)
        checked += 1
        if err:
            failures.append(f"{so}: {err}")
            continue
        if not aligns:
            failures.append(f"{so}: no PT_LOAD segments")
            continue
        for a in aligns:
            if a < min_align:
                failures.append(
                    f"{so}: PT_LOAD p_align={a} < required {min_align}"
                )

    if failures:
        print(f"check_elf_16kb_alignment: FAIL ({len(failures)} issue(s), checked={checked})")
        for f in failures:
            print(f"  - {f}")
        return 1

    print(
        f"check_elf_16kb_alignment: OK checked={checked} min_align={min_align}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
