#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
V02-WORK-001 — Engineering Asset Contract V1 校验脚本

用法:
  python3 validate-assets.py --schema generator/schemas/engineering-asset.schema.yaml <asset.yaml>
  python3 validate-assets.py --all            # 校验全部 valid/invalid asset fixtures
退出码: 0 = 全部符合预期, 1 = 有失败

额外规则（超出 JSON Schema）:
  R1 path safety: 所有路径字段必须为相对路径；禁止绝对路径与 ".." 段
  R2 secret default: configuration key 匹配 password/token/secret/apikey 时禁止 default 值
     （secret/config reference 类型不得携带默认值落入契约）
"""
import argparse
import posixpath
import re
import sys
from pathlib import Path

import jsonschema
import yaml

ROOT = Path(__file__).resolve().parent.parent.parent  # 仓库根
SCHEMA = ROOT / "generator" / "schemas" / "engineering-asset.schema.yaml"
EXAMPLES = ROOT / "generator" / "schemas" / "examples" / "assets"

VALID_FILES = [
    "valid/asset-module-valid.yaml",
    "valid/asset-capability-valid.yaml",
    "valid/asset-provider-valid.yaml",
    "valid/asset-template-valid.yaml",
]

INVALID_FILES = [
    "invalid/asset-missing-id.yaml",
    "invalid/asset-invalid-type.yaml",
    "invalid/asset-invalid-version.yaml",
    "invalid/asset-path-traversal.yaml",
    "invalid/asset-unknown-property.yaml",
    "invalid/asset-invalid-dependency.yaml",
    "invalid/asset-secret-value.yaml",
]

# V02-WORK-002: 真实资产目录 → 对应 Registry 文件（一致性检查用）
ASSET_DIRS = [
    ("capabilities", ROOT / "registry" / "capabilities.yaml", "CAPABILITY"),
    ("providers", ROOT / "registry" / "providers.yaml", "PROVIDER"),
]

SECRET_KEY = re.compile(r"(password|token|secret|apikey)", re.IGNORECASE)


def load_yaml(path: Path):
    with open(path, encoding="utf-8") as f:
        return yaml.safe_load(f)


def path_fields(asset: dict):
    """Yield all contract path strings (R1)."""
    for f in asset.get("files", []) or []:
        if isinstance(f, dict):
            yield f.get("source")
            yield f.get("target")
    conf = asset.get("conformance", {}) or {}
    for key in ("requiredFiles",):
        for p in conf.get(key, []) or []:
            yield p
    tests = asset.get("tests", {}) or {}
    for key in ("files", "fixtures"):
        for p in tests.get(key, []) or []:
            yield p
    docs = asset.get("documentation", {}) or {}
    yield docs.get("guide")
    for p in docs.get("references", []) or []:
        yield p


def rule_path_safety(asset: dict):
    """R1: no absolute paths, no '..' segments."""
    for p in path_fields(asset):
        if p is None:
            continue
        if p.startswith("/") or re.match(r"^[A-Za-z]:[\\/]", p):
            return f"absolute path not allowed: {p}"
        norm = posixpath.normpath(p)
        if norm.startswith("..") or "/../" in "/" + norm:
            return f"path traversal not allowed: {p}"
    return None


def rule_secret_default(asset: dict):
    """R2: secret-like configuration keys must not carry a default value."""
    for c in asset.get("configuration", []) or []:
        if not isinstance(c, dict):
            continue
        key = c.get("key", "")
        if SECRET_KEY.search(key) and "default" in c:
            return f"secret-like configuration key must not declare a default: {key}"
        if c.get("type") in ("secretRef", "configRef") and "default" in c:
            return f"reference-typed configuration key must not declare a default: {key}"
    return None


def validate_one(asset_path: Path, expect: str):
    """expect: pass | fail"""
    schema = load_yaml(SCHEMA)
    asset = load_yaml(asset_path)
    validator = jsonschema.Draft202012Validator(schema)
    errors = sorted(validator.iter_errors(asset), key=lambda e: list(e.path))
    rule_errors = []
    if not errors:
        rule_errors = [r for r in (rule_path_safety(asset), rule_secret_default(asset)) if r]

    ok = len(errors) == 0 and len(rule_errors) == 0
    if expect == "pass":
        passed = ok
        label = "PASS" if ok else "FAIL"
    else:
        passed = not ok
        label = "PASS(rejected)" if not ok else "FAIL(accepted!)"
    rel = asset_path.relative_to(ROOT)
    print(f"[{label}] {rel} (expect {expect})")
    if not ok and expect == "pass":
        for e in errors[:5]:
            where = "/".join(str(p) for p in e.path) or "(root)"
            print(f"    - schema: {where}: {e.message}")
        for r in rule_errors:
            print(f"    - rule: {r}")
    if ok and expect == "fail":
        print("    - 警告: 该非法文件未被拒绝")
    return passed


def check_registry_consistency() -> bool:
    """V02-WORK-002: Registry 登记的 asset 必须能在资产目录找到，且 id/type 匹配。"""
    ok = True
    for dirname, registry_path, expect_type in ASSET_DIRS:
        registry = load_yaml(registry_path)
        entries = registry.get("entries", []) or []
        entry_ids = {e.get("id") for e in entries if isinstance(e, dict)}
        asset_root = ROOT / dirname
        # 1) 每个 registry entry 必须有对应资产目录与 asset.yaml
        for eid in sorted(entry_ids):
            asset_file = asset_root / eid / "asset.yaml"
            if not asset_file.exists():
                print(f"[FAIL] registry entry {eid} has no asset at {dirname}/{eid}/asset.yaml")
                ok = False
        # 2) 每个资产目录（非隐藏/非文件）必须有 asset.yaml，且 id/type 与 registry 匹配
        if asset_root.exists():
            for sub in sorted(p for p in asset_root.iterdir() if p.is_dir()):
                asset_file = sub / "asset.yaml"
                if not asset_file.exists():
                    print(f"[FAIL] asset dir {dirname}/{sub.name} missing asset.yaml")
                    ok = False
                    continue
                asset = load_yaml(asset_file)
                aid = asset.get("id")
                atype = asset.get("type")
                if aid != sub.name:
                    print(f"[FAIL] {dirname}/{sub.name}/asset.yaml id '{aid}' != dir name")
                    ok = False
                if atype != expect_type:
                    print(f"[FAIL] {dirname}/{sub.name}/asset.yaml type '{atype}' != {expect_type}")
                    ok = False
                if aid not in entry_ids:
                    print(f"[FAIL] asset {dirname}/{sub.name} not registered in {registry_path.name}")
                    ok = False
    if ok:
        print("[PASS] registry <-> asset consistency")
    return ok


def run_all() -> bool:
    ok = True
    for f in VALID_FILES:
        p = EXAMPLES / f
        if not p.exists():
            print(f"[FAIL] 缺少合法 fixture: {f}")
            ok = False
            continue
        ok = validate_one(p, "pass") and ok
    for f in INVALID_FILES:
        p = EXAMPLES / f
        if not p.exists():
            print(f"[FAIL] 缺少非法 fixture: {f}")
            ok = False
            continue
        ok = validate_one(p, "fail") and ok
    ok = check_registry_consistency() and ok
    return ok


def main() -> int:
    global SCHEMA
    parser = argparse.ArgumentParser(description="Engineering Asset Contract V1 validator")
    parser.add_argument("--schema", default=str(SCHEMA), help="path to engineering-asset.schema.yaml")
    parser.add_argument("--all", action="store_true", help="validate all valid/invalid fixtures")
    parser.add_argument("file", nargs="?", help="single asset file to validate (expects pass)")
    args = parser.parse_args()

    SCHEMA = Path(args.schema)

    if args.all:
        ok = run_all()
    elif args.file:
        ok = validate_one(Path(args.file), "pass")
    else:
        print("用法: validate-assets.py --all | --schema <schema> <asset.yaml>")
        return 2
    print("== ASSETS VALIDATION OK ==" if ok else "== ASSETS VALIDATION FAILED ==")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
