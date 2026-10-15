# 资源分区命名约定（清单 1.4）

> 排期 D3 产出。目的：四个源 fork（m / arc / max / ng）与工作区基线的资源互不覆盖、来源一眼可辨、融合日可控回滚。

## 1. strings（按源分文件）

| 来源 | 文件 |
|------|------|
| 工作区基线 | `values/strings.xml`（保持不动） |
| m | `values/strings_m.xml` |
| arc | `values/strings_arc.xml` |
| max | `values/strings_max.xml` |
| ng | `values/strings_ng.xml` |

- locale 变体同步建文件：`values-zh/strings_m.xml`、`values-zh-rTW/strings_m.xml` 等；仅当源提供该语言翻译时落地，缺失语言回退默认（lint `MissingTranslation` 已关闭）。
- 搬运前先 `grep` 目标 key 是否与基线同名：同名不同义必须改名（加源前缀，如 `m_settings_video`），同名同义直接用源文件。

## 2. 其他资源类型（文件名/键名加源前缀）

| 类型 | 约定 | 示例 |
|------|------|------|
| drawable / mipmap | 文件名 `{src}_` 前缀 | `m_ic_playback_speed.xml`、`max_bg_cover_frame.png` |
| layout | 文件名 `{src}_` 前缀 | `arc_activity_auto_task.xml` |
| id | `@+id/{src}_xxx` | `@+id/m_danmaku_view` |
| colors / dimens / arrays / attrs / styles | 独立分区文件 `colors_m.xml`、`dimens_max.xml` 等；键名与基线冲突时加 `{src}_` 前缀 | `colors_m.xml` 内 `<color name="m_video_bg">` |
| menu / anim / raw / xml | 文件名 `{src}_` 前缀 | `ng_menu_ai_chat.xml` |

- `values-night` 夜色变体跟随同名日间文件建 `{src}` 分区（如 `values-night/colors_m.xml`）。
- Android 资源按 name 全局合并，分文件只为来源可辨；**禁止覆盖基线同名键**。

## 3. 冲突处理流程

1. 纯新增任务：按本约定命名，落地前 grep 同名冲突。
2. 同名且语义一致 → 直接采用源文件内容。
3. 同名不同义 → 融合方案日裁决改名，结论记入当日报告。
4. 融合类任务（覆盖工作区同名文件）按当日融合方案执行，本约定只约束纯新增资源。

## 4. 源代号

| 代号 | 源 | 前缀 |
|------|----|------|
| m | syq17496152/legado | `m_` / `strings_m.xml` |
| arc | Rimchars/legado | `arc_` / `strings_arc.xml` |
| max | Suml-1/Legado_Max | `max_` / `strings_max.xml` |
| ng | joestar817/legado_NG | `ng_` / `strings_ng.xml` |
