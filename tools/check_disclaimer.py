#!/usr/bin/env python3
"""合规门禁：免责声明与版权声明必须存在于 strings.xml。

要求（见产品合规章节）：
1. 免责声明明确"内容来自第三方源，App 不存储、不分发"
2. 明确"用户自行承担使用风险"与"举报/下架通道"
"""
import sys
from pathlib import Path
from xml.etree import ElementTree as ET

TARGET = Path("app/src/main/res/values/strings.xml")

REQUIRED_KEYS = {
    "disclaimer_title",
    "disclaimer_body",
    "disclaimer_copyright",
    "disclaimer_report",
}

REQUIRED_PHRASES = ["第三方", "不存储"]


def main() -> int:
    if not TARGET.exists():
        print(f"[FAIL] strings.xml 不存在: {TARGET}")
        return 1

    root = ET.parse(TARGET).getroot()
    values = {el.get("name"): (el.text or "") for el in root if el.tag == "string"}

    missing = REQUIRED_KEYS - set(values)
    if missing:
        print("[FAIL] 缺少免责声明条目：", sorted(missing))
        return 1

    body = values["disclaimer_body"] + values["disclaimer_copyright"]
    for phrase in REQUIRED_PHRASES:
        if phrase not in body:
            print(f"[FAIL] 免责声明缺少关键表述：{phrase}")
            return 1

    print("[OK] 免责声明门禁通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
