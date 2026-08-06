#!/usr/bin/env python3
"""
Verify uncompressed native libraries inside an APK/AAB are zip-aligned for 16 KB pages.

Authority: ANDROID-NATIVE §2 — use zipalign -c -P 16 (or equivalent).

This script:
1. Prefer invoking Android build-tools `zipalign -c -P 16 -v 4 <apk>` when on PATH
   or ANDROID_HOME is set.
2. Fallback: pure-Python check that stored (uncompressed) `lib/**/*.so` entries
   have local-header data offsets aligned to 16 KB.

Usage:
  python tools/ci/check_apk_16kb_zipalign.py path/to/app.apk
  python tools/ci/check_apk_16kb_zipalign.py --skip-if-missing path/to/app.apk

Exit 0 if APK missing and --skip-if-missing (skeleton CI).
Exit 1 on misalignment or tool failure (unless skip).
"""

from __future__ import annotations

import argparse
import os
import struct
import subprocess
import sys
import zipfile
from pathlib import Path
from typing import List, Optional, Tuple


PAGE = 16384


def find_zipalign() -> Optional[str]:
    from shutil import which

    z = which("zipalign")
    if z:
        return z
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        return None
    build_tools = Path(sdk) / "build-tools"
    if not build_tools.is_dir():
        return None
    versions = sorted(build_tools.iterdir(), reverse=True)
    for v in versions:
        cand = v / ("zipalign.exe" if os.name == "nt" else "zipalign")
        if cand.is_file():
            return str(cand)
    return None


def run_zipalign(zipalign: str, apk: Path) -> Tuple[int, str]:
    # -c check, -P 16 page-size alignment for uncompressed .so (API 35+ tooling)
    cmd = [zipalign, "-c", "-P", "16", "-v", "4", str(apk)]
    try:
        proc = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            check=False,
        )
        out = (proc.stdout or "") + (proc.stderr or "")
        return proc.returncode, out
    except OSError as e:
        return 127, str(e)


def python_check_stored_so(apk: Path) -> List[str]:
    """Fallback: ensure uncompressed lib/**/*.so data offsets % 16384 == 0."""
    issues: List[str] = []
    with zipfile.ZipFile(apk, "r") as zf:
        for info in zf.infolist():
            name = info.filename.replace("\\", "/")
            if not (name.startswith("lib/") and name.endswith(".so")):
                continue
            if info.compress_type != zipfile.ZIP_STORED:
                issues.append(
                    f"{name}: compressed (store uncompressed for 16 KB page map)"
                )
                continue
            # ZipInfo.header_offset points at local file header.
            # Data starts after local header + name + extra.
            # We re-read the local header from the file for accuracy.
            with open(apk, "rb") as f:
                f.seek(info.header_offset)
                header = f.read(30)
                if len(header) < 30 or header[:4] != b"PK\x03\x04":
                    issues.append(f"{name}: bad local header")
                    continue
                name_len, extra_len = struct.unpack_from("<HH", header, 26)
                data_offset = info.header_offset + 30 + name_len + extra_len
                if data_offset % PAGE != 0:
                    issues.append(
                        f"{name}: data offset {data_offset} not aligned to {PAGE}"
                    )
    return issues


def main(argv: List[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path, help="APK or APK-like ZIP path")
    parser.add_argument(
        "--skip-if-missing",
        action="store_true",
        help="Exit 0 if apk does not exist (skeleton CI)",
    )
    parser.add_argument(
        "--python-only",
        action="store_true",
        help="Do not invoke zipalign binary",
    )
    args = parser.parse_args(argv)

    apk: Path = args.apk
    if not apk.is_file():
        if args.skip_if_missing:
            print(f"check_apk_16kb_zipalign: missing {apk} — skip")
            return 0
        print(f"check_apk_16kb_zipalign: file not found: {apk}", file=sys.stderr)
        return 1

    if not args.python_only:
        zipalign = find_zipalign()
        if zipalign:
            code, out = run_zipalign(zipalign, apk)
            print(out)
            if code == 0:
                print("check_apk_16kb_zipalign: OK (zipalign -c -P 16)")
                return 0
            print(
                f"check_apk_16kb_zipalign: zipalign failed rc={code}",
                file=sys.stderr,
            )
            return 1
        print("check_apk_16kb_zipalign: zipalign not found — pure Python fallback")

    issues = python_check_stored_so(apk)
    if issues:
        print("check_apk_16kb_zipalign: FAIL")
        for i in issues:
            print(f"  - {i}")
        return 1
    print("check_apk_16kb_zipalign: OK (python fallback)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
