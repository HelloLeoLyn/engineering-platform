#!/usr/bin/env python3
# validate-engineering-work-contracts.py — Engineering Work Model Contract Validator V1
# 依据: V0.7 §14（AI Engineering Contract）/ §22（Artifact Schema）/ §23（WorkItem State Machine）
# 校验: work-item / engineering-plan / implementation-tasks / test-plan / test-run / verification-report
# 要求: 合法 PASS / 非法 rejected / fixture 缺失整体 FAIL。
import sys
import glob
import os

import yaml
import jsonschema

HERE = os.path.dirname(os.path.abspath(__file__))
SCHEMA_DIR = os.path.join(HERE, "..", "schemas")
EXAMPLES_DIR = os.path.join(SCHEMA_DIR, "examples", "work")

# schema 文件名 → fixture 文件名前缀
CONTRACTS = [
    ("work-item.schema.yaml", "work-item-"),
    ("engineering-plan.schema.yaml", "engineering-plan-"),
    ("implementation-tasks.schema.yaml", "implementation-tasks-"),
    ("test-plan.schema.yaml", "test-plan-"),
    ("test-run.schema.yaml", "test-run-"),
    ("verification-report.schema.yaml", "verification-report-"),
]


def load_yaml(path):
    with open(path, encoding="utf-8") as f:
        return yaml.safe_load(f)


def main():
    all_results = []
    for schema_name, prefix in CONTRACTS:
        with open(os.path.join(SCHEMA_DIR, schema_name), encoding="utf-8") as f:
            schema = yaml.safe_load(f)
        assert schema.get("properties", {}).get("schemaVersion", {}).get("const") == 1, \
            f"{schema_name} schemaVersion const != 1"
        validator = jsonschema.Draft202012Validator(schema)

        valid_dir = os.path.join(EXAMPLES_DIR, "valid")
        invalid_dir = os.path.join(EXAMPLES_DIR, "invalid")

        valid_files = sorted(glob.glob(os.path.join(valid_dir, prefix + "*.yaml")))
        if not valid_files:
            all_results.append((f"{prefix}: no valid fixtures", False))
        for vf in valid_files:
            data = load_yaml(vf)
            errors = list(validator.iter_errors(data))
            all_results.append((os.path.basename(vf), len(errors) == 0, [e.message for e in errors]))

        invalid_files = sorted(glob.glob(os.path.join(invalid_dir, prefix + "*.yaml")))
        if not invalid_files:
            all_results.append((f"{prefix}: no invalid fixtures", False))
        for inv in invalid_files:
            data = load_yaml(inv)
            errors = list(validator.iter_errors(data))
            all_results.append((os.path.basename(inv), len(errors) > 0,
                                "expected rejection" if errors else "NOT REJECTED"))

    failures = [r for r in all_results if not r[1]]
    total = len(all_results)
    print(f"Engineering Work Contract fixtures: {total - len(failures)}/{total} expected")

    for name, ok, detail in all_results:
        mark = "✔" if ok else "✘"
        print(f"  {mark} {name}")
        if not ok and detail:
            for d in detail:
                print(f"      {d}")

    if failures:
        print("\n结果: 存在未符合预期的 fixture ❌")
        return 1
    print("\n结果: 全部符合预期 ✔")
    return 0


if __name__ == "__main__":
    sys.exit(main())
