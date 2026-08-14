#!/usr/bin/env python3
"""
Verify the release APK's stripped libomnillm_llama.so digests match UPSTREAM.lock.

Authority: ENGINE-LLAMACPP / ENGINE-STANDARD §4 (supply-chain lock), BLD-D1/D2.

Policy (D2 "stripped-packaged"): the lock's artifactDigest MUST be the SHA-256
of the STRIPPED libomnillm_llama.so exactly as packaged in the release APK
(lib/<abi>/libomnillm_llama.so). The unstripped CMake `obj` artifacts (tens of
MB) are NOT the shipped bytes and must never be recorded in the lock.

This script (fail-closed):
1. Locates the release APK
   (<repo>/android/app-ui/build/outputs/apk/release/*.apk, or --apk override).
   Fallback (CI without assembled APK): the AGP strip task output
   (<repo>/android/app-ui/build/intermediates/stripped_native_libs/release/
   stripReleaseDebugSymbols/out/lib/<abi>/libomnillm_llama.so), which is
   byte-identical to the APK-packaged .so.
2. Extracts lib/<abi>/libomnillm_llama.so per ABI recorded in the lock and
   computes SHA-256.
3. Compares against UPSTREAM.lock artifact.artifactDigest[<abi>].
4. Regression (D1): asserts the pinned upstream short commit (first 7 chars
   of upstream.commit) is embedded in the extracted arm64-v8a .so, proving the
   build-info pin (vendored tree has no .git -> git walk-up would embed the
   wrong commit otherwise).

Usage:
  python tools/ci/verify_llama_digest.py [--repo-root PATH] [--apk PATH]

Exit 0 when every ABI matches. Exit 1 on any mismatch, missing artifact,
unsupported lock shape, or unreadable input — never silently skip.
"""

from __future__ import annotations

import argparse
import glob
import hashlib
import sys
import zipfile
from pathlib import Path
from typing import Dict, List, Optional, Tuple

try:
    import yaml
except ImportError:  # pragma: no cover
    print(
        "verify_llama_digest: PyYAML required (tools/codegen/requirements.txt)",
        file=sys.stderr,
    )
    sys.exit(1)

NATIVE_SO_NAME = "libomnillm_llama.so"
LOCK_REL = Path("engines/llama-cpp/UPSTREAM.lock")
APK_DIR_REL = Path("android/app-ui/build/outputs/apk/release")
STRIPPED_GLOB_REL = (
    "android/app-ui/build/intermediates/stripped_native_libs/release/"
    "*/out/lib/{abi}/" + NATIVE_SO_NAME
)


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def read_lock(repo_root: Path) -> dict:
    lock_path = repo_root / LOCK_REL
    if not lock_path.is_file():
        raise RuntimeError(f"UPSTREAM.lock not found: {lock_path}")
    with open(lock_path, "r", encoding="utf-8") as fh:
        lock = yaml.safe_load(fh)
    if not isinstance(lock, dict):
        raise RuntimeError(f"{lock_path}: lock is not a YAML mapping")
    artifact = lock.get("artifact")
    if not isinstance(artifact, dict):
        raise RuntimeError(f"{lock_path}: missing artifact section")
    digests = artifact.get("artifactDigest")
    if not isinstance(digests, dict):
        raise RuntimeError(
            f"{lock_path}: artifact.artifactDigest must be a per-ABI map "
            f"(variant: {artifact.get('variant')!r}) — "
            "run the D2 recapture flow to record stripped-packaged digests"
        )
    return lock


def find_release_apk(repo_root: Path, apk_override: Optional[Path]) -> Optional[Path]:
    if apk_override is not None:
        if apk_override.is_file():
            return apk_override
        return None
    candidates = sorted((repo_root / APK_DIR_REL).glob("*.apk"))
    if not candidates:
        return None
    preferred = [c for c in candidates if "release" in c.name.lower()]
    return (preferred or candidates)[0]


