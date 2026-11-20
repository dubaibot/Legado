<div align="center">
<img width="125" height="125" src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="reading-d"/>
<br>
阅读 D
<br>
阅读 D 是一个基于 <a href="https://github.com/gedoor/legado" target="_blank">Legado（阅读 3.0）</a> 的开源 Android 应用，在原版小说阅读能力之上，融合了影视源、短剧与弹幕播放。
</div>

## 软件定位

- 阅读与观影一体：书架、订阅、影视、短剧都在同一个应用里完成
- 源驱动：书源、订阅源、影视源全部由用户自行导入与管理，软件本身不提供内容
- 数据本地化：书架、配置与阅读进度保存在本机，支持导出、备份与恢复
- 开源无广告：应用内不含广告位与推广内容

## 主要功能

### 影视与短剧

1. 多类型影视源：兼容 FongMi / TVbox 生态，支持 JSON（苹果 CMS V10）、TXT 列表、HTML 规则、Python 插件与 jar 爬虫（`csp_` 站点）五种接入方式。
2. 短剧竖屏播放：沉浸式竖屏播放页，上下滑切换、双击点赞、收藏直接加入书架、选集网格、倍速、自动连播与进度记忆。
3. 弹幕播放：支持 B 站 XML / JSON 弹幕解析与 Canvas 渲染，滚动、顶部、底部三种模式，可接入 LogVar / dandanplay 兼容弹幕服务器。
4. 完善播放器：片头片尾跳过、定时关闭、画面镜像与比例、清晰度切换、截图、悬浮窗与系统媒体通知。
5. 首页影视流：分类标签栏加视频网格，多源切换、分类浏览与后台预热缓存。
6. 统一搜索：书源、订阅源与影视源在同一入口搜索，结果统一展示。

### 阅读（继承自 Legado）

1. 自定义书源，规则简单易懂，软件内附规则说明。
2. 列表书架与网格书架自由切换，支持分组与本地书籍。
3. 书源支持搜索与发现，找书看书功能全部可自定义。
4. 订阅内容，可订阅想看的任意内容。
5. 替换净化，去除广告、替换正文内容。
6. 本地 TXT、EPUB 阅读，支持手动浏览与智能扫描。
7. 高度自定义阅读界面：字体、颜色、背景、行距、段距、加粗、简繁转换等。
8. 多种翻页模式：覆盖、仿真、滑动、滚动。
9. 在线朗读（TTS）与音频播放，支持带歌词的音频。
10. Web 服务与 WebDAV 备份，可在浏览器中编辑源、阅读与同步数据。

## 下载与安装

- GitHub Releases：<https://github.com/dubaibot/Legado/releases>
- CI 构建产物：Actions 中的 `Build ReadingD APK` 工作流，下载 `readingD-apk` Artifacts
- 安装包按 ABI 拆分，提供 universal、arm64-v8a 与 armeabi-v7a 版本，可按设备选择以减小体积

## 快速开始

1. 安装后打开应用，进入「我的 - 源管理」导入书源、订阅源或影视源。
2. 书源可在「发现」中浏览，影视源在「影视」标签浏览与播放。
3. 软件不内置任何内容，源地址请自行准备。

相关文档：

- [书源规则教程](https://mgz0227.github.io/The-tutorial-of-Legado/)
- [帮助文档](/app/src/main/assets/web/help/md/appHelp.md)
- [更新日志](/app/src/main/assets/updateLog.md)

## API

* 阅读 3.0 提供两种 API 调用方式：`Web 方式` 与 `Content Provider 方式`，详见 [api.md](api.md)。
* 可通过 URL 唤起应用进行一键导入，格式为 `legado://import/{path}?src={url}`。
* `path` 类型：`bookSource`、`rssSource`、`replaceRule`、`textTocRule`、`httpTTS`、`theme`、`readConfig`、`dictRule`、`addToBookshelf`。
* 类型含义：书源、订阅源、替换规则、本地 TXT 目录规则、在线朗读引擎、主题、阅读排版、添加到书架。

## 交流与反馈

- 问题反馈与功能建议：<https://github.com/dubaibot/Legado/issues>
- 源码与提交记录：<https://github.com/dubaibot/Legado>

## 免责声明

阅读 D 依赖系统 WebView 提供网页访问能力，通过用户自定义的第三方源返回内容，应用对第三方源返回的内容不负责，亦不承担任何法律责任。任何第三方源均由他人制作或提供，应用作者对其合法性不负责。第三方源提供的试读内容不代表应用赞同其内容或立场，请自行判断并承担使用风险。

任何单位或个人认为第三方源内容可能涉嫌侵犯其信息网络传播权，应及时向应用提出书面通知，并提供身份证明、权属证明及详细侵权情况证明。应用在收到上述法律文件后，将依法尽快处理相关内容。

## 开源协议与致谢

本项目基于 [Legado](https://github.com/gedoor/legado) 开发，遵循 GPL-3.0 协议，详见 [LICENSE](LICENSE)。

感谢 Legado 作者 gedoor 及 Legado 开源社区的所有贡献者。

主要开源依赖：

> * org.jsoup:jsoup
> * cn.wanghaomiao:JsoupXpath
> * com.jayway.jsonpath:json-path
> * com.github.gedoor:rhino-android
> * com.squareup.okhttp3:okhttp
> * com.github.bumptech.glide:glide
> * org.nanohttpd:nanohttpd
> * org.nanohttpd:nanohttpd-websocket
> * cn.bingoogolapple:bga-qrcode-zxing
> * com.jaredrummler:colorpicker
> * org.apache.commons:commons-text
> * io.noties.markwon:core
> * io.noties.markwon:image-glide
> * com.hankcs:hanlp
> * com.positiondev.epublib:epublib-core
> * com.github.Moriafly:LyricViewX
> * io.github.rosemoe:editor

## 更新日志

完整更新日志见 [app/src/main/assets/updateLog.md](/app/src/main/assets/updateLog.md)。

【最近更新】
- 竖屏沉浸播放页交互重做：爱心加强推荐、收藏进书架、选集切换、倍速与自动连播
- 影视源支持 jar 爬虫（FongMi Spider，`csp_` 站点）、JSON、TXT、HTML 与 Python 插件
- 弹幕支持 LogVar / dandanplay 兼容服务器
- 修复 Rhino 源 XML 后代访问语法兼容问题