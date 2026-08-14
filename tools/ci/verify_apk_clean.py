#!/usr/bin/env python3
"""
Verify the release APK is packaging-clean (D10) and carries the C-07 engine natives.

Authority: D10 (GA-GAPS FIX) — JVM-only runtime garbage must not ship in the APK;
C-07 — litertlm / onnxruntime-genai / onnxruntime-android natives ARE packaged.

The check (fail-closed, both directions):

1. FORBIDDEN (D10) — these entries must NOT appear anywhere in the APK:
   - jansi payloads: `org/fusesource/jansi/**` (Mac/Windows .dll/.jnilib natives,
     properties, txt) + `META-INF/native-image/jansi/**` (Graal config)
     <- io.ktor:ktor-server-core runtime scope; never used on Android.
   - sqlite-jdbc JVM payloads: `org/sqlite/native/**` (Mac/Windows 6 MB natives),
     `sqlite-jdbc.properties`, `META-INF/native-image/org.xerial/**` (Graal
     config), `META-INF/services/java.sql.Driver` (DriverManager registration)
     <- app.cash.sqldelight:sqlite-driver <- :data:persistence.
     Class files (org/sqlite/*.class, org/fusesource/jansi/*.class) are allowed to
     remain — they are inert on Android and kept for JVM-side linkage.

2. REQUIRED (C-07) — these natives MUST be present in BOTH arm64-v8a and x86_64:
   liblitertlm_jni.so (com.google.ai.edge.litertlm:litertlm-android:0.15.0),
   libonnxruntime-genai.so + libonnxruntime-genai-jni.so
   (onnxruntime-genai-android-0.14.0.aar),
   libonnxruntime.so + libonnxruntime4j_jni.so
   (com.microsoft.onnxruntime:onnxruntime-android:1.25.1).

3. REQUIRED (baseline) — libomnillm_llama.so (both ABIs), libandroidx.graphics.path.so,
   libc++_shared.so (both ABIs), plus libomp.so / libgojni.so / libMllm*.so.

4. INVARIANT — mllm stays arm64-v8a-only (UBIQUITOUS/mllm jniLibs ship no x86_64;
   any appearance under x86_64 is a packaging regression).

Usage:
  python tools/ci/verify_apk_clean.py path/to/app.apk
  python tools/ci/verify_apk_clean.py --skip-if-missing path/to/app.apk

Exit 0 on clean+complete APK. Exit 1 on any forbidden entry, missing required
native, invariant violation, or unreadable input — never silently skip.
"""

from __future__ import annotations

import argparse
import sys
import zipfile
from pathlib import Path
from typing import Dict, List, Set, Tuple

# --- D10 forbidden entries (patterns matched against normalized APK entry names) ---
FORBIDDEN_PREFIXES: Tuple[str, ...] = (
    "org/fusesource/jansi/",
    "org/sqlite/native/",
    "META-INF/native-image/jansi/",
    "META-INF/native-image/org.xerial/",
)
FORBIDDEN_EXACT: Tuple[str, ...] = (
    "sqlite-jdbc.properties",
    "META-INF/services/java.sql.Driver",
)

# --- C-07 required engine natives per ABI ---
C07_NATIVES: Tuple[str, ...] = (
    "liblitertlm_jni.so",
    "libonnxruntime-genai.so",
    "libonnxruntime-genai-jni.so",
    "libonnxruntime.so",
    "libonnxruntime4j_jni.so",
)

# --- Baseline natives that must keep shipping (BLD-10 / BLD-13 / C-07) ---
BASELINE_BOTH_ABIS: Tuple[str, ...] = (
    "libomnillm_llama.so",
    "libandroidx.graphics.path.so",
    "libc++_shared.so",
)
BASELINE_ARM64_ONLY: Tuple[str, ...] = (
    "libomp.so",  # LLVM OpenMP (mllm runtime dep)
    "libgojni.so",  # Go runtime (mllm_server.aar)
    "libMllmCPUBackend.so",
    "libMllmRT.so",
    "libMllmSdkC.so",
)

ABIS: Tuple[str, ...] = ("arm64-v8a", "x86_64")


def norm(name: str) -> str:
    return name.replace("\\", "/")


def check_forbidden(names: List[str]) -> List[str]:
    issues: List[str] = []
    for n in names:
        nn = norm(n)
        if any(nn.startswith(p) for p in FORBIDDEN_PREFIXES) or nn in FORBIDDEN_EXACT:
            issues.append(f"FORBIDDEN D10 entry present: {nn}")
    return issues


def collect_natives(names: List[str]) -> Dict[str, Set[str]]:
    per_abi: Dict[str, Set[str]] = {abi: set() for abi in ABIS}
    for n in names:
        nn = norm(n)
        if nn.startswith("lib/") and nn.endswith(".so"):
            parts = nn.split("/")
            if len(parts) == 3 and parts[1] in per_abi:
                per_abi[parts[1]].add(parts[2])
    return per_abi


def check_required(natives: Dict[str, Set[str]]) -> List[str]:
    issues: List[str] = []
    for abi in ABIS:
        for lib in C07_NATIVES:
            if lib not in natives[abi]:
                issues.append(f"MISSING C-07 native lib/{abi}/{lib}")
        for lib in BASELINE_BOTH_ABIS:
            if lib not in natives[abi]:
                issues.append(f"MISSING baseline native lib/{abi}/{lib}")
    for abi in ABIS:
        for lib in BASELINE_ARM64_ONLY:
            if abi == "arm64-v8a":
                if lib not in natives[abi]:
                    issues.append(f"MISSING baseline native lib/{abi}/{lib}")
            elif lib in natives[abi]:
                issues.append(f"UNEXPECTED native lib/{abi}/{lib} (arm64-only invariant)")
    return issues


def main(argv: List[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path, help="APK to verify")
    parser.add_argument(
        "--skip-if-missing",
        action="store_true",
        help="Exit 0 if apk does not exist (skeleton CI)",
    )
    args = parser.parse_args(argv)

    apk: Path = args.apk
    if not apk.is_file():
        if args.skip_if_missing:
            print(f"verify_apk_clean: missing {apk} — skip")
            return 0
        print(f"verify_apk_clean: file not found: {apk}", file=sys.stderr)
        return 1

    try:
        with zipfile.ZipFile(apk, "r") as zf:
            names = zf.namelist()
    except zipfile.BadZipFile as exc:
        print(f"verify_apk_clean: not a valid zip: {apk} ({exc})", file=sys.stderr)
        return 1

    forbidden = check_forbidden(names)
    natives = collect_natives(names)
    required = check_required(natives)

    print(f"verify_apk_clean: {apk.name} ({len(names)} entries)")
    for abi in ABIS:
        print(
            f"  natives {abi}: {len(natives[abi])} -> "
            + ", ".join(sorted(natives[abi]))
        )

    if forbidden:
        print("verify_apk_clean: FAIL (D10 forbidden entries)")
        for f in forbidden:
            print(f"  - {f}", file=sys.stderr)
        return 1
    if required:
        print("verify_apk_clean: FAIL (missing/unexpected natives)")
        for f in required:
            print(f"  - {f}", file=sys.stderr)
        return 1
    print("verify_apk_clean: OK — D10-clean and C-07 natives present")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
