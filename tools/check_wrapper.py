#!/usr/bin/env python3
"""Gradle Wrapper 配置门禁。

检查四件事，都是"提交前必须为真、事后很难补救"的：

1. `gradlew` / `gradlew.bat` / `gradle/wrapper/gradle-wrapper.jar` /
   `gradle-wrapper.properties` 四件齐全——缺一件，别的机器克隆后根本起不来；
2. `gradlew` 具备可执行位——跨平台提交时常丢掉它，
   表现为 Linux/CI 上 `./gradlew: Permission denied`；
3. `distributionUrl` 锁定为精确版本（无 rc/latest/snapshot），且与根
   `build.gradle.kts` 里的 AGP 大版本对齐；
4. 声明 `distributionSha256Sum` 且格式合法——wrapper 首次运行会下载并执行
   分发包，没有校验和等于允许中间人替换构建工具。

不做联网校验：门禁要能在离线 CI 与本地任意时刻跑。校验和是否与真实分发包
一致属于一次性核对（本轮已用 sha256sum -c 实测通过），不放进每次提交的门禁。
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PROPS = os.path.join(ROOT, "gradle", "wrapper", "gradle-wrapper.properties")
JAR = os.path.join(ROOT, "gradle", "wrapper", "gradle-wrapper.jar")
GRADLEW = os.path.join(ROOT, "gradlew")
GRADLEW_BAT = os.path.join(ROOT, "gradlew.bat")
ROOT_BUILD = os.path.join(ROOT, "build.gradle.kts")


def parse_props(path):
    out = {}
    with open(path, encoding="utf-8") as fh:
        for raw in fh:
            line = raw.strip()
            if not line or line.startswith("#") or line.startswith("!"):
                continue
            if "=" not in line:
                continue
            k, v = line.split("=", 1)
            out[k.strip()] = v.strip().replace("\\:", ":")
    return out


def main():
    problems = []

    for path, label in ((GRADLEW, "gradlew"), (GRADLEW_BAT, "gradlew.bat"),
                        (JAR, "gradle/wrapper/gradle-wrapper.jar"),
                        (PROPS, "gradle/wrapper/gradle-wrapper.properties")):
        if not os.path.isfile(path):
            problems.append(f"缺少 {label}")
        elif os.path.getsize(path) == 0:
            problems.append(f"{label} 是空文件")

    if os.path.isfile(GRADLEW) and not os.access(GRADLEW, os.X_OK):
        problems.append("gradlew 缺少可执行位（Linux/CI 上会 Permission denied）")

    url = None
    if os.path.isfile(PROPS):
        props = parse_props(PROPS)
        url = props.get("distributionUrl", "")
        if not url:
            problems.append("properties 缺少 distributionUrl")
        elif not url.startswith("https://"):
            problems.append(f"distributionUrl 不是 https：{url}")
        else:
            name = url.rsplit("/", 1)[-1].removesuffix(".zip")
            m = re.match(r"^gradle-(\d+)\.(\d+)(\.\d+)?-(bin|all)$", name)
            if not m:
                problems.append(f"distributionUrl 版本未锁定为精确版本：{name}")
            else:
                major, minor = int(m.group(1)), int(m.group(2))
                expected_major = None
                if os.path.isfile(ROOT_BUILD):
                    with open(ROOT_BUILD, encoding="utf-8") as fh:
                        text = fh.read()
                    agp = re.search(
                        r'id\("com\.android\.application"\)\s+version\s+"([\d.]+)"', text)
                    if agp:
                        expected_major = int(agp.group(1).split(".")[0])
                if expected_major is not None and major != expected_major:
                    problems.append(f"Gradle 大版本 {major} 与 AGP {expected_major} 不匹配")
                if major == 8 and minor < 7:
                    problems.append(f"Gradle 次版本 {minor} 低于 AGP 8.x 要求的 7")

        sha = props.get("distributionSha256Sum")
        if not sha:
            problems.append("properties 缺少 distributionSha256Sum")
        elif not re.fullmatch(r"[0-9a-fA-F]{64}", sha):
            problems.append(f"distributionSha256Sum 不是合法 64 位十六进制：{sha}")

        if props.get("validateDistributionUrl", "").lower() != "true":
            problems.append("validateDistributionUrl 应为 true")

    if os.path.isfile(JAR) and os.path.getsize(JAR) > 0:
        with open(JAR, "rb") as fh:
            head = fh.read(2)
        if head != b"PK":
            problems.append("gradle-wrapper.jar 不是合法 zip 包")

    if problems:
        print("[FAIL] Gradle Wrapper 检查未通过：")
        for p in problems:
            print(f"  - {p}")
        return 1

    print(f"[OK] Gradle Wrapper 检查通过（{url.rsplit('/', 1)[-1]}）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
