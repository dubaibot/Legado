#!/usr/bin/env bash
# 合规 + 架构门禁总入口。CI 与本地提交前均执行。
set -euo pipefail
cd "$(dirname "$0")/.."

fail=0
for script in tools/check_permissions.py tools/check_disclaimer.py \
              tools/check_no_telemetry.py tools/check_contracts.py \
              tools/check_wrapper.py; do
    echo "== $script"
    python3 "$script" || fail=1
done

if [ "$fail" -ne 0 ]; then
    echo "合规门禁未通过，禁止合并"
    exit 1
fi
echo "全部门禁通过"
