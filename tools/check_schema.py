#!/usr/bin/env python3
"""Room schema 导出的配置门禁（T05）。

为什么需要这道门禁
------------------
Room 的 schema 导出是"三处同时为真"才成立的组合：

  1. `@Database(exportSchema = true)`
  2. `ksp { arg("room.schemaLocation", ...) }`
  3. `app/schemas/<version>.json` 真的生成并被提交

缺任何一处都**不会**导致构建失败，只会在构建日志里留一条 warning。
等到某天有人给实体加字段才发现没有基线：既写不出迁移测试，也只能退回
`fallbackToDestructiveMigration()`——用户升级即丢数据。

这种"静默失效"的配置错误靠人眼 review 不可靠，所以固化成门禁。
检查对象是文本而非 Gradle 模型：门禁不需要 Android SDK，离线即可跑。

schema JSON 本身由 Gradle + KSP 在构建期产出。本门禁不检查其内容
（解析 schema 属于 Room 的职责），只报告它是否存在——不存在时给出
明确的"请在有 Android SDK 的机器上跑一次 assembleDebug"提示，
而不是伪造一份 JSON 让门禁变绿。
"""

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DB = ROOT / "app/src/main/java/com/lanyue/reader/data/db/LanyueDatabase.kt"
BUILD = ROOT / "app/build.gradle.kts"
SCHEMAS = ROOT / "app/schemas"

errors = []
notes = []


def read(path):
    if not path.is_file():
        errors.append(f"缺少文件：{path.relative_to(ROOT)}")
        return ""
    return path.read_text(encoding="utf-8")


def main():
    db_src = read(DB)
    build_src = read(BUILD)
    if not db_src or not build_src:
        return report()

    # ---- 1. @Database(exportSchema = true) ----
    # 缺省是 false，必须显式写 true；写成 false 也算未开启。
    if re.search(r"exportSchema\s*=\s*true", db_src):
        notes.append("@Database exportSchema = true")
    else:
        errors.append(
            "@Database 未声明 exportSchema = true（Room 缺省为 false，schema 不会导出）"
        )

    # ---- 2. ksp { arg("room.schemaLocation", ...) } ----
    m = re.search(r'arg\s*\(\s*"room\.schemaLocation"\s*,\s*"([^"]*)"', build_src)
    if not m:
        errors.append(
            'app/build.gradle.kts 缺少 ksp { arg("room.schemaLocation", ...) }'
        )
        schema_location = None
    else:
        schema_location = m.group(1)
        notes.append(f'room.schemaLocation = "{schema_location}"')
        # 绝对路径会把本机目录写死，换一台机器即失效
        if "$projectDir" not in schema_location and "${projectDir}" not in schema_location:
            errors.append(
                f'room.schemaLocation 未使用 $projectDir 相对路径："{schema_location}"'
            )
        # 目录名必须是 schemas：迁移测试与社区惯例都按这个路径找
        tail = schema_location.rstrip("/\\").replace("\\", "/").split("/")[-1]
        if tail != "schemas":
            errors.append(f'room.schemaLocation 目录名应为 schemas，实际 "{tail}"')

    # ---- 3. version 与 entities ----
    vm = re.search(r"version\s*=\s*(\d+)", db_src)
    version = int(vm.group(1)) if vm else None
    if version is None:
        errors.append("@Database 缺少合法 version")
    else:
        notes.append(f"@Database version = {version}")

    em = re.search(r"entities\s*=\s*\[(.*?)]", db_src, re.S)
    entity_count = len(re.findall(r"::class", em.group(1))) if em else 0
    if entity_count == 0:
        errors.append("@Database 的 entities 列表为空或无法解析")
    else:
        notes.append(f"实体 {entity_count} 个")

    # ---- 4. schema JSON 产物 ----
    # 这一项只报告不判错：产物需要 Gradle + KSP 才能生成，
    # 在没有 Android SDK 的环境里判错只会逼人伪造文件。
    jsons = sorted(p.name for p in SCHEMAS.glob("*.json")) if SCHEMAS.is_dir() else []
    if not jsons:
        notes.append(
            "app/schemas/ 尚无 schema JSON —— 需在有 Android SDK 的环境执行一次"
            " `./gradlew :app:kspDebugKotlin`（或 assembleDebug）生成并提交"
        )
    else:
        notes.append(f"app/schemas/ 已有：{', '.join(jsons)}")
        if version is not None and f"{version}.json" not in jsons:
            errors.append(
                f"缺少与 version={version} 对应的 {version}.json（迁移测试会找错基线）"
            )
        # 顺带做一次最廉价的骨架校验：能解析、version 与注解一致
        for name in jsons:
            try:
                data = json.loads((SCHEMAS / name).read_text(encoding="utf-8"))
            except Exception as exc:
                errors.append(f"{name} 不是合法 JSON：{exc}")
                continue
            inner = data.get("database", {})
            if inner.get("version") != version:
                errors.append(
                    f"{name} 的 database.version={inner.get('version')}"
                    f" 与 @Database version={version} 不一致"
                )

    return report()


def report():
    for n in notes:
        print(f"  · {n}")
    if errors:
        print()
        for e in errors:
            print(f"  ✗ {e}")
        return 1
    print("  Room schema 配置正确")
    return 0


if __name__ == "__main__":
    sys.exit(main())
