#!/usr/bin/env python3
"""
Fail-closed Gradle module dependency boundary gate (OmniLLM Android).

Authority:
  - AGENTS.md § Dependency rules / Forbidden edges
  - ARCH-INVARIANTS INV-001 (UI process / no DB writers from UI surface)
  - ADR-010 single writer (control plane)
  - ADR-007 companion separate UID (no privileged data deps)

Checks (hard, exit 1 on any violation):
  1. Direct edges from :android:app-ui to :data:*, :engines:* (any pack)
  2. Direct edges from :android:companion-sandbox to :data:*, :runtime:*,
     :engines:*, :features:*, :interfaces:http / admin
  3. Any :engines:* (including :engines:api) → :data:persistence or
     :data:model-store
  4. Api-propagated path from :android:app-ui → :data:persistence
     (DB writer types must not appear on UI compile classpath)
  5. Api-propagated path from :android:app-ui → native engine packs
     (:engines:llama-cpp|litert-lm|mlc-llm|mllm|ort-genai)

Soft findings (printed as WARN; do not fail — tracked architectural debt):
  - Api path :android:app-ui → :engines:api (SPI types only; no native load)
  - Api path :android:app-ui → :data:model-store (quarantine key types)

Usage:
  python tools/ci/check_module_dependency_rules.py [--repo-root PATH]

Exit 0 when hard rules pass. Exit 1 on hard violations or parse failure.
"""

from __future__ import annotations

import argparse
import re
import sys
from collections import defaultdict, deque
from pathlib import Path
from typing import Dict, List, Set, Tuple

# project(":foo:bar") or project(":foo:bar") inside api/implementation/…
_DEP_RE = re.compile(
    r"""(?P<config>\b(?:api|implementation|compileOnly|runtimeOnly|testImplementation|testApi|androidTestImplementation)\b)"""
    r"""\s*\(\s*project\s*\(\s*["'](?P<path>:[^"']+)["']\s*\)""",
    re.MULTILINE,
)

NATIVE_ENGINE_PACKS = frozenset(
    {
        ":engines:llama-cpp",
        ":engines:litert-lm",
        ":engines:mlc-llm",
        ":engines:mllm",
        ":engines:ort-genai",
    }
)

DATA_MODULES = frozenset({":data:persistence", ":data:model-store"})


def module_path_from_build_file(repo: Path, build_file: Path) -> str:
    rel = build_file.parent.relative_to(repo).as_posix()
    if rel == ".":
        return ":"
    return ":" + rel.replace("/", ":")


def parse_dependencies(build_file: Path) -> List[Tuple[str, str]]:
    """Return list of (configuration, project_path)."""
    text = build_file.read_text(encoding="utf-8")
    # Strip block comments roughly to avoid matching examples in comments.
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.DOTALL)
    lines = []
    for line in text.splitlines():
        # Drop full-line and trailing // comments.
        if "//" in line:
            line = line[: line.index("//")]
        lines.append(line)
    cleaned = "\n".join(lines)
    out: List[Tuple[str, str]] = []
    for m in _DEP_RE.finditer(cleaned):
        out.append((m.group("config"), m.group("path")))
    return out


def load_graph(repo: Path) -> Tuple[Dict[str, List[Tuple[str, str]]], Set[str]]:
    """module -> [(config, dep)], and set of all modules that have build files."""
    graph: Dict[str, List[Tuple[str, str]]] = defaultdict(list)
    modules: Set[str] = set()
    for build in repo.rglob("build.gradle.kts"):
        # Skip build outputs
        parts = set(build.parts)
        if "build" in parts and build.name == "build.gradle.kts":
            # e.g. someModule/build/... should not exist; still skip nested build/
            if any(p == "build" for p in build.relative_to(repo).parts[:-1]):
                continue
        mod = module_path_from_build_file(repo, build)
        if mod == ":":
            continue  # root project — no product module edges enforced here
        modules.add(mod)
        for cfg, dep in parse_dependencies(build):
            graph[mod].append((cfg, dep))
    return graph, modules


def api_closure(graph: Dict[str, List[Tuple[str, str]]], start: str) -> Dict[str, List[str]]:
    """
    BFS along api() edges only. Returns map dep -> path (list of modules).
    Includes only transitive api dependencies, not the start module itself.
    """
    paths: Dict[str, List[str]] = {}
    q: deque[Tuple[str, List[str]]] = deque([(start, [start])])
    seen: Set[str] = {start}
    while q:
        node, path = q.popleft()
        for cfg, dep in graph.get(node, []):
            if cfg != "api":
                continue
            if dep in seen:
                continue
            seen.add(dep)
            new_path = path + [dep]
            paths[dep] = new_path
            q.append((dep, new_path))
    return paths


