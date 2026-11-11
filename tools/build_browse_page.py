#!/usr/bin/env python3
"""重新生成 index.html / source-browser.html 的源码浏览页。

原页面的形态是固定的，重建时必须逐项对齐，否则「重新生成」会变成「换了个页面」：
- `srcData`：JSON 数组，元素为 {path, lang, lines, content}，按 path 字典序；
  分隔符是 json 默认的 `", "` / `": "`，中文不转义（页面本身是 zh-CN）。
- `zipData`：同一批文件的 zip，base64 单行、deflate、条目前缀 `lanyue-android/`，
  统一时间戳与 0o600 权限位，保证同一次生成内条目元数据一致。
- 两个 html 内容完全相同（source-browser.html 是同一份的别名）。
"""
import base64
import io
import json
import os
import re
import sys
import time
import zipfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PREFIX = "lanyue-android/"

# 这三个不进浏览页：CI 元数据与页面自身，收进去会让页面记录自己的旧版本。
EXCLUDE = {".nojekyll", "index.html", "source-browser.html"}


def lang_of(path: str) -> str:
    name = os.path.basename(path)
    if name.startswith(".") and "." not in name[1:]:
        return "text"
    ext = name.rsplit(".", 1)[-1].lower() if "." in name else ""
    return {
        "kt": "kt", "kts": "kts", "md": "md", "py": "py", "sh": "sh",
        "xml": "xml", "pro": "pro", "properties": "properties",
    }.get(ext, "text")


def collect():
    files = []
    for root, dirs, names in os.walk(REPO):
        dirs[:] = [d for d in dirs if d != ".git"]
        for n in names:
            full = os.path.join(root, n)
            rel = os.path.relpath(full, REPO).replace(os.sep, "/")
            if rel in EXCLUDE:
                continue
            files.append(rel)
    return sorted(files)


def read_text(path):
    """按字节读入再严格 UTF-8 解码；解不开返回 None，说明是二进制。

    不能直接用 `open(..., encoding="utf-8").read()`：仓库里现在有
    `gradle-wrapper.jar`，那样会在生成阶段直接抛 UnicodeDecodeError，
    整个页面重建失败。二进制文件对源码浏览器没有展示价值（base64 进 JSON
    还会膨胀约 4/3），但仍应留在 zip 里，方便下载完整仓库快照。
    """
    with open(path, "rb") as fh:
        raw = fh.read()
    try:
        return raw.decode("utf-8")
    except UnicodeDecodeError:
        return None


def build():
    paths = collect()
    entries = []
    binaries = []  # (rel, bytes) 只进 zip
    for rel in paths:
        full = os.path.join(REPO, rel)
        text = read_text(full)
        if text is None:
            with open(full, "rb") as fh:
                binaries.append((rel, fh.read()))
            continue
        # 行数与页面展示口径一致：末尾换行不额外算一行
        lines = text.count("\n") + (0 if text.endswith("\n") else 1)
        entries.append({"path": rel, "lang": lang_of(rel), "lines": lines, "content": text})

    payload = json.dumps(entries, ensure_ascii=False)
    # 源码里可能自带 `</script>`（本轮新增的生成脚本自身就含它的正则字面量）。
    # 原样写进 <script type="application/json"> 会提前闭合标签，后面的数据整段丢失，
    # 表现为「页面能打开但只能看到一部分文件」。`<\/` 是 JSON 的合法转义，
    # json.loads 后仍还原成 `</`，所以转义只影响 HTML 层的安全性，不改变数据。
    payload = payload.replace("</", "<\\/")

    # zip 条目元数据统一，避免同一次生成的包里出现不同时间戳
    now = time.gmtime()
    stamp = (now.tm_year, now.tm_mon, now.tm_mday, now.tm_hour, now.tm_min, now.tm_sec)
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        # 按路径排序写入，保证两次生成的包条目顺序一致（便于比对差异）
        for rel, data in sorted(
            [(e["path"], e["content"].encode("utf-8")) for e in entries] + binaries,
            key=lambda x: x[0],
        ):
            info = zipfile.ZipInfo(PREFIX + rel, date_time=stamp)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.create_system = 3
            info.external_attr = 0o600 << 16
            z.writestr(info, data)
    zip_b64 = base64.b64encode(buf.getvalue()).decode("ascii")

    return entries, payload, zip_b64


def main():
    src = os.path.join(REPO, "index.html")
    with open(src, "r", encoding="utf-8") as fh:
        html = fh.read()

    entries, payload, zip_b64 = build()

    html, n1 = re.subn(
        r'(<script id="srcData" type="application/json">).*?(</script>)',
        lambda m: m.group(1) + payload + m.group(2),
        html, count=1, flags=re.S,
    )
    html, n2 = re.subn(
        r'(<script id="zipData" type="text/plain">).*?(</script>)',
        lambda m: m.group(1) + zip_b64 + m.group(2),
        html, count=1, flags=re.S,
    )
    if n1 != 1 or n2 != 1:
        print(f"替换失败 srcData={n1} zipData={n2}", file=sys.stderr)
        return 1

    for target in ("index.html", "source-browser.html"):
        with open(os.path.join(REPO, target), "w", encoding="utf-8") as fh:
            fh.write(html)

    kt = sum(1 for e in entries if e["lang"] == "kt")
    total_lines = sum(e["lines"] for e in entries)
    print(f"files={len(entries)} kt={kt} lines={total_lines} "
          f"html_bytes={len(html.encode('utf-8'))} zip_bytes={len(base64.b64decode(zip_b64))}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
