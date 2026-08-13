#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
EP-WORK-001 Manifest 四件套 Schema V1 校验脚本
用法:
  python3 validate-manifest.py --schema generator/schemas/platform.schema.yaml platform.yaml
  python3 validate-manifest.py --all            # 校验全部示例 + 现有 platform.yaml
退出码: 0 = 全部符合预期, 1 = 有失败
"""
import argparse
import json
import sys
from pathlib import Path

import jsonschema
import yaml

ROOT = Path(__file__).resolve().parent.parent.parent  # 仓库根
SCHEMAS = ROOT / "generator" / "schemas"

MANIFEST_SCHEMA_MAP = [
    ("platform", SCHEMAS / "platform.schema.yaml"),
    ("project", SCHEMAS / "project.schema.yaml"),
    ("module", SCHEMAS / "module.schema.yaml"),
    ("provider", SCHEMAS / "provider.schema.yaml"),
]


def load_yaml(path: Path):
    with open(path, encoding="utf-8") as f:
        return yaml.safe_load(f)


def validate_one(schema_path: Path, manifest_path: Path, expect: str):
    """expect: pass | fail"""
    schema = load_yaml(schema_path)
    manifest = load_yaml(manifest_path)
    validator = jsonschema.Draft202012Validator(schema)
    errors = sorted(validator.iter_errors(manifest), key=lambda e: list(e.path))
    ok = len(errors) == 0
    if expect == "pass":
        passed = ok
        label = "PASS" if ok else "FAIL"
    else:
        passed = not ok
        label = "PASS(rejected)" if not ok else "FAIL(accepted!)"
    print(f"[{label}] {manifest_path.relative_to(ROOT)}  <-  {schema_path.name} (expect {expect})")
    if not ok and expect == "pass":
        for e in errors[:5]:
            where = "/".join(str(p) for p in e.path) or "(root)"
            print(f"    - {where}: {e.message}")
    if ok and expect == "fail":
        print("    - 警告: 该非法文件未被拒绝")
    return passed


def run_all() -> bool:
    """校验: 现有 platform.yaml + 4 合法示例应通过; 4 非法示例应被拒。

    硬性要求 8 个示例文件全部存在：
    合法: platform/project/module/provider.example.yaml
    非法: platform/project/module/provider-invalid-1.yaml
    任何一个缺失 => 输出 FAIL 且整体失败 (exit code 1)，不允许跳过。
    """
    results = []
    examples = SCHEMAS / "examples"
    invalid_dir = examples / "invalid"

    # 8 个必选文件清单 (schema 名, 合法文件名, 非法文件名)
    required = [
        ("platform", "platform.example.yaml", "platform-invalid-1.yaml"),
        ("project", "project.example.yaml", "project-invalid-1.yaml"),
        ("module", "module.example.yaml", "module-invalid-1.yaml"),
        ("provider", "provider.example.yaml", "provider-invalid-1.yaml"),
    ]

    # 1) 显式检查 8 个文件全部存在，缺失即 FAIL（不跳过）
    for schema_name, valid_name, invalid_name in required:
        valid_path = examples / valid_name
        invalid_path = invalid_dir / invalid_name
        for path, kind in [(valid_path, "合法"), (invalid_path, "非法")]:
            if not path.exists():
                print(f"[FAIL] 缺失{kind}示例: {path.relative_to(ROOT)} (expect 存在)")
                results.append(False)
            else:
                print(f"[OK]   {kind}示例存在: {path.relative_to(ROOT)}")

    # 2) 现有 platform.yaml 必须通过 platform schema
    results.append(validate_one(SCHEMAS / "platform.schema.yaml", ROOT / "platform.yaml", "pass"))

    # 3) 逐个校验 8 个示例
    for schema_name, valid_name, invalid_name in required:
        schema_path = SCHEMAS / f"{schema_name}.schema.yaml"
        valid_path = examples / valid_name
        invalid_path = invalid_dir / invalid_name
        if valid_path.exists():
            results.append(validate_one(schema_path, valid_path, "pass"))
        if invalid_path.exists():
            results.append(validate_one(schema_path, invalid_path, "fail"))

    return all(results)


def run_single(schema: Path, manifest: Path) -> bool:
    return validate_one(schema, manifest, "pass")


def main():
    ap = argparse.ArgumentParser(description="Engineering Platform Manifest Schema Validator (V1)")
    ap.add_argument("--schema", type=Path, help="schema 文件路径")
    ap.add_argument("--manifest", type=Path, help="待校验 manifest 路径")
    ap.add_argument("--all", action="store_true", help="校验全部示例 + 现有 platform.yaml")
    args = ap.parse_args()

    if args.all:
        ok = run_all()
    elif args.schema and args.manifest:
        ok = run_single(args.schema, args.manifest)
    else:
        ap.print_help()
        sys.exit(2)
    print("\n结果:", "全部符合预期 ✔" if ok else "存在失败 ✘")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
