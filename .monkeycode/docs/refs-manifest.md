# 源树锁定清单

> 四个 fork 为独立根仓库，git 历史与工作区无共同提交，仅做文件级移植。源树存放于 `/workspace/_refs/`（已 gitignore，仓库内只保留本清单）。若源树丢失，按本清单重新浅克隆。

| 代号 | 仓库 | 分支 | 锁定 commit | 锁定日期 | 验证关键路径 |
|------|------|------|-------------|----------|--------------|
| m | https://github.com/syq17496152/legado | master | `46dc93167ae67d8d912a0fb52352833cd922f15e` | 2026-10-15 | `app/src/main/java/io/legado/app/help/video/`（VideoPlaybackPipeline、MacCmsNormalizer 等）、`utils/FlowExtensions.kt` |
| arc | https://github.com/Rimchars/legado | main | `e111bf666199505d783e6910d8609f0e5cb043e0` | 2026-10-15 | `app/src/main/java/io/legado/app/model/AutoTask*.kt`（11 文件）、`ui/autoTask/` |
| max | https://github.com/Suml-1/Legado_Max | main | `9cef1691b0864ad3229bfc1852a6a5096f8c3f9e` | 2026-10-15 | `app/src/main/java/io/legado/app/model/debug/`（15 文件）、`ui/debug/`、`ui/debuglog/` |
| ng | https://github.com/joestar817/legado_NG | main | `49ca9556c3060ad7737d0f493cada4fc88c5c86e` | 2026-10-15 | `app/src/main/java/io/legado/app/help/ai/`（39 文件）、`web/mcp/`、`app/src/main/assets/ai/` |

## 克隆命令

```bash
# m 源
git clone --depth 1 https://github.com/syq17496152/legado _refs/m
# arc 源
git clone --depth 1 https://github.com/Rimchars/legado _refs/arc
# max 源
git clone --depth 1 https://github.com/Suml-1/Legado_Max _refs/max
# ng 源
git clone --depth 1 https://github.com/joestar817/legado_NG _refs/ng
```

浅克隆 HEAD 与上表 commit 一致即为有效锁定；若上游已前进，移植时仍以表内 commit 的文件内容为准。

## 工作区基线

- 基线 tag：`baseline-v90`（指向 main `92f93ee`，DB v90 未动的起点）
- 工具链：JDK 17、Android SDK（platforms;android-36、build-tools;36.0.0）、local.properties 指向 `/opt/android-sdk`
- 基线构建日志：`/tmp/opencode/assemble-debug-baseline.log`（摘要见 `.monkeycode/docs/d1-baseline-report.md`）
