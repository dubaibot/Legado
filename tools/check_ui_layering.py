#!/usr/bin/env python3
"""UI 分层门禁：界面层不得直连数据库（T10）。

T10 的硬约束是"UI 不许直接碰 DAO，必须经过 Repository"。

为什么这条要写成门禁而不是写在文档里：直接查 DAO 在短期内**能跑通**，
而且更省事——少写一层 Repository 就少一层转发。代价要到后面才显现：

- 界面在 `LaunchedEffect` 里查库，协程随重组被取消，容易留下半截状态；
- 同一份数据被多个页面各查一次，刷新时机各不相同，界面之间会互相矛盾；
- 想加缓存、想加去重、想换数据源，发现调用点散落在各个 Composable 里。

这些都不是编译错误，也不会被现有测试发现。所以用文本扫描把边界钉住：
`com/lanyue/reader/ui/` 下任何文件引用了 DAO 类型或 RoomDatabase，即失败。

允许引用实体类（`data/db/entity`）：那是纯数据模型，界面拿它渲染是正常的；
需要禁的是"能直接读写数据库"的那一层。
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
UI_DIR = ROOT / "app/src/main/java/com/lanyue/reader/ui"

# 判定"直连数据库"的三类引用：
#   1. 任何 *Dao / *Daos 类型（CatalogDaos、SourceDaos 等）
#   2. RoomDatabase 本体及其子类 LanyueDatabase
#   3. Room 的运行时入口（Room.databaseBuilder / Room.inMemoryDatabaseBuilder）
FORBIDDEN_PATTERNS = [
    (re.compile(r"\b\w*Dao\b"), "DAO 类型"),
    (re.compile(r"\b\w*Daos\b"), "DAO 容器"),
    (re.compile(r"\bRoomDatabase\b"), "RoomDatabase"),
    (re.compile(r"\bLanyueDatabase\b"), "LanyueDatabase"),
    (re.compile(r"\bRoom\s*\.\s*(databaseBuilder|inMemoryDatabaseBuilder)"), "Room 构建器"),
]

# 实体与注解不在此列：界面渲染数据模型是合理用法
ALLOWED_IMPORT_PREFIXES = (
    "com.lanyue.reader.data.db.entity",
)

errors = []


def is_allowed(line: str) -> bool:
    return any(p in line for p in ALLOWED_IMPORT_PREFIXES)


def main():
    if not UI_DIR.is_dir():
        print("  (ui/ 目录不存在，跳过)")
        return 0

    files = sorted(UI_DIR.rglob("*.kt"))
    if not files:
        print("  (ui/ 下没有 Kotlin 文件，跳过)")
        return 0

    for path in files:
        rel = path.relative_to(ROOT)
        for lineno, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            line = raw.strip()
            # 只查 import 与类型使用；注释里提到 DAO 不算违规
            if line.startswith("//") or line.startswith("*") or line.startswith("/*"):
                continue
            if is_allowed(line):
                continue
            for pattern, kind in FORBIDDEN_PATTERNS:
                if pattern.search(line):
                    errors.append(f"{rel}:{lineno} 直接引用{kind}：{line}")
                    break

    if errors:
        for e in errors:
            print(f"  ✗ {e}")
        print()
        print("  界面层必须通过 Repository 取数据，不要直接持有 DAO。")
        return 1

    print(f"  ui/ 下 {len(files)} 个文件未直连数据库")
    return 0


if __name__ == "__main__":
    sys.exit(main())
