<div align="center">
<img width="125" height="125" src="https://gitee.com/lyc486/legado/raw/main/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="legado"/>
<br>
阅读Sigma
<br>
阅读Sigma继承自<a href="https://github.com/gedoor/legado" target="_blank">Legado</a>，延续开源阅读项目，在其基础上新增更多功能。
</div>

## 版本说明
- 测试版(beta)：包名与Legado原版相同，可覆盖更新，版本更新频繁
- 正式版(plus)：新的共存包名，安装后是一个新软件，不会覆盖Legado原版，每到一个稳定阶段进行一次更新
#### 找不到下载地址可以去这里 [下载软件](https://gitee.com/lyc486/legado/releases)

[![](https://img.shields.io/badge/-Contents:-696969.svg)](#contents) [![](https://img.shields.io/badge/-Function-F5F5F5.svg)](#Function-主要功能-) [![](https://img.shields.io/badge/-Community-F5F5F5.svg)](#Community-交流社区-) [![](https://img.shields.io/badge/-API-F5F5F5.svg)](#API-) [![](https://img.shields.io/badge/-Other-F5F5F5.svg)](#Other-其他-) [![](https://img.shields.io/badge/-Grateful-F5F5F5.svg)](#Grateful-感谢-) [![](https://img.shields.io/badge/-Interface-F5F5F5.svg)](#Interface-界面-)

>新用户？
>
>软件不提供内容，需要您自己手动添加，例如导入书源等。  
>看看 [帮助文档](https://www.yuque.com/legado/wiki)，也许里面就有你要的答案。

# Function-主要功能 [![](https://img.shields.io/badge/-Function-F5F5F5.svg)](#Function-主要功能-)

<details><summary>原版</summary>
1.自定义书源，自己设置规则，抓取网页数据，规则简单易懂，软件内有规则说明。<br>
2.列表书架，网格书架自由切换。<br>
3.书源规则支持搜索及发现，所有找书看书功能全部自定义，找书更方便。<br>
4.订阅内容,可以订阅想看的任何内容,看你想看<br>
5.支持替换净化，去除广告替换内容很方便。<br>
6.支持本地TXT、EPUB阅读，手动浏览，智能扫描。<br>
7.支持高度自定义阅读界面，切换字体、颜色、背景、行距、段距、加粗、简繁转换等。<br>
8.支持多种翻页模式，覆盖、仿真、滑动、滚动等。<br>
9.软件开源，持续优化，无广告。
</details>

<details><summary>延续</summary>
1.带歌词的音频播放功能。<br>
2.带弹幕的视频播放功能。<br>
3.更方便的书源编辑。<br>
4.更丰富的书源功能实现。<br>
5.更完善的订阅源功能。<br>
</details>

<a href="#readme">
    <img src="https://img.shields.io/badge/-返回顶部-orange.svg" alt="#" align="right">
</a>

# Community-交流社区 [![](https://img.shields.io/badge/-Community-F5F5F5.svg)](#Community-交流社区-)

#### Telegram
[![Telegram-channel](https://img.shields.io/badge/Σ_Telegram-%E9%A2%91%E9%81%93-blue)](https://t.me/readsigma)

#### WeChat
[![WeChat-channel](https://img.shields.io/badge/Σ_%e5%be%ae%e4%bf%a1-%e5%85%ac%e4%bc%97%e5%8f%b7-green)](https://mp.weixin.qq.com/mp/appmsgalbum?action=getalbum&album_id=4345046964963590147)  
<img src="https://open.weixin.qq.com/qr/code?username=legado_plus" width="100">

#### Discord
[![Discord](https://img.shields.io/discord/560731361414086666?color=%235865f2&label=Discord)](https://discord.gg/VtUfRyzRXn)

#### Other
https://www.yuque.com/legado/wiki/community

<a href="#readme">
    <img src="https://img.shields.io/badge/-返回顶部-orange.svg" alt="#" align="right">
</a>

# API [![](https://img.shields.io/badge/-API-F5F5F5.svg)](#API-)
* 阅读3.0 提供了2种方式的API：`Web方式`和`Content Provider方式`。您可以在[这里](api.md)根据需要自行调用。 
* 可通过url唤起阅读进行一键导入,url格式: legado://import/{path}?src={url}
* path类型: bookSource,rssSource,replaceRule,textTocRule,httpTTS,theme,readConfig,dictRule,[addToBookshelf](/app/src/main/java/io/legado/app/ui/association/AddToBookshelfDialog.kt)
* path类型解释: 书源,订阅源,替换规则,本地txt小说目录规则,在线朗读引擎,主题,阅读排版,添加到书架

<a href="#readme">
    <img src="https://img.shields.io/badge/-返回顶部-orange.svg" alt="#" align="right">
</a>

# Other-其他 [![](https://img.shields.io/badge/-Other-F5F5F5.svg)](#Other-其他-)  

<details><summary>免责声明</summary>
阅读依赖系统webview提供网页访问功能，通过用户自定义的第三方网页书源返回阅读内容，阅读对其返回内容概不负责，亦不承担任何法律责任。任何第三方网页书源均系他人制作或提供，非软件作者，阅读对其合法性概不负责，亦不承担任何法律责任。第三方网页书源提供的试读，不代表阅读赞成其内容或立场。您应该对用搜索到的结果自行承担风险。<br>
任何单位或个人认为第三方网页书源内容可能涉嫌侵犯其信息网络传播权，应该及时向阅读提出书权力通知，并提供身份证明、权属证明及详细侵权情况证明。阅读在收到上述法律文件后，将会依法尽快屏蔽相关内容。<br>
</details>

##### 阅读3.0
* [书源规则](https://mgz0227.github.io/The-tutorial-of-Legado/)
* [更新日志](/app/src/main/assets/updateLog.md)
* [帮助文档](/app/src/main/assets/web/help/md/appHelp.md)

# 更新日志

【10-27】
- 修复：发布包名恢复固定值 com.myreaderD.reader.release
- 当前快照：3.26.102701

【10-03】
- 安装包按CPU架构拆分，新增arm精简版，减小下载体积
- 版本号随构建自动递增，便于覆盖升级
- 当前快照：3.26.100100

【10-02】
- 发现页改版：支持三列网格展示，书源编辑页可选择发现样式（列表/三列）
- 发现页新增快捷分组入口栏（长按分组进入书源管理），支持传统/新版发现页切换
- 分类胶囊标签按字数自适应宽度并自动换行
- EPUB 正文 ruby 注音与相邻文本合并渲染，避免注音替换后出现多余空白
- 当前快照：3.26.100100

【07-15】
- 新增导出链接为口令、自动识别口令导入
- 当前快照：3.26.071522

【07-04】
- 链接规则支持head类型
- 回调事件支持详情页点击标签
- 详情页简介图片支持执行js，支持长按查看
- 优化web端加载段评图片
- 详情页简介支持用web渲染
- 调整新用户预加载和搜索线程的默认值
- 其余优化与修复
- 当前快照：3.26.070423

【03-07】
- 优化代码，修复问题
- 当前快照：3.26.030717

【03-03】
- 优化代码，修复问题
- 视频悬浮窗播放时进行系统媒体播放通知
- 净化规则使用js时支持调用java.log
- 代码编辑器搜索替换内容支持$符号
- 优化书架滚动位置记忆
- 增加搜索结果排序时对书籍分类信息进行判断
- 增加自动检查app更新功能
- 当前快照：3.26.030223

【02-16】
- 优化代码，修复问题
- 让小说朗读走系统媒体播放通道
- 更新内置字典规则
- 新增java.refreshBookToc函数
- java.reLoginView函数增加deltaUp参数
- 新增@webjs:规则类型
- 文件类书源支持下载链接type指定文件后缀
- 提升购买按钮权限
- 当前快照：3.26.021623

【01-31】
- 优化代码，修复一些问题
- 正文增加锁定反向横屏
- 当前快照：3.26.013122

【01-28】
- 新增java.reLoginView()函数，刷新登录界面
- 书源发现支持更多丰富的按钮类型
- 新增java.refreshExplore()函数
- java.open函数支持打开登录界面
- 书源简介支持html标签包裹，显示html样式
- 书籍简介和字典支持gif动态图和svg图data链接
- 书籍简介和字典支持button按钮
- 支持源控制图片显示尺寸
- 书籍简介支持markdown语法编写
- 新增java.showBrowser函数，能进行半屏显示段评
- 支持图片链接click键，不推荐继续使用旧方式
- 支持双击响应段评图
- 新增chapter.update()函数
- 新增java.showPhoto函数
- 新增java.refreshContent()函数
- 支持订阅源启动页html用js返回空
- 提升webview函数获取js结果速度
- 其余优化与修复
- 当前快照：3.26.012900

【01-13】
- 软件自定义背景图支持.9.png格式
- 背景图导入支持直接输入图片在线链接
- 主题分享支持在线背景图链接
- 背景图支持跟随主题切换
- 主题设置支持透明操作栏，提升图片背景视觉效果
- 支持分组封面自定义图片恢复默认
- 登录UI的select类型支持action键
- 提升内置浏览器打开速度（例：订阅源、段评 打开速度大概快100毫秒左右）
- 支持正文下划线设为虚线类型
- cache.get函数新增onlyDisk参数
- tts源支持jslib规则
- tts源登录界面新增java.clearTtsCache()函数
- 支持导出单个tts源
- 编辑tts源、字典规则、TXT目录规则时误触空白区域会提示保存
- 新增正文边缘点击阈值设置，防止曲面屏误触
- 实现订阅源的登录检查规则
- 在链接访问出错时，也能执行一次登录检查规则
- StrResponse对象支持callTime()获取响应时间
- 并发访问函数支持skipRateLimit参数，绕过源并发率限制
- 视频播放器支持记录函数调用时的播放进度
- 其余细节优化与bug修复
- 当前快照：3.26.011319

<a href="#readme">
    <img src="https://img.shields.io/badge/-返回顶部-orange.svg" alt="#" align="right">
</a>

# Grateful-感谢 [![](https://img.shields.io/badge/-Grateful-F5F5F5.svg)](#Grateful-感谢-)
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
<a href="#readme">
    <img src="https://img.shields.io/badge/-返回顶部-orange.svg" alt="#" align="right">
</a>

# Interface-界面 [![](https://img.shields.io/badge/-Interface-F5F5F5.svg)](#Interface-界面-)
<img src="https://channel.qpic.cn/psc?/channel/NaDwC23LjvXqrn3RH.9z8bdVVU8X7gejuELokmv2DGfJwQpP2UFcwHqqFK4nzfgNUTL6Wij4QqsJySkoPfQuCB1l5MstXCXWjmH5Mm6PGkU!/b=&bo=UQRICVEESAkWADA!&ek=1&tl=1" width="270">  <img src="https://channel.qpic.cn/psc?/channel/NaDwC23LjvXqrn3RH.9z8epqbe*Bzro*ybEEVmlLpuD9E14zFMjtRx2JeZJdVzNYeSZqCm5Kv0MruAumEe0tJ77n*agIEzJIaSN9VHAen2I!/b=&bo=UQRICVEESAkWADA!&ek=1&tl=1" width="270">  
<img src="https://channel.qpic.cn/psc?/channel/NaDwC23LjvXqrn3RH.9z8SQggDiubhwWyktI5v48VluvK1vwdY2OV9RauO04TcXb4425kVcBiAz2DhpfnrDZKnSpwp8SLnDtKOxtLm1yP9I!/b=&bo=UQRICVEESAkWADA!&ek=1&tl=1" width="270">  <img src="https://channel.qpic.cn/psc?/channel/NaDwC23LjvXqrn3RH.9z8Tx.7oqZyCeAdzSVQa6ZgtXKE*EaUCdEJ3zm2ynIrgem10TqY8aKvSbG3v5FNNHtwSFyN5QfLW*Zd14gUetfsC8!/b=&bo=UgRKCVIESgkWADA!&ek=1&tl=1" width="270">

<a href="#readme">
    <img src="https://img.shields.io/badge/-返回顶部-orange.svg" alt="#" align="right">
</a>
