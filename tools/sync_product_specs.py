#!/usr/bin/env python3
"""Sync docs/product/specs → specs/ (implementation working copy).

Default: copy. --check: exit 1 if trees differ, write nothing.
Does not sync in the reverse direction.
"""
from __future__ import annotations

import argparse
import filecmp
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "docs" / "product" / "specs"
DST = ROOT / "specs"


def iter_files(base: Path) -> list[Path]:
    return sorted(p.relative_to(base) for p in base.rglob("*") if p.is_file())


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--check",
        action="store_true",
        help="report drift only; do not copy",
    )
    args = parser.parse_args()

    if not SRC.is_dir():
        print(f"missing product specs: {SRC}", file=sys.stderr)
        return 2
    if not DST.is_dir():
        print(f"missing implementation specs: {DST}", file=sys.stderr)
        return 2

    src_files = set(iter_files(SRC))
    dst_files = set(iter_files(DST))
    only_src = sorted(src_files - dst_files)
    only_dst = sorted(dst_files - src_files)
    changed = sorted(
        rel
        for rel in (src_files & dst_files)
        if not filecmp.cmp(SRC / rel, DST / rel, shallow=False)
    )

    print(f"product specs: {SRC}")
    print(f"implement specs: {DST}")
    print(f"only in product: {len(only_src)}")
    print(f"only in implement: {len(only_dst)}")
    print(f"content differs: {len(changed)}")
    for label, rows in (
        ("only-product", only_src),
        ("only-implement", only_dst),
        ("differ", changed),
    ):
        for rel in rows[:40]:
            print(f"  {label}: {rel.as_posix()}")
        if len(rows) > 40:
            print(f"  {label}: … {len(rows) - 40} more")

    if args.check:
        return 1 if (only_src or only_dst or changed) else 0

    for rel in src_files:
        dest = DST / rel
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(SRC / rel, dest)
    print("copied product specs → specs/")
    print("next: ./gradlew generateContracts && ./gradlew checkContractDrift")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
