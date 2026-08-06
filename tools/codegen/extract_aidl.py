#!/usr/bin/env python3
"""Extract .aidl projections from specs/aidl/omnillm-aidl.yaml.

Authority: specs/aidl/omnillm-aidl.yaml (product package copy under repo specs/).
Output: interfaces/aidl/src/main/aidl/<package>/*.aidl

Usage (from repo root):
  python tools/codegen/extract_aidl.py
"""
from __future__ import annotations

import pathlib
import sys

try:
    import yaml
except ImportError:
    print("PyYAML required: pip install pyyaml", file=sys.stderr)
    sys.exit(1)

REPO = pathlib.Path(__file__).resolve().parents[2]
SPEC = REPO / "specs" / "aidl" / "omnillm-aidl.yaml"
OUT = REPO / "interfaces" / "aidl" / "src" / "main" / "aidl"


def main() -> int:
    data = yaml.safe_load(SPEC.read_text(encoding="utf-8"))
    declarations = data["declarations"]
    written = 0
    for decl in declarations:
        name = decl["name"]
        package = decl.get("package", "ai.omnillm.api")
        source = decl["source"]
        if not source.endswith("\n"):
            source = source + "\n"
        dest_dir = OUT.joinpath(*package.split("."))
        dest_dir.mkdir(parents=True, exist_ok=True)
        path = dest_dir / f"{name}.aidl"
        path.write_text(source, encoding="utf-8")
        written += 1
        print(f"wrote {path.relative_to(REPO)}")
    print(f"done: {written} declarations from {SPEC.relative_to(REPO)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
