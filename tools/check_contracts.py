#!/usr/bin/env python3
"""架构门禁（轻量静态检查）。

1. EngineException 不得继承 CancellationException（4.3 强制约束）
2. 源实现（source/）不得直接引用 UI 层
3. LocalParseProxy 只允许绑定 127.0.0.1
4. 关键 TODO 必须带编号（TODO-DEV-*），便于评审追踪
"""
import re
import sys
from pathlib import Path

MAIN = Path("app/src/main/java")


def read_all(path: Path):
    return [(f, f.read_text(encoding="utf-8")) for f in path.rglob("*.kt")]


def main() -> int:
    problems = []

    engine = MAIN / "com/lanyue/reader/core/engine/Engine.kt"
    if engine.exists():
        text = engine.read_text(encoding="utf-8")
        m = re.search(r"class EngineException\([^)]*\)\s*:\s*([A-Za-z0-9_.]+)", text, re.S)
        if m and "CancellationException" in m.group(1):
            problems.append("Engine.kt: EngineException 不得继承 CancellationException")
    else:
        problems.append("缺少 Engine.kt")

    for f, text in read_all(MAIN / "com/lanyue/reader/source"):
        if re.search(r"import com\.lanyue\.reader\.ui", text):
            problems.append(f"{f}: 源层不得依赖 UI 层")

    proxy = MAIN / "com/lanyue/reader/source/video/LocalParseProxy.kt"
    if proxy.exists():
        text = proxy.read_text(encoding="utf-8")
        if "0.0.0.0" in text:
            problems.append("LocalParseProxy.kt: 禁止绑定 0.0.0.0")

    for f, text in read_all(MAIN):
        for m in re.finditer(r"TODO(?!-DEV-)[^ \n]*", text):
            problems.append(f"{f}: TODO 未带编号 -> {m.group(0)}")

    if problems:
        print("[FAIL] 架构门禁：")
        for p in problems:
            print("  -", p)
        return 1

    print("[OK] 架构契约门禁通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
