#!/usr/bin/env python3
"""Extract .aidl projections from specs/aidl/omnillm-aidl.yaml.

Authority: specs/aidl/omnillm-aidl.yaml (canonical API surface; kept in sync
with interfaces/aidl/src/main/aidl/ai/omnillm/api/*.aidl — the implementation
truth per API-20).
Output: interfaces/aidl/src/main/aidl/<package>/*.aidl

Usage (from repo root):
  python tools/codegen/extract_aidl.py
  python tools/codegen/extract_aidl.py --out <dir>          # write elsewhere
  python tools/codegen/extract_aidl.py --check              # CI drift gate

--check: regenerate into a temp tree and fail if committed .aidl files would
differ from the spec (same CRLF-normalized comparison as generate_contracts.py).
"""
from __future__ import annotations

import argparse
import difflib
import pathlib
import shutil
import sys
import tempfile

try:
    import yaml
except ImportError:
    print("PyYAML required: pip install pyyaml", file=sys.stderr)
    sys.exit(1)

REPO = pathlib.Path(__file__).resolve().parents[2]
SPEC = REPO / "specs" / "aidl" / "omnillm-aidl.yaml"
OUT = REPO / "interfaces" / "aidl" / "src" / "main" / "aidl"


def load_spec(spec_path: pathlib.Path) -> dict:
    data = yaml.safe_load(spec_path.read_text(encoding="utf-8"))
    if "declarations" not in data:
        raise ValueError(f"{spec_path}: missing 'declarations'")
    return data


def normalize(text: str) -> str:
    return text.replace("\r\n", "\n")


def write_projections(data: dict, out_dir: pathlib.Path) -> list[pathlib.Path]:
    written: list[pathlib.Path] = []
    for decl in data["declarations"]:
        name = decl["name"]
        package = decl.get("package", "ai.omnillm.api")
        source = decl["source"]
        if not source.endswith("\n"):
            source = source + "\n"
        dest_dir = out_dir.joinpath(*package.split("."))
        dest_dir.mkdir(parents=True, exist_ok=True)
        path = dest_dir / f"{name}.aidl"
        path.write_text(normalize(source), encoding="utf-8", newline="\n")
        written.append(path)
    return written


def check_drift(repo_root: pathlib.Path, spec_path: pathlib.Path,
                out_dir: pathlib.Path) -> int:
    data = load_spec(spec_path)
    with tempfile.TemporaryDirectory(prefix="aidl-drift-") as tmp:
        tmp_root = pathlib.Path(tmp)
        generated = {p.relative_to(tmp_root) for p in write_projections(data, tmp_root)}
        committed = {
            p.relative_to(out_dir)
            for p in out_dir.rglob("*.aidl")
            if p.is_file()
        }
        missing = sorted(generated - committed)
        extra = sorted(committed - generated)
        diffs: list[str] = []
        for rel in sorted(generated & committed):
            a = normalize((out_dir / rel).read_text(encoding="utf-8"))
            b = normalize((tmp_root / rel).read_text(encoding="utf-8"))
            if a != b:
                ud = difflib.unified_diff(
                    a.splitlines(), b.splitlines(),
                    fromfile=f"interfaces/aidl/{rel.as_posix()}",
                    tofile=f"generated/{rel.as_posix()}",
                    lineterm="",
                )
                diffs.append("\n".join(list(ud)[:80]))

        if not missing and not extra and not diffs:
            print(
                "AIDL drift check: OK "
                f"({len(generated)} declarations match committed .aidl files)."
            )
            return 0

        print("AIDL drift check: FAILED", file=sys.stderr)
        print(
            "specs/aidl/omnillm-aidl.yaml and committed .aidl files diverged. "
            "Run: python tools/codegen/extract_aidl.py",
            file=sys.stderr,
        )
        if missing:
            print("Missing committed files:", file=sys.stderr)
            for m in missing:
                print(f"  + {m.as_posix()}", file=sys.stderr)
        if extra:
            print("Extra committed files (not produced by spec):", file=sys.stderr)
            for e in extra:
                print(f"  - {e.as_posix()}", file=sys.stderr)
        for d in diffs:
            print(d, file=sys.stderr)
        return 1


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="OmniLLM AIDL projection extractor")
    parser.add_argument(
        "--repo-root",
        type=pathlib.Path,
        default=None,
        help="Repository root (default: parent of tools/)",
    )
    parser.add_argument(
        "--out",
        type=pathlib.Path,
        default=None,
        help="Write .aidl files into this directory instead of interfaces/aidl",
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="Fail if committed .aidl files drift from specs/aidl/omnillm-aidl.yaml",
    )
    args = parser.parse_args(argv)

    script_path = pathlib.Path(__file__).resolve()
    repo_root = (args.repo_root or script_path.parents[2]).resolve()
    spec_path = repo_root / "specs" / "aidl" / "omnillm-aidl.yaml"
    out_dir = (args.out or repo_root / "interfaces" / "aidl" / "src" / "main" / "aidl").resolve()

    if not spec_path.is_file():
        print(f"spec not found under {repo_root}", file=sys.stderr)
        return 2

    if args.check:
        return check_drift(repo_root, spec_path, out_dir)

    if args.out is None and out_dir.is_dir():
        shutil.rmtree(out_dir)
    data = load_spec(spec_path)
    written = write_projections(data, out_dir)
    for p in written:
        try:
            display = p.relative_to(repo_root)
        except ValueError:
            display = p
        print(f"wrote {display}")
    print(f"done: {len(written)} declarations from {spec_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
