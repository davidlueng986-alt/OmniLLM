#!/usr/bin/env bash
# OmniLLM local CI parity with .github/workflows/ci.yml
#
# Usage (from repo root):
#   bash tools/ci/local_ci.sh
#   bash tools/ci/local_ci.sh --skip-assemble
#   bash tools/ci/local_ci.sh --skip-lint
#
# Requirements: JDK 17+, Python 3 + tools/codegen/requirements.txt,
#   Android SDK (for assemble / lint) with platform 36 + build-tools 36.0.0.
# Optional: NDK 28.2.x when native .so are present.
#
# Exit non-zero on any gate failure (fail closed).

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

SKIP_ASSEMBLE=0
SKIP_LINT=0
SKIP_RELEASE=0

for arg in "$@"; do
  case "$arg" in
    --skip-assemble) SKIP_ASSEMBLE=1 ;;
    --skip-lint) SKIP_LINT=1 ;;
    --skip-release) SKIP_RELEASE=1 ;;
    -h|--help)
      sed -n '1,20p' "$0"
      exit 0
      ;;
    *)
      echo "Unknown arg: $arg" >&2
      exit 2
      ;;
  esac
done

if [[ -x "./gradlew" ]]; then
  GW=(./gradlew)
elif [[ -f "./gradlew" ]]; then
  GW=(bash ./gradlew)
else
  echo "gradlew missing - run: gradle wrapper --gradle-version 9.5.0" >&2
  exit 1
fi

# Prefer python3; allow override via OMNILLM_PYTHON or -Pomnillm.python
if [[ -n "${OMNILLM_PYTHON:-}" ]]; then
  PYTHON="$OMNILLM_PYTHON"
elif command -v python3 >/dev/null 2>&1; then
  PYTHON=python3
elif command -v python >/dev/null 2>&1; then
  PYTHON=python
else
  echo "Python 3 required for contract codegen / 16 KB gates" >&2
  exit 1
fi

echo "==> [local_ci] repo: $ROOT"
echo "==> [local_ci] python: $PYTHON ($("$PYTHON" --version 2>&1))"

echo "==> [0/11] Dependency edges (INV-001 / engines→data)"
"$PYTHON" tools/ci/check_dependency_edges.py

echo "==> [1/11] Specs authority present"
for f in \
  specs/canonical-types.yaml \
  specs/error-catalog.yaml \
  specs/state-machines.yaml \
  specs/access-control-catalog.yaml \
  specs/capability-catalog.yaml \
  specs/capability-availability-matrix.yaml \
  specs/engine-qualification-status.yaml \
  specs/openapi/omnillm.openapi.yaml \
  specs/aidl/omnillm-aidl.yaml \
  specs/platform-policy-register.yaml \
  specs/database/omnillm-schema.sql
do
  test -f "$f" || { echo "MISSING: $f" >&2; exit 1; }
done

echo "==> [2/11] Install codegen deps (idempotent)"
"$PYTHON" -m pip install -q -r tools/codegen/requirements.txt

echo "==> [3/11] Contract drift gate (BEFORE regenerate)"
"${GW[@]}" checkContractDrift -Pomnillm.python="$PYTHON" --stacktrace

echo "==> [4/11] generateContracts + clean generated tree"
"${GW[@]}" generateContracts -Pomnillm.python="$PYTHON" --stacktrace
if ! git diff --quiet -- \
  'core/canonical/src/main/kotlin/com/omnillm/core/canonical/generated' \
  'core/state/src/main/kotlin/com/omnillm/core/state/generated' \
  'core/errors/src/main/kotlin/com/omnillm/core/errors/generated'
then
  echo "FAIL: generated sources dirty after generateContracts" >&2
  git --no-pager diff -- \
    'core/canonical/src/main/kotlin/com/omnillm/core/canonical/generated' \
    'core/state/src/main/kotlin/com/omnillm/core/state/generated' \
    'core/errors/src/main/kotlin/com/omnillm/core/errors/generated' | head -n 200
  exit 1
fi

echo "==> [5/11] Module dependency boundary gate (INV-001 / ADR-010)"
"${GW[@]}" checkModuleDependencyRules -Pomnillm.python="$PYTHON" --stacktrace

echo "==> [6/11] Dependency edges (Gradle task + script)"
"${GW[@]}" checkDependencyEdges -Pomnillm.python="$PYTHON" --stacktrace

echo "==> [7/11] Unit tests (JVM + Android testDebugUnitTest) - fail closed"
"${GW[@]}" test -Pomnillm.python="$PYTHON" --stacktrace --continue

echo "==> [8/11] Root check (drift + native 16 KB + dependency rules)"
"${GW[@]}" check -Pomnillm.python="$PYTHON" --stacktrace

if [[ "$SKIP_LINT" -eq 0 ]]; then
  echo "==> [9/11] Android lint (app modules); detekt intentionally skipped"
  "${GW[@]}" \
    :android:app-ui:lintDebug \
    :android:companion-sandbox:lintDebug \
    -Pomnillm.python="$PYTHON" --stacktrace
else
  echo "==> [9/11] Android lint SKIPPED (--skip-lint)"
fi

if [[ "$SKIP_ASSEMBLE" -eq 0 ]]; then
  echo "==> [10/11] assembleDebug (+ release unless --skip-release)"
  "${GW[@]}" \
    :android:app-ui:assembleDebug \
    :android:companion-sandbox:assembleDebug \
    -Pomnillm.python="$PYTHON" --stacktrace
  if [[ "$SKIP_RELEASE" -eq 0 ]]; then
    "${GW[@]}" \
      :android:app-ui:assembleRelease \
      :android:companion-sandbox:assembleRelease \
      -Pomnillm.python="$PYTHON" --stacktrace
  fi

  echo "==> [11/11] Native 16 KB APK zip-align"
  "$PYTHON" tools/ci/check_elf_16kb_alignment.py --min-align 16384
  shopt -s nullglob
  apks=(
    android/app-ui/build/outputs/apk/debug/*.apk
    android/app-ui/build/outputs/apk/release/*.apk
    android/companion-sandbox/build/outputs/apk/debug/*.apk
    android/companion-sandbox/build/outputs/apk/release/*.apk
  )
  if [[ ${#apks[@]} -eq 0 ]]; then
    echo "FAIL: no APKs after assemble" >&2
    exit 1
  fi
  for apk in "${apks[@]}"; do
    echo "  zip-align: $apk"
    "$PYTHON" tools/ci/check_apk_16kb_zipalign.py "$apk"
  done
else
  echo "==> [9-10/10] assemble + APK 16 KB SKIPPED (--skip-assemble)"
  "$PYTHON" tools/ci/check_elf_16kb_alignment.py --min-align 16384
fi

echo ""
echo "local_ci: OK (all enabled gates passed)"
