#!/usr/bin/env bash
# 用法：scripts/run.sh check | ask "问题" | patrol | repl
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="$ROOT/target/taiyiflow-ops-copilot.jar"

if [[ ! -f "$JAR" ]]; then
  echo "未找到 $JAR，请先执行: cd '$ROOT' && ./mvnw clean package" >&2
  exit 1
fi

if [[ -f "$ROOT/config/ops.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$ROOT/config/ops.env"
  set +a
fi

# Windows JDK 的 stdout/stderr 默认是 GBK，强制 UTF-8 否则中文日志会乱码
exec java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
  -jar "$JAR" "$@"
