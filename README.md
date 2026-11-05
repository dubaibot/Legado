# 蓝阅 Android · 源码

阅览客户端源码工程：解析内核 / 数据层 / 源接入 / 播放器 / 弹幕。

## 在线浏览源码

- **源码浏览页**（文件树 + 全文搜索 + 语法高亮 + 一键复制 + 内嵌 ZIP 下载）
  https://cdn.jsdelivr.net/gh/dubaibot/Legado@lanyue-source/source-browser.html

## 结构

| 目录 | 内容 |
| --- | --- |
| `app/src/main/java/com/lanyue/reader/core` | 解析内核、网络分派、限速、埋点、权限分级 |
| `app/src/main/java/com/lanyue/reader/data` | Room 实体 / DAO / 数据库（17 表） |
| `app/src/main/java/com/lanyue/reader/source` | 书源 / 订阅源 / 影视源 / 弹幕源 + SourceRouter 调度 |
| `app/src/main/java/com/lanyue/reader/player` | 播放器管理、音频前台服务、下载续传服务 |
| `app/src/main/java/com/lanyue/reader/danmaku` | 弹幕参数、过滤、格式转换、Canvas 渲染 |
| `tools/` | 合规与架构门禁（权限白名单 / 免责声明 / 无遥测 / 契约） |

## 门禁

```bash
bash tools/ci.sh
```

## 说明

- 本分支为源码镜像分支，不改动 `main`。
- 编译验证需本地 Android Studio 执行 `assembleDebug`（本环境无 Android SDK）。
- 免责声明：本工程不提供、不存储、不分发任何内容；内容来自用户自行导入的第三方源。
