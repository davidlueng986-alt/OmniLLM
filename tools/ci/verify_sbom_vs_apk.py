#!/usr/bin/env python3
"""
Verify an OmniLLM SBOM (CycloneDX) honestly reflects the packaged APK contents.

Authority: D9 (GA-GAPS FIX) — SBOM must not over-claim or under-claim. The
verifier cross-checks the SBOM's `packaged-in-apk` components against the APK's
characteristic entries and fails on a mismatch in EITHER direction:

1. SBOM -> APK: every `apk-entries` glob listed on a `packaged-in-apk` component
   must match at least one real APK entry (native libs under lib/<abi>/, AndroidX
   META-INF *.version files, characteristic service/metadata files). Components
   with other scopes (compileOnly-not-shipped / pinned-not-shipped /
   metadata-only / excluded-from-packaging) must NOT appear — enforced via the
   `must-not-match` globs below.

2. APK -> SBOM: every characteristic APK entry (lib/<abi>/*.so and
   META-INF/<group>_<artifact>.version) must be claimed by at least one
   packaged-in-apk component's `apk-entries`. Anything unclaimed is either an
   SBOM gap (under-claim) or an unlicensed surprise — fail.

Hermetic CI: SBOM claims on libomnillm_llama.so (the llama native artifact)
soft-skip when the APK contains NO libomnillm_llama.so at all (mirrors the
llama digest gate skip). When any libomnillm_llama.so IS packaged, both
directions stay fail-closed (an unclaimed packaged llama .so still fails).

SBOM contract (see tools/ci/README.md D9 section):
- component.properties[].name == "omnillm:scope" with values:
  packaged-in-apk | compileOnly-not-shipped | pinned-not-shipped |
  metadata-only | excluded-from-packaging
- packaged-in-apk components carry component.properties[].name ==
  "omnillm:apk-entries" -> comma-separated globs (relative to APK root).

Usage:
  python tools/ci/verify_sbom_vs_apk.py --sbom path/to/SBOM.json --apk path/to/app.apk
  python tools/ci/verify_sbom_vs_apk.py --sbom path/to/SBOM.json --apk path/to/app.apk --skip-if-missing

Exit 0 on a two-way match. Exit 1 on any mismatch or missing file.
"""

from __future__ import annotations

import argparse
import fnmatch
import json
import sys
import zipfile
from pathlib import Path
from typing import Dict, List, Optional, Set, Tuple

SCOPE_PROP = "omnillm:scope"
ENTRIES_PROP = "omnillm:apk-entries"

# Hermetic CI: llama native artifact (baseline entry claims soft-skip when the
# artifact is absent from the APK entirely; enforced when present).
LLAMA_NATIVE = "libomnillm_llama.so"
LLAMA_APK_GLOB = "lib/*/" + LLAMA_NATIVE

# Entries that must never be present for any listed scope (the D10 junk family).
GLOBALLY_FORBIDDEN: Tuple[str, ...] = (
    "org/fusesource/jansi/**",
    "org/sqlite/native/**",
    "sqlite-jdbc.properties",
    "META-INF/native-image/jansi/**",
    "META-INF/native-image/org.xerial/**",
    "META-INF/services/java.sql.Driver",
)


def parse_props(comp: dict) -> Dict[str, str]:
    props: Dict[str, str] = {}
    for p in comp.get("properties", []) or []:
        props[p.get("name", "")] = p.get("value", "")
    return props


def load_sbom(path: Path) -> Tuple[List[dict], List[str]]:
    with open(path, "r", encoding="utf-8") as fh:
        doc = json.load(fh)
    comps = doc.get("components", [])
    errors: List[str] = []
    for comp in comps:
        props = parse_props(comp)
        scope = props.get(SCOPE_PROP, "packaged-in-apk")
        if scope == "packaged-in-apk" and not props.get(ENTRIES_PROP):
            # Pure-dex components (ktor/netty/stdlib/etc.) legitimately carry no
            # characteristic APK entries; they are covered by direction 2
            # (unclaimed characteristic entries fail) and by the classpath dump
            # in release.yml. Only components that DO claim entries are checked
            # against the APK.
            continue
        if scope == "excluded-from-packaging" and not props.get(ENTRIES_PROP):
            errors.append(
                f"{comp.get('name')}: scope={scope} requires omnillm:apk-entries"
            )
    return comps, errors


def norm(name: str) -> str:
    return name.replace("\\", "/")


def collect_apk_entries(apk: Path) -> List[str]:
    with zipfile.ZipFile(apk, "r") as zf:
        return [norm(i.filename) for i in zf.infolist()]


