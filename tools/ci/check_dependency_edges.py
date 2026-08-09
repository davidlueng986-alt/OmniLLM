#!/usr/bin/env python3
"""
Fail-closed Gradle dependency edge checks (INV-001 / AGENTS.md / ADR-010).

Scans module build.gradle.kts for project("...") edges and rejects known
forbidden pairs. Does not resolve full transitive graphs — only direct
declared edges (fail closed on explicit bad wiring).

Usage (from repo root):
  python tools/ci/check_dependency_edges.py
"""

from __future__ import annotations

import re
import sys
from pathlib import Path
from typing import Dict, List, Set, Tuple

PROJECT_DEP = re.compile(
    r"""(?:api|implementation|compileOnly|runtimeOnly|testImplementation|androidTestImplementation)\s*\(\s*project\s*\(\s*["']([^"']+)["']\s*\)"""
)

# Main (production) configurations only — test-scoped fixtures to :data:*
# are integration-test edges and do not reach shipped classpaths.
MAIN_PROJECT_DEP = re.compile(
    r"""(?:api|implementation|compileOnly|runtimeOnly)\s*\(\s*project\s*\(\s*["']([^"']+)["']\s*\)"""
)

# Direct edges that must never appear (from_module_path_prefix → forbidden dependency).
# Keys are module paths as written in settings/include (":android:app-ui").
FORBIDDEN: List[Tuple[str, str, str]] = [
    (":android:app-ui", ":engines:api", "UI must not depend on engines (INV-001)"),
    (":android:app-ui", ":engines:llama-cpp", "UI must not depend on engine adapters (INV-001)"),
    (":android:app-ui", ":engines:litert-lm", "UI must not depend on engine adapters (INV-001)"),
    (":android:app-ui", ":engines:mlc-llm", "UI must not depend on engine adapters (INV-001)"),
    (":android:app-ui", ":engines:mllm", "UI must not depend on engine adapters (INV-001)"),
    (":android:app-ui", ":engines:ort-genai", "UI must not depend on engine adapters (INV-001)"),
    (":android:app-ui", ":data:persistence", "UI must not depend on domain DB (INV-001 / ADR-010)"),
    (":android:app-ui", ":data:model-store", "UI must not depend on model-store writers (INV-001)"),
    (":android:workers", ":data:persistence", "Workers must not open domain DB (ADR-010)"),
    (":android:workers", ":data:model-store", "Workers must not hold model-store writers (ADR-010)"),
    (":android:companion-sandbox", ":data:persistence", "Companion must not open domain DB (ADR-007)"),
    (":android:companion-sandbox", ":data:model-store", "Companion must not hold model-store (ADR-007)"),
    (":android:parser-isolated", ":data:persistence", "Isolated parser must not open domain DB"),
    (":android:parser-isolated", ":data:model-store", "Isolated parser must not hold model-store"),
    (":engines:llama-cpp", ":data:persistence", "Engines must not depend on Room/SQL DAOs"),
    (":engines:litert-lm", ":data:persistence", "Engines must not depend on Room/SQL DAOs"),
    (":engines:mlc-llm", ":data:persistence", "Engines must not depend on Room/SQL DAOs"),
    (":engines:mllm", ":data:persistence", "Engines must not depend on Room/SQL DAOs"),
    (":engines:ort-genai", ":data:persistence", "Engines must not depend on Room/SQL DAOs"),
    (":engines:api", ":data:persistence", "Engine API must not depend on persistence"),
    (":engines:api", ":data:model-store", "Engine API must not depend on model-store"),
]

# Soft / residual risks reported as warnings (not fail): intentional process merge.
WARNINGS: List[Tuple[str, str, str]] = [
    (
        ":android:app-ui",
        ":android:runtime-service",
        "UI depends on runtime-service for process/manifest merge; ensure no UI-process control plane attach",
    ),
    (
        ":features:modelhub",
        ":data:model-store",
        "Feature Pack touches model-store types; host only via control-plane ports in :runtime (ARC-02 documented debt)",
    ),
]


