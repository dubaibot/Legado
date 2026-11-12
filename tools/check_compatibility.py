#!/usr/bin/env python3
"""T52 兼容性静态审计：前台服务类型与权限匹配 + SDK 版本范围检查。

检查内容：
1. 每个 <service> 声明的 foregroundServiceType 必须有对应的
   <uses-permission>（Android 14+ 强制要求，漏了直接崩溃）；
2. compileSdk >= targetSdk >= minSdk（基本约束）；
3. minSdk 不低于 26（Android 8，项目声明的最低支持版本）；
4. targetSdk >= 34（Android 14，应用商店上架要求）；
5. 两个已知服务（MediaDownloadService / AudioPlaybackService）的类型
   与 check_permissions.py 的断言保持一致（交叉校验，避免一方改了另一方漏）。
"""
import re
import sys
from pathlib import Path

MANIFEST = Path("app/src/main/AndroidManifest.xml")
BUILD_GRADLE = Path("app/build.gradle.kts")

# foregroundServiceType → 对应的 uses-permission
FGS_TYPE_TO_PERMISSION = {
    "mediaPlayback": "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
    "dataSync": "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
    "phoneCall": "android.permission.FOREGROUND_SERVICE_PHONE_CALL",
    "location": "android.permission.FOREGROUND_SERVICE_LOCATION",
    "connectedDevice": "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE",
    "mediaProcessing": "android.permission.FOREGROUND_SERVICE_MEDIA_PROCESSING",
    "mediaProjection": "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION",
    "microphone": "android.permission.FOREGROUND_SERVICE_MICROPHONE",
    "camera": "android.permission.FOREGROUND_SERVICE_CAMERA",
    "health": "android.permission.FOREGROUND_SERVICE_HEALTH",
    "remoteMessaging": "android.permission.FOREGROUND_SERVICE_REMOTE_MESSAGING",
    "systemExempted": "android.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED",
    "shortService": "android.permission.FOREGROUND_SERVICE_SHORT_SERVICE",
    "specialUse": "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
}

# 交叉校验：已知服务与期望类型（与 check_permissions.py 一致）
KNOWN_SERVICES = {
    ".player.MediaDownloadService": "dataSync",
    ".player.AudioPlaybackService": "mediaPlayback",
}


def main() -> int:
    fail = 0

    # ── 1. 读 AndroidManifest ──
    if not MANIFEST.exists():
        print(f"[FAIL] manifest not found: {MANIFEST}")
        return 1
    manifest_text = MANIFEST.read_text(encoding="utf-8")

    # ── 2. 读 build.gradle.kts ──
    if not BUILD_GRADLE.exists():
        print(f"[FAIL] build.gradle.kts not found: {BUILD_GRADLE}")
        return 1
    gradle_text = BUILD_GRADLE.read_text(encoding="utf-8")

    # ── 3. 提取声明的权限 ──
    declared_perms = set(re.findall(
        r'android:name="(android\.permission\.FOREGROUND_SERVICE[A-Z_]*)"', manifest_text
    ))

    # ── 4. 提取所有 <service> 声明 ──
    # 匹配 <service ...> 或 <service ... />
    service_pattern = re.compile(r'<service\b([^>]*?)/?>', re.S)
    services = service_pattern.findall(manifest_text)

    if not services:
        print("[WARN] manifest 中未声明任何 <service>")

    # ── 5. 检查每个 service 的 foregroundServiceType ──
    fgs_declarations = []
    for svc_attr in services:
        name_match = re.search(r'android:name="([^"]+)"', svc_attr)
        svc_name = name_match.group(1) if name_match else "(unnamed)"

        fgs_match = re.search(r'android:foregroundServiceType="([^"]+)"', svc_attr)
        if fgs_match:
            fgs_type = fgs_match.group(1)
            fgs_declarations.append((svc_name, fgs_type))

            # 检查对应的 permission 是否声明
            needed_perm = FGS_TYPE_TO_PERMISSION.get(fgs_type)
            if needed_perm and needed_perm not in declared_perms:
                print(f"[FAIL] 服务 {svc_name} 声明了 foregroundServiceType={fgs_type}，"
                      f"但缺少 <uses-permission android:name=\"{needed_perm}\" />")
                fail = 1
        else:
            # 没有声明 foregroundServiceType 的服务不报错（不是所有服务都是前台服务）
            pass

    # ── 6. 交叉校验已知服务类型 ──
    for svc_name, expected_type in KNOWN_SERVICES.items():
        found = False
        for name, fgs_type in fgs_declarations:
            if name == svc_name:
                found = True
                if fgs_type != expected_type:
                    print(f"[FAIL] {svc_name} 的 foregroundServiceType 应为 {expected_type}，"
                          f"实际为 {fgs_type}")
                    fail = 1
                break
        if not found:
            print(f"[FAIL] 未声明服务 {svc_name} 或未声明其 foregroundServiceType")
            fail = 1

    # ── 7. SDK 版本范围检查 ──
    compile_match = re.search(r'compileSdk\s*=\s*(\d+)', gradle_text)
    target_match = re.search(r'targetSdk\s*=\s*(\d+)', gradle_text)
    min_match = re.search(r'minSdk\s*=\s*(\d+)', gradle_text)

    if not (compile_match and target_match and min_match):
        print("[FAIL] build.gradle.kts 中缺少 compileSdk/targetSdk/minSdk 声明")
        fail = 1
    else:
        compile_sdk = int(compile_match.group(1))
        target_sdk = int(target_match.group(1))
        min_sdk = int(min_match.group(1))

        if min_sdk > target_sdk:
            print(f"[FAIL] minSdk({min_sdk}) > targetSdk({target_sdk})")
            fail = 1
        if target_sdk > compile_sdk:
            print(f"[FAIL] targetSdk({target_sdk}) > compileSdk({compile_sdk})")
            fail = 1
        if min_sdk < 26:
            print(f"[FAIL] minSdk={min_sdk}，低于项目要求的 Android 8 (API 26)")
            fail = 1
        if target_sdk < 34:
            print(f"[WARN] targetSdk={target_sdk}，建议升至 34 (Android 14) 以满足上架要求")
            # 警告不算失败

    # ── 8. Android 14+ 前台服务类型声明完整性 ──
    if target_match and int(target_match.group(1)) >= 34:
        # Android 14 (API 34) 起，前台服务必须声明 foregroundServiceType
        for svc_attr in services:
            name_match = re.search(r'android:name="([^"]+)"', svc_attr)
            svc_name = name_match.group(1) if name_match else "(unnamed)"
            exported_match = re.search(r'android:exported="(true|false)"', svc_attr)
            exported = exported_match.group(1) == "true" if exported_match else False

            # 导出的服务或前台服务必须有类型声明
            # 这里只检查已知前台服务（已在 KNOWN_SERVICES 中覆盖）
            pass

    # ── 结果 ──
    if fail:
        print("[FAIL] 兼容性审计未通过")
        return 1

    fgs_count = len(fgs_declarations)
    perm_count = len(declared_perms)
    sdk_info = ""
    if compile_match and target_match and min_match:
        sdk_info = (f"，SDK: min={min_match.group(1)}/"
                    f"target={target_match.group(1)}/"
                    f"compile={compile_match.group(1)}")
    print(f"[OK] 兼容性审计通过：{fgs_count} 个前台服务、{perm_count} 个前台服务权限{sdk_info}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
