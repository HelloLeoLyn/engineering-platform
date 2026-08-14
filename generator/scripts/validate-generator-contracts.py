#!/usr/bin/env python3
# validate-generator-contracts.py — GenerationPlan + ChangeManifest Contract Validator V1
# 依据: V0.7 §20（Generation Plan V1）/ §21（Executor/Rollback）/ §22（ChangeManifest Artifact）
# 校验: generation-plan.schema.yaml + change-manifest.schema.yaml 的 valid/invalid fixtures
import json
import sys
import glob
import os

import yaml
import jsonschema

HERE = os.path.dirname(os.path.abspath(__file__))
SCHEMA_DIR = os.path.join(HERE, "..", "schemas")
EXAMPLES_DIR = os.path.join(SCHEMA_DIR, "examples", "generator")


def load_schema(name):
    with open(os.path.join(SCHEMA_DIR, name), encoding="utf-8") as f:
        return yaml.safe_load(f)


def load_yaml(path):
    with open(path, encoding="utf-8") as f:
        return yaml.safe_load(f)


def validate_fixtures(schema, valid_dir, invalid_dir, label, name_prefix):
    results = []
    validator = jsonschema.Draft202012Validator(schema)

    valid_files = sorted(glob.glob(os.path.join(valid_dir, name_prefix + "*.yaml")))
    if not valid_files:
        results.append((f"{label}: no valid fixtures", False))
    for vf in valid_files:
        data = load_yaml(vf)
        errors = list(validator.iter_errors(data))
        results.append((os.path.basename(vf), len(errors) == 0, [e.message for e in errors]))

    invalid_files = sorted(glob.glob(os.path.join(invalid_dir, name_prefix + "*.yaml")))
    if not invalid_files:
        results.append((f"{label}: no invalid fixtures", False))
    for inv in invalid_files:
        data = load_yaml(inv)
        errors = list(validator.iter_errors(data))
        # invalid fixture MUST be rejected
        results.append((os.path.basename(inv), len(errors) > 0,
                        "expected rejection" if errors else "NOT REJECTED"))

    return results


def main():
    plan_schema = load_schema("generation-plan.schema.yaml")
    cm_schema = load_schema("change-manifest.schema.yaml")

    # quick self-check: schemas must declare schemaVersion const 1 (inside properties, matching other contracts)
    assert plan_schema.get("properties", {}).get("schemaVersion", {}).get("const") == 1, \
        "generation-plan schemaVersion const != 1"
    assert cm_schema.get("properties", {}).get("schemaVersion", {}).get("const") == 1, \
        "change-manifest schemaVersion const != 1"

    all_results = []
    all_results += validate_fixtures(plan_schema,
                                     os.path.join(EXAMPLES_DIR, "valid"),
                                     os.path.join(EXAMPLES_DIR, "invalid"),
                                     "generation-plan", "generation-plan-")
    all_results += validate_fixtures(cm_schema,
                                     os.path.join(EXAMPLES_DIR, "valid"),
                                     os.path.join(EXAMPLES_DIR, "invalid"),
                                     "change-manifest", "change-manifest-")

    failures = [r for r in all_results if not r[1]]
    total = len(all_results)
    print(f"Generator Contract fixtures: {total - len(failures)}/{total} expected")

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