def direct_deps(graph: Dict[str, List[Tuple[str, str]]], start: str) -> Set[str]:
    return {dep for _, dep in graph.get(start, [])}


def fmt_path(path: List[str]) -> str:
    return " → ".join(path)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument(
        "--repo-root",
        type=Path,
        default=Path(__file__).resolve().parents[2],
        help="Monorepo root (default: two levels up from this script)",
    )
    args = ap.parse_args()
    repo: Path = args.repo_root.resolve()
    if not (repo / "settings.gradle.kts").is_file():
        print(f"ERROR: not a monorepo root: {repo}", file=sys.stderr)
        return 1

    graph, modules = load_graph(repo)
    hard: List[str] = []
    soft: List[str] = []

    # ------------------------------------------------------------------
    # 1) app-ui direct forbidden edges
    # ------------------------------------------------------------------
    app_ui = ":android:app-ui"
    if app_ui not in modules:
        hard.append(f"MISSING module {app_ui} (expected in settings)")
    else:
        for dep in sorted(direct_deps(graph, app_ui)):
            if dep.startswith(":data:"):
                hard.append(f"DIRECT {app_ui} → {dep} (forbidden: data writers / stores)")
            if dep.startswith(":engines:"):
                hard.append(f"DIRECT {app_ui} → {dep} (forbidden: engines on UI)")

    # ------------------------------------------------------------------
    # 2) companion isolation
    # ------------------------------------------------------------------
    companion = ":android:companion-sandbox"
    if companion in modules:
        for dep in sorted(direct_deps(graph, companion)):
            if dep.startswith(":data:") or dep.startswith(":runtime:"):
                hard.append(
                    f"DIRECT {companion} → {dep} "
                    f"(forbidden: privileged control-plane / data on companion UID)"
                )
            if dep.startswith(":engines:"):
                hard.append(
                    f"DIRECT {companion} → {dep} "
                    f"(companion must not compile against engine packs as library merge)"
                )
            if dep in {":interfaces:http", ":interfaces:admin", ":interfaces:aidl"}:
                # AIDL stubs for sandbox ticket may be needed later; admin/http are forbidden.
                if dep != ":interfaces:aidl":
                    hard.append(
                        f"DIRECT {companion} → {dep} "
                        f"(forbidden: privileged interface surface on companion)"
                    )

    # ------------------------------------------------------------------
    # 3) engines must not depend on data writers / model store
    # ------------------------------------------------------------------
    for mod in sorted(m for m in modules if m.startswith(":engines:")):
        for cfg, dep in graph.get(mod, []):
            if dep in DATA_MODULES:
                hard.append(
                    f"{cfg.upper()} {mod} → {dep} "
                    f"(forbidden: engine → data; single writer / INV-001)"
                )

    # ------------------------------------------------------------------
    # 4–5) app-ui api-propagated paths
    # ------------------------------------------------------------------
    if app_ui in modules:
        closure = api_closure(graph, app_ui)
        # Also walk implementation edges from app-ui one hop, then api from there
        # (app-ui typically uses implementation(project(...)) for features).
        for cfg, dep in graph.get(app_ui, []):
            if cfg not in {"api", "implementation"}:
                continue
            sub = api_closure(graph, dep)
            # include the direct feature itself
            combined = {dep: [app_ui, dep]}
            for k, p in sub.items():
                combined[k] = [app_ui] + p
            for target, path in combined.items():
                if target == ":data:persistence":
                    hard.append(
                        "API-PATH "
                        + fmt_path(path)
                        + " (forbidden: persistence on UI compile classpath / INV-001)"
                    )
                if target in NATIVE_ENGINE_PACKS:
                    hard.append(
                        "API-PATH "
                        + fmt_path(path)
                        + " (forbidden: native engine pack on UI classpath / INV-001)"
                    )
                if target == ":engines:api":
                    soft.append(
                        "API-PATH "
                        + fmt_path(path)
                        + " (debt: engines SPI types reach UI; prefer Admin projections only)"
                    )
                if target == ":data:model-store":
                    soft.append(
                        "API-PATH "
                        + fmt_path(path)
                        + " (debt: model-store types reach UI; quarantine keys should stay control-plane)"
                    )

    # ------------------------------------------------------------------
    # Report
    # ------------------------------------------------------------------
    print(f"check_module_dependency_rules: modules={len(modules)} root={repo}")
    if soft:
        print(f"\nWARN ({len(soft)} soft findings — not failing CI):")
        for s in soft:
            print(f"  - {s}")
    if hard:
        print(f"\nFAIL ({len(hard)} hard violations):", file=sys.stderr)
        for h in hard:
            print(f"  - {h}", file=sys.stderr)
        print(
            "\nSee AGENTS.md § Dependency rules, INV-001, ADR-010, ADR-007.",
            file=sys.stderr,
        )
        return 1

    print("OK: hard dependency boundary rules passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
