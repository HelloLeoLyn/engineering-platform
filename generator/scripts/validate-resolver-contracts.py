#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
EP-WORK-004A Resolver Contracts 校验脚本

职责（严格限定）:
  - 仅 Schema Validation（三个 Contract: effective-project / resolution-report / resolver-error）
  - 显式要求所有规定的 valid/invalid fixture 存在，缺失 => 整体 FAIL
  - 合法 fixture 必须 PASS；非法 fixture 必须被拒绝
  - Secret-related field-name lint（辅助检查）：检查 schema 定义与合法 fixture 中
    是否出现明显的 Secret Value 字段名（secret/password/token/apiKey 等）。
    定位说明: 这只是字段名级的 lint 辅助，不是完整 Secret Safety 保证，
    不能发现所有 Secret Value；主要安全边界是 Schema 本身 +
    后续 Semantic/Security Validation。不引入 entropy/regex 检测或运行时扫描。

明确不做:
  - Resolver Pipeline / Reference Resolution / Semantic Resolution
  - 任何 Java/Python Resolver 实现
  - 复杂 Secret Scanner（entropy / regex / 运行时）

用法:
  python3 validate-resolver-contracts.py
退出码: 0 = 全部符合预期, 1 = 有失败
"""
import sys
from pathlib import Path

import jsonschema
import yaml

ROOT = Path(__file__).resolve().parent.parent.parent  # 仓库根
SCHEMAS = ROOT / "generator" / "schemas"
EXAMPLES = SCHEMAS / "examples" / "resolver"
VALID = EXAMPLES / "valid"
INVALID = EXAMPLES / "invalid"

# 三个 Contract Schema
CONTRACTS = {
    "effective-project": SCHEMAS / "effective-project.schema.yaml",
    "resolution-report": SCHEMAS / "resolution-report.schema.yaml",
    "resolver-error": SCHEMAS / "resolver-error.schema.yaml",
}

# 显式 fixture 清单：schema 名 -> (合法文件列表, 非法文件列表)
FIXTURES = {
    "effective-project": (
        ["effective-project-minimal.yaml", "effective-project-full.yaml"],
        [
            "effective-project-missing-resolution.yaml",
            "effective-project-invalid-activation.yaml",
            "effective-project-invalid-activation-source.yaml",
        ],
    ),
    "resolution-report": (
        ["resolution-report-minimal.yaml", "resolution-report-full.yaml"],
        ["resolution-report-missing-resolution-id.yaml"],
    ),
    "resolver-error": (
        ["resolver-error.yaml"],
        ["resolver-error-invalid-code.yaml", "resolver-error-missing-message.yaml"],
    ),
}

# 明显 Secret Value 字段名（作为 property 名禁止；引用型如 secretProvider/configReference 允许）
FORBIDDEN_SECRET_FIELDS = {"secret", "password", "token", "apikey", "api_key", "accesskey", "access_key"}



def load_yaml(path: Path):
    with open(path, encoding="utf-8") as f:
        return yaml.safe_load(f)


def collect_keys(obj, prefix=""):
    """递归收集所有 dict key（小写化）"""
    keys = []
    if isinstance(obj, dict):
        for k, v in obj.items():
            keys.append(str(k).lower())
            keys.extend(collect_keys(v, f"{prefix}{k}."))
    elif isinstance(obj, list):
        for item in obj:
            keys.extend(collect_keys(item, prefix))
    return keys


def check_secret_field_lint(schema_path: Path, valid_files: list) -> bool:
    """Secret-related field-name lint（辅助检查，非完整 Secret Safety 保证）。

    仅检查 schema 定义与合法 fixture 中是否出现明显的 Secret Value 字段名。
    不能证明 EPM 不可能包含 Secret Value；主要安全边界是 Schema 本身。
    """
    ok = True
    schema = load_yaml(schema_path)
    schema_keys = set(collect_keys(schema.get("properties", {})))
    hit = schema_keys & FORBIDDEN_SECRET_FIELDS
    if hit:
        print(f"[FAIL] {schema_path.name} 定义了明显 Secret Value 字段名: {sorted(hit)}")
        ok = False
    for fname in valid_files:
        f = VALID / fname
        if not f.exists():
            continue
        data = load_yaml(f)
        data_keys = set(collect_keys(data))
        hit = data_keys & FORBIDDEN_SECRET_FIELDS
        if hit:
            print(f"[FAIL] {fname} 包含明显 Secret Value 字段名: {sorted(hit)}")
            ok = False
    if ok:
        print("[OK]   Secret-related field-name lint: 未发现明显 Secret Value 字段名（辅助检查，非完整 Secret Safety 保证）")
    return ok


def validate_one(schema_path: Path, fixture_path: Path, expect: str) -> bool:
    """expect: pass | fail"""
    schema = load_yaml(schema_path)
    data = load_yaml(fixture_path)
    errors = list(jsonschema.Draft202012Validator(schema).iter_errors(data))
    ok = len(errors) == 0
    if expect == "pass":
        passed = ok
        label = "PASS" if ok else "FAIL"
    else:
        passed = not ok
        label = "PASS(rejected)" if not ok else "FAIL(accepted!)"
    print(f"[{label}] {fixture_path.relative_to(ROOT)}  <-  {schema_path.name} (expect {expect})")
    if not ok and expect == "pass":
        for e in errors[:5]:
            where = "/".join(str(p) for p in e.path) or "(root)"
            print(f"    - {where}: {e.message}")
    if ok and expect == "fail":
        print("    - 警告: 该非法文件未被拒绝")
    return passed


def run_all() -> bool:
    results = []

    # 0) 三个 Contract Schema 必须存在
    for name, path in CONTRACTS.items():
        if not path.exists():
            print(f"[FAIL] 缺失 Contract Schema: {path.relative_to(ROOT)}")
            results.append(False)

    # 1) 显式 fixture 存在性检查（缺失 => FAIL，不允许跳过）
    for schema_name, (valid_files, invalid_files) in FIXTURES.items():
        for fname in valid_files:
            f = VALID / fname
            if not f.exists():
                print(f"[FAIL] 缺失合法 fixture: {f.relative_to(ROOT)} (expect 存在)")
                results.append(False)
        for fname in invalid_files:
            f = INVALID / fname
            if not f.exists():
                print(f"[FAIL] 缺失非法 fixture: {f.relative_to(ROOT)} (expect 存在)")
                results.append(False)

    # 2) Secret-related field-name lint 检查（effective-project）
    results.append(check_secret_field_lint(CONTRACTS["effective-project"], FIXTURES["effective-project"][0]))

    # 3) 合法 fixture -> PASS
    for schema_name, (valid_files, _) in FIXTURES.items():
        schema_path = CONTRACTS[schema_name]
        for fname in valid_files:
            f = VALID / fname
            if f.exists():
                results.append(validate_one(schema_path, f, "pass"))

    # 4) 非法 fixture -> FAIL
    for schema_name, (_, invalid_files) in FIXTURES.items():
        schema_path = CONTRACTS[schema_name]
        for fname in invalid_files:
            f = INVALID / fname
            if f.exists():
                results.append(validate_one(schema_path, f, "fail"))

    return all(results)


def main():
    ok = run_all()
    print("\n结果:", "全部符合预期 ✔" if ok else "存在失败 ✘")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
