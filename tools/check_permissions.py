#!/usr/bin/env python3
"""第六章 6.2.4 权限最小化门禁。

校验 AndroidManifest.xml 的 uses-permission 只能是白名单集合。
白名单是"允许集"，不代表都是非危险级；POST_NOTIFICATIONS 属 dangerous，
必须运行时请求（见 ui 层 requestPermission）。
"""
import re
import sys
from pathlib import Path

ALLOWED = {
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
    "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
    "android.permission.CHANGE_WIFI_MULTICAST_STATE",
    "android.permission.WAKE_LOCK",
}

FORBIDDEN_HINTS = (
    "READ_PHONE_STATE",
    "READ_SMS",
    "READ_EXTERNAL_STORAGE",
    "QUERY_ALL_PACKAGES",
    "MANAGE_EXTERNAL_STORAGE",
    "ACCESS_FINE_LOCATION",
    "READ_CONTACTS",
    "RECORD_AUDIO",
    "CAMERA",
)

MANIFEST = Path("app/src/main/AndroidManifest.xml")


def main() -> int:
    if not MANIFEST.exists():
        print(f"[FAIL] manifest not found: {MANIFEST}")
        return 1

    text = MANIFEST.read_text(encoding="utf-8")
    declared = set(re.findall(r'android:name="(android\.permission\.[A-Z_]+)"', text))

    extra = declared - ALLOWED
    if extra:
        print("[FAIL] 超出权限白名单：")
        for p in sorted(extra):
            print(f"  - {p}")
        return 1

    bad = [p for p in declared if any(h in p for h in FORBIDDEN_HINTS)]
    if bad:
        print("[FAIL] 命中禁止清单：", bad)
        return 1

    # 前台服务类型必须与代码用途一致
    for svc, need in (
        ("MediaDownloadService", "dataSync"),
        ("AudioPlaybackService", "mediaPlayback"),
    ):
        m = re.search(r'android:name="\.player\.' + svc + r'"(.*?)/>', text, re.S)
        if not m:
            print(f"[FAIL] 未声明服务 {svc}")
            return 1
        if need not in m.group(1):
            print(f"[FAIL] {svc} foregroundServiceType 应为 {need}")
            return 1

    print(f"[OK] 权限门禁通过，已声明 {len(declared)} 项，均在白名单内")
    return 0


if __name__ == "__main__":
    sys.exit(main())
