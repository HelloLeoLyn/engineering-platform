#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
EP-WORK-003 Registry V1 校验脚本
职责（严格限定，不做跨 Registry Reference Validation）:
  1. YAML 可解析
  2. Schema Validation (registry.schema.yaml)
  3. schemaVersion (const=1)
  4. registry.type (8 类枚举)
  5. registry.version (const=1)
  6. entries 结构 (array)
  7. Entry required fields (id)
  8. Entry ID pattern (按 type 差异化)
  9. additionalProperties (false, 顶层 + entry)
  10. 同一 Registry 内 duplicate id

明确不做（属于 EP-WORK-004 Resolver）:
  - provider -> capability existence
  - module -> capability existence
  - guide -> module existence
  - task -> capability existence

用法:
  python3 validate-registry.py --all
  python3 validate-registry.py --file registry/modules.yaml
退出码: 0 = 全部符合预期, 1 = 有失败
"""
import argparse
import sys
from pathlib import Path

import jsonschema
import yaml

ROOT = Path(__file__).resolve().parent.parent.parent  # 仓库根
SCHEMAS = ROOT / "generator" / "schemas"
REGISTRY = ROOT / "registry"

REGISTRY_SCHEMA = SCHEMAS / "registry.schema.yaml"

# 8 类必选 registry 文件
REQUIRED_REGISTRY_FILES = [
    "capabilities.yaml",
    "providers.yaml",
    "modules.yaml",
    "events.yaml",
    "tasks.yaml",
    "permissions.yaml",
    "errors.yaml",
    "guides.yaml",
]

# 合法样例 / 非法样例目录（仅 Registry validation 测试用）
EXAMPLES_DIR = SCHEMAS / "examples" / "registry"
VALID_EXAMPLES = EXAMPLES_DIR / "valid"
INVALID_EXAMPLES = EXAMPLES_DIR / "invalid"


def load_yaml(path: Path):
    with open(path, encoding="utf-8") as f:
        return yaml.safe_load(f)


def collect_duplicate_ids(registry_data):
    """同一 Registry 内 duplicate id 检查。返回重复 id 集合。"""
    entries = registry_data.get("entries")
    if not isinstance(entries, list):
        return set()
    seen = {}
    for entry in entries:
        if isinstance(entry, dict) and "id" in entry:
            eid = entry["id"]
            seen[eid] = seen.get(eid, 0) + 1
    return {eid for eid, count in seen.items() if count > 1}


def validate_one(registry_path: Path, expect: str) -> bool:
    """expect: pass | fail"""
    schema = load_yaml(REGISTRY_SCHEMA)
    registry_data = load_yaml(registry_path)  # YAML parse error -> 异常 -> FAIL
    errors = list(jsonschema.Draft202012Validator(schema).iter_errors(registry_data))

    # duplicate id 检查（脚本级）
    dups = collect_duplicate_ids(registry_data)
    if dups:
        errors.append(_DupError(f"duplicate entry id(s): {sorted(dups)}"))

    ok = len(errors) == 0
    if expect == "pass":
        passed = ok
        label = "PASS" if ok else "FAIL"
    else:
        passed = not ok
        label = "PASS(rejected)" if not ok else "FAIL(accepted!)"

    rel = registry_path.relative_to(ROOT)
    print(f"[{label}] {rel}  <-  {REGISTRY_SCHEMA.name} (expect {expect})")
    if not ok and expect == "pass":
        for e in errors[:6]:
            where = "/".join(str(p) for p in getattr(e, "path", [])) or "(root)"
            print(f"    - {where}: {e.message}")
    if ok and expect == "fail":
        print("    - 警告: 该非法文件未被拒绝")
    return passed


class _DupError:
    """最小错误对象：模拟 jsonschema ValidationError 的 path/message"""

    def __init__(self, message):
        self.message = message
        self.path = []


def run_all() -> bool:
    """校验 8 个真实 registry + 合法/非法样例目录"""
    results = []

    # 1) 8 个真实 registry 文件必须存在且 PASS
    for name in REQUIRED_REGISTRY_FILES:
        f = REGISTRY / name
        if not f.exists():
            print(f"[FAIL] 缺失 registry 文件: {f.relative_to(ROOT)} (expect 存在)")
            results.append(False)
        else:
            results.append(validate_one(f, "pass"))

    # 2) 合法样例 -> PASS
    if VALID_EXAMPLES.exists():
        for f in sorted(VALID_EXAMPLES.glob("*.yaml")):
            results.append(validate_one(f, "pass"))

    # 3) 非法样例 -> FAIL
    if INVALID_EXAMPLES.exists():
        for f in sorted(INVALID_EXAMPLES.glob("*.yaml")):
            results.append(validate_one(f, "fail"))

    return all(results)


def run_single(registry_path: Path) -> bool:
    return validate_one(registry_path, "pass")


def main():
    ap = argparse.ArgumentParser(description="Engineering Platform Registry Validator (V1)")
    ap.add_argument("--all", action="store_true", help="校验 8 个真实 registry + 示例目录")
    ap.add_argument("--file", type=Path, help="校验单个 registry 文件")
    args = ap.parse_args()

    if args.all:
        ok = run_all()
    elif args.file:
        ok = run_single(args.file)
    else:
        ap.print_help()
        sys.exit(2)
    print("\n结果:", "全部符合预期 ✔" if ok else "存在失败 ✘")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