def module_path_from_build_file(root: Path, build_file: Path) -> str:
    rel = build_file.parent.relative_to(root).as_posix()
    if rel == ".":
        return ":"
    return ":" + rel.replace("/", ":")


def collect_edges(root: Path) -> Dict[str, Set[str]]:
    edges: Dict[str, Set[str]] = {}
    for build in root.rglob("build.gradle.kts"):
        # Skip build outputs
        parts = set(build.parts)
        if "build" in parts and build.name == "build.gradle.kts":
            # still allow module roots only
            pass
        # Ignore anything under */build/* intermediates
        if any(p == "build" for p in build.relative_to(root).parts[:-1]):
            continue
        text = build.read_text(encoding="utf-8")
        mod = module_path_from_build_file(root, build)
        deps = set(PROJECT_DEP.findall(text))
        if deps:
            edges[mod] = deps
    return edges


def collect_main_edges(root: Path) -> Dict[str, Set[str]]:
    """Direct edges declared in production configurations only (ARC-02 gate)."""
    edges: Dict[str, Set[str]] = {}
    for build in root.rglob("build.gradle.kts"):
        if any(p == "build" for p in build.relative_to(root).parts[:-1]):
            continue
        text = build.read_text(encoding="utf-8")
        mod = module_path_from_build_file(root, build)
        deps = set(MAIN_PROJECT_DEP.findall(text))
        if deps:
            edges[mod] = deps
    return edges


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    edges = collect_edges(root)
    main_edges = collect_main_edges(root)
    failures: List[str] = []
    warns: List[str] = []

    for src, dst, reason in FORBIDDEN:
        if dst in edges.get(src, set()):
            failures.append(f"FORBIDDEN {src} → {dst}: {reason}")

    # Engines/* (including :engines:api) must never depend on any :data:*.
    for mod, deps in edges.items():
        if mod.startswith(":engines:"):
            for d in sorted(deps):
                if d.startswith(":data:"):
                    failures.append(
                        f"FORBIDDEN {mod} → {d}: engines must not depend on data layer"
                    )

    # UI must not directly depend on any engines/* or data/* (prefix catch-all).
    ui_deps = edges.get(":android:app-ui", set())
    for d in sorted(ui_deps):
        if d.startswith(":engines:"):
            failures.append(
                f"FORBIDDEN :android:app-ui → {d}: UI must not depend on engines (INV-001)"
            )
        if d.startswith(":data:"):
            failures.append(
                f"FORBIDDEN :android:app-ui → {d}: UI must not depend on data/* (INV-001)"
            )

    # Feature Packs / transport facades must not compile against :data:* writers
    # (ARC-02 / AGENTS.md dependency rules). Production configurations only;
    # test-only fixtures are integration edges and stay allowed.
    for mod, deps in main_edges.items():
        if mod.startswith(":features:") or mod.startswith(":interfaces:"):
            for d in sorted(deps):
                if d == ":data:persistence":
                    failures.append(
                        f"FORBIDDEN {mod} → {d}: feature/interface must not depend on "
                        f"data writers (ARC-02 / INV-001); consume :core:ports or :runtime ports"
                    )
                elif d == ":data:model-store":
                    warns.append(
                        f"WARN {mod} → {d}: model-store types on feature classpath "
                        f"(ARC-02 documented debt; host via control-plane ports)"
                    )

    for src, dst, reason in WARNINGS:
        if dst in edges.get(src, set()):
            warns.append(f"WARN {src} → {dst}: {reason}")

    for w in warns:
        print(w)
    if failures:
        print(f"check_dependency_edges: FAIL ({len(failures)} issue(s))")
        for f in failures:
            print(f"  {f}")
        return 1

    print(
        f"check_dependency_edges: OK modules_with_project_deps={len(edges)} "
        f"warnings={len(warns)}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
