#!/usr/bin/env python3
"""合规门禁：本地埋点不得有网络出口。

第八章要求：埋点只落本地文件（LocalMetrics -> 本地环形日志），
禁止上报、禁止初始化第三方统计 SDK、禁止向远端发请求。
"""
import re
import sys
from pathlib import Path

SRC = Path("app/src/main/java")
METRICS_DIR = SRC / "com/lanyue/reader/core/observability"

NET_HINTS = re.compile(
    r"HttpURLConnection|OkHttpClient|openConnection\(|Retrofit|"
    r"firebase|umeng|appsflyer|sensorsdata|bugly",
    re.I,
)

THIRD_PARTY_SDK = re.compile(
    r"com\.google\.firebase|com\.umeng|com\.bugly|com\.appsflyer|"
    r"com\.sensorsdata|cn\.jpush|com\.tencent\.bugly",
)


def main() -> int:
    problems = []

    if METRICS_DIR.exists():
        for f in METRICS_DIR.rglob("*.kt"):
            text = f.read_text(encoding="utf-8")
            for m in NET_HINTS.finditer(text):
                problems.append(f"{f}: 埋点模块出现网络/SDK 特征 {m.group(0)}")

    build = Path("app/build.gradle.kts")
    if build.exists():
        for line in build.read_text(encoding="utf-8").splitlines():
            s = line.strip()
            if s.startswith("//") or not s.startswith("implementation("):
                continue
            if THIRD_PARTY_SDK.search(s):
                problems.append(f"build.gradle.kts: 引入第三方统计 SDK -> {s}")

    if problems:
        print("[FAIL] 存在数据上报风险：")
        for p in problems:
            print("  -", p)
        return 1

    print("[OK] 无遥测出口门禁通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