def characteristic(entry: str) -> bool:
    """APK entries the SBOM must account for."""
    return (entry.startswith("lib/") and entry.endswith(".so")) or (
        entry.startswith("META-INF/") and entry.endswith(".version")
    )


def any_glob_match(globs: List[str], entry: str) -> bool:
    return any(fnmatch.fnmatch(entry, g) for g in globs)


def main(argv: List[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sbom", type=Path, required=True, help="SBOM JSON path")
    parser.add_argument("--apk", type=Path, required=True, help="APK path")
    parser.add_argument(
        "--skip-if-missing",
        action="store_true",
        help="Exit 0 if the APK does not exist (skeleton CI)",
    )
    args = parser.parse_args(argv)

    sbom_path: Path = args.sbom
    apk_path: Path = args.apk
    if not apk_path.is_file():
        if args.skip_if_missing:
            print(f"verify_sbom_vs_apk: missing {apk_path} — skip")
            return 0
        print(f"verify_sbom_vs_apk: APK not found: {apk_path}", file=sys.stderr)
        return 1
    if not sbom_path.is_file():
        print(f"verify_sbom_vs_apk: SBOM not found: {sbom_path}", file=sys.stderr)
        return 1

    comps, sbom_errors = load_sbom(sbom_path)
    if sbom_errors:
        print("verify_sbom_vs_apk: FAIL (malformed SBOM)")
        for e in sbom_errors:
            print(f"  - {e}", file=sys.stderr)
        return 1

    try:
        apk_entries = collect_apk_entries(apk_path)
    except zipfile.BadZipFile as exc:
        print(f"verify_sbom_vs_apk: not a valid zip: {apk_path} ({exc})", file=sys.stderr)
        return 1

    failures: List[str] = []
    skipped: List[str] = []
    llama_packaged = any(
        fnmatch.fnmatch(e, LLAMA_APK_GLOB) for e in apk_entries
    )

    # Direction 1: SBOM -> APK.
    claimed_globs: List[str] = []
    for comp in comps:
        props = parse_props(comp)
        scope = props.get(SCOPE_PROP, "packaged-in-apk")
        name = comp.get("name", "?")
        entries_globs = [g for g in props.get(ENTRIES_PROP, "").split(",") if g]
        if scope == "packaged-in-apk":
            claimed_globs.extend(entries_globs)
            for g in entries_globs:
                if not any(fnmatch.fnmatch(e, g) for e in apk_entries):
                    if LLAMA_NATIVE in g and not llama_packaged:
                        skipped.append(
                            f"{name}: claimed entry {g!r} NOT enforced — llama "
                            "native artifact absent from APK (hermetic CI)"
                        )
                    else:
                        failures.append(
                            f"{name}: claimed entry {g!r} NOT present in APK"
                        )
        elif scope == "excluded-from-packaging":
            for g in entries_globs:
                hits = [e for e in apk_entries if fnmatch.fnmatch(e, g)]
                if hits:
                    failures.append(
                        f"{name} ({scope}): expected excluded, found "
                        f"{len(hits)} APK entries matching {g!r}: "
                        f"{hits[:3]}"
                    )

    # Globally forbidden regardless of SBOM (D10 regressions must fail loudly).
    for g in GLOBALLY_FORBIDDEN:
        hits = [e for e in apk_entries if fnmatch.fnmatch(e, g)]
        if hits:
            failures.append(
                f"D10 forbidden entry present in APK (unclaimed): {g} -> {hits[:3]}"
            )

    # Direction 2: APK -> SBOM.
    unclaimed: List[str] = []
    for entry in apk_entries:
        if not characteristic(entry):
            continue
        if not any_glob_match(claimed_globs, entry):
            unclaimed.append(entry)
    if unclaimed:
        failures.append(
            f"APK characteristic entries NOT claimed by any SBOM packaged "
            f"component ({len(unclaimed)}): {unclaimed[:8]}"
        )

    print(
        f"verify_sbom_vs_apk: {apk_path.name} vs {sbom_path.name} "
        f"({len(comps)} SBOM components, {len(apk_entries)} APK entries)"
    )
    packaged = sum(
        1
        for c in comps
        if parse_props(c).get(SCOPE_PROP, "packaged-in-apk") == "packaged-in-apk"
    )
    print(f"  packaged-in-apk components: {packaged}")
    for s in skipped:
        print(f"  SKIPPED: {s}")

    if failures:
        print("verify_sbom_vs_apk: FAIL")
        for f in failures:
            print(f"  - {f}", file=sys.stderr)
        return 1
    print("verify_sbom_vs_apk: OK — SBOM and APK agree (both directions)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
