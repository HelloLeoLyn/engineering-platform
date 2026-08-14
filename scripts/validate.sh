#!/usr/bin/env bash
# scripts/validate.sh — Engineering Platform V0.1 Unified Validation Entry
# 用法: ./scripts/validate.sh            # Python contract validators + Java tests（环境允许时）
#       ./scripts/validate.sh --python  # 仅 Python contract validators
#       ./scripts/validate.sh --java    # 仅 Java compile 检查（javac --release 21 辅助）
#
# 说明:
#   - Python validators = contract/build-time validation（全部必须 exit 0）
#   - Java tests 需要 JDK 25（正式基线）；本机 JDK 21 时仅做静态编译检查
#   - 不复制 validator 逻辑，只做 orchestration
set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FAIL=0
MODE="${1:-all}"
# 兼容 --python / --java 前缀
case "$MODE" in
  --python) MODE=python ;;
  --java) MODE=java ;;
esac

run_python() {
  echo "== Python Contract Validators =="
  for v in validate-manifest.py validate-registry.py validate-resolver-contracts.py \
           validate-generator-contracts.py validate-engineering-work-contracts.py \
           validate-agent-execution-contracts.py; do
    ARGS=""
    case "$v" in
      validate-manifest.py|validate-registry.py) ARGS="--all" ;;
    esac
    if python3 "$ROOT/generator/scripts/$v" $ARGS >/dev/null 2>&1; then
      echo "  ✔ $v"
    else
      echo "  ✘ $v"
      FAIL=1
    fi
  done
}

run_java() {
  echo "== Java Static Compile Check (javac --release 21, JDK25 gate pending) =="
  OUT="$(mktemp -d)"
  JUP="$HOME/.m2/repository/org/junit/jupiter/junit-jupiter-api/6.0.1/junit-jupiter-api-6.0.1.jar"
  ASSERTJ="$HOME/.m2/repository/org/assertj/assertj-core/3.27.6/assertj-core-3.27.6.jar"
  if javac --release 21 -d "$OUT" \
      "$ROOT"/generator/generator-contracts/src/main/java/com/engineeringplatform/generator/contracts/*.java \
      "$ROOT"/generator/generator-core/src/main/java/com/engineeringplatform/generator/core/*.java 2>/dev/null; then
    echo "  ✔ main sources compile"
  else
    echo "  ✘ main sources compile"
    FAIL=1
  fi
  if javac --release 21 -d "$OUT" -cp "$OUT:$JUP:$ASSERTJ" \
      "$ROOT"/generator/generator-core/src/test/java/com/engineeringplatform/generator/core/*.java 2>/dev/null; then
    echo "  ✔ test sources compile"
  else
    echo "  ✘ test sources compile"
    FAIL=1
  fi
  rm -rf "$OUT"
  echo "  (正式 Maven/JUnit test 需要 JDK 25 — JDK25_BUILD_GATE = PENDING)"
}

case "$MODE" in
  python) run_python ;;
  java)   run_java ;;
  *)      run_python; run_java ;;
esac

if [ "$FAIL" -ne 0 ]; then
  echo "== VALIDATION FAILED =="
  exit 1
fi
echo "== VALIDATION OK =="
exit 0