def extract_so_from_apk(apk: Path, abi: str) -> Optional[bytes]:
    entry_name = f"lib/{abi}/{NATIVE_SO_NAME}"
    with zipfile.ZipFile(apk, "r") as zf:
        for info in zf.infolist():
            name = info.filename.replace("\\", "/")
            if name == entry_name:
                return zf.read(info)
    return None


def find_stripped_so(repo_root: Path, abi: str) -> Optional[Path]:
    hits = glob.glob(str(repo_root / STRIPPED_GLOB_REL.format(abi=abi)))
    return Path(hits[0]) if hits else None


def load_so_bytes(repo_root: Path, apk: Optional[Path], abi: str) -> Tuple[Optional[bytes], str]:
    if apk is not None:
        data = extract_so_from_apk(apk, abi)
        if data is not None:
            return data, f"APK {apk.name} (lib/{abi}/{NATIVE_SO_NAME})"
    stripped = find_stripped_so(repo_root, abi)
    if stripped is not None:
        return stripped.read_bytes(), stripped.relative_to(repo_root).as_posix()
    return None, f"APK={apk.name if apk else None} stripped_native_libs(lib/{abi})"


def check_embedded_commit(data: bytes, upstream_commit: str, abi: str) -> Tuple[bool, str]:
    """D1 regression: pinned short commit must be embedded in the .so bytes."""
    short = upstream_commit[:7]
    if short.encode("ascii") in data:
        return True, f"{short}"
    return False, f"{short} NOT FOUND"


def main(argv: List[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", type=Path, default=None,
                        help="OmniLLM repo root (default: derived from script location)")
    parser.add_argument("--apk", type=Path, default=None,
                        help="Explicit release APK to verify (overrides auto-discovery)")
    args = parser.parse_args(argv)

    repo_root = args.repo_root
    if repo_root is None:
        repo_root = Path(__file__).resolve().parents[2]
    repo_root = repo_root.resolve()

    try:
        lock = read_lock(repo_root)
    except RuntimeError as exc:
        print(f"verify_llama_digest: FAIL — {exc}", file=sys.stderr)
        return 1

    upstream = lock.get("upstream") or {}
    upstream_commit = str(upstream.get("commit") or "")
    digests: Dict[str, str] = lock["artifact"]["artifactDigest"]
    abis = sorted(digests.keys())

    apk = find_release_apk(repo_root, args.apk)
    if apk is None and args.apk is not None:
        print(f"verify_llama_digest: FAIL — explicit APK not found: {args.apk}", file=sys.stderr)
        return 1

    failures: List[str] = []
    lines: List[str] = []
    for abi in abis:
        expected = digests[abi].lower()
        data, source = load_so_bytes(repo_root, apk, abi)
        if data is None:
            msg = f"libomnillm_llama.so not found for {abi} ({source})"
            failures.append(msg)
            lines.append(f"  {abi}: MISSING — {msg}")
            continue
        actual = sha256_bytes(data)
        ok = actual == expected
        lines.append(
            f"  {abi}: {actual[:16]}…{' == ' if ok else ' != '}"
            f"{expected[:16]}… ({'MATCH' if ok else 'MISMATCH'}; source: {source})"
        )
        if not ok:
            failures.append(
                f"{abi}: digest {actual} != lock {expected} (source: {source})"
            )
        if abi == "arm64-v8a" and upstream_commit and ok:
            embedded_ok, detail = check_embedded_commit(data, upstream_commit, abi)
            lines.append(f"    embedded upstream commit (D1): {detail}")
            if not embedded_ok:
                failures.append(
                    f"arm64-v8a: pinned commit {upstream_commit[:7]} not embedded in .so "
                    "(build-info pin regression)"
                )

    print("verify_llama_digest: stripped-packaged digest check")
    print("  lock:", LOCK_REL.as_posix(), f"(variant: {lock['artifact'].get('variant', '?')})")
    print("  source APK:", apk.relative_to(repo_root).as_posix() if apk else "(none — stripped intermediates)")
    print("\n".join(lines))

    if failures:
        print("verify_llama_digest: FAIL")
        for f in failures:
            print(f"  - {f}", file=sys.stderr)
        return 1
    print("verify_llama_digest: OK — every ABI matches the lock")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
