# 更新日志

## cronet版本: 128.0.6613.40

【11-04】
- 修复：JSON 源 CMS API URL 构建参数错误（home 直接请求 baseUrl 无参数、category 用 ac=videolist、search 用 wd+quick 不带 ac、detail 用 ac=detail+ids）
- 修复：JSON 源支持自定义 Header（site header 字段解析并应用到所有请求）
- 修复：JSON 源自动附加 extend 参数（site ext 字段拼接到所有请求 URL）
- 修复：py 源 Spider 兼容层重写为 Python 包装类，正确映射关键字参数到 Java 位置参数
- 修复：call_spider_func 移除 isinstance(cls, type) 检查（Chaquopy Java 类不通过该检查）
- 修复：call_spider_func 增加 homeVideoContent 调用（对齐 ElderTV 行为）
- 修复：PyTaskQueue.callPluginFunc 移除 AttributeError 文本匹配限制，统一尝试 Spider fallback

【11-03】
- 新增：FongMi/TV 多源格式全面支持（json/txt/py/html 四类影视源）
- 新增：FongMi Config 多站点解析器（sites 数组展开为多个独立源）
- 新增：FongMi TXT 逐行格式解析器（名称$接口地址展开为多个 CMS 源）
- 新增：HtmlVideoSource（HTML 源 VideoSource 实现，基于 AnalyzeRule xpath/css 规则解析）
- 新增：VideoHttpClient 共享 HTTP 客户端（连接池 + 响应缓存 TTL 5 分钟）
- 新增：JsonVideoSource 支持 FongMi Config 多站点 + CMS V10 + 自定义端点
- 重写：VideoSourceRegistry 支持 PY/JSON/TXT/HTML 四类源创建与展开
- 新增：源实例缓存（VideoSourceRegistry ConcurrentHashMap，避免重复读盘+创建）
- 新增：py 源结果缓存（home/category/search TTL 5 分钟，减少 Python 解释器重复执行）
- 新增：后台预热机制（refreshSources 时并发预加载所有源 home 数据，切换源直接命中）
- 优化：切换源不清空缓存，切回来直接命中 homeCache/categoryCache
- 重构：ViewModel 统一走 VideoSource 接口，删除 bridge*/parse* 分叉逻辑（py/json/txt 一套代码）
- Spider 类兼容层，支持 FongMi/TV 约定的 py 源（无模块级函数时自动实例化 Spider 子类调用 homeContent/categoryContent/detailContent/searchContent/playerContent）
- 新增：插件调用结果归一化（Spider 类方法输出统一转换为 App 数据格式，兼容 CMS vod_play_from/vod_play_url 格式）
- 修复：首页顶栏移除源名称显示，内容区域满屏显示
- 新增：视频卡片封面图显示（Glide 加载，3:4 比例）
- 新增：分类浏览翻页控件（上一页/下一页 + 页码指示）
- 修复：详情弹窗选集列表横向滚动
- 修复：播放页面改用 VideoPlayerActivity（GSY 播放器），替换原简易 VideoView 浮层
- 优化：refreshSources 不再清空 HomeBridge 缓存，减少重复能力检测开销

【11-02】
- 新增：首页源管理页（导入 py 插件 / HTML 自定义页面，支持自定义名称与删除）
- 新增：预装 requests 库，修复 py 源导入即加载失败
- 新增：影视源播放功能（详情弹窗显示播放按钮，点击调用插件解析并播放 m3u8）
- 新增：首页分类标签栏 + 视频网格（调用插件 home/category 函数）
- 优化：首页设置菜单精简为导入/管理两项
- 优化：详情弹窗显示结构化描述而非原始 JSON
- 修复：SAF 导入 py 文件时扩展名检测失败导致误判为 HTML（按文件内容检测类型）
- 修复：SAF content:// URI 的 lastPathSegment 为空导致导入文件名丢失（改用 DocumentsContract 查询）
- 修复：源管理页顶栏被列表遮挡、关闭按钮不可见
- 修复：顶栏搜索按钮始终显示
- 修复：插件加载错误在管理页可视化展示
- 修复：启动崩溃 FileNotFoundException web/help/md/updateLog.md（复制 updateLog.md 到 web/help/md/ 路径）
- 修复：py 插件函数返回值序列化为 Python repr 而非 JSON，导致首页/搜索/详情均无法解析（改用 plugin_entry.call_func 确保 JSON 序列化）
- 修复：home() 网络请求超时导致首页一直显示"加载中"（分类不依赖网络，视频尝试加载失败返回空；loadHome 超时兜底设空数据）
- 修复：HTML 源导入后页面一直显示"加载中"（refreshSources 未调用 loadHtmlContent，已修复）
- 优化：py 源网络请求失败时打印错误日志（_fetch/home/category/search/detail/play 均输出异常信息）
- 优化：首页视频为空时显示引导提示而非"暂无视频"
- 修复：plugin_entry.detect_capabilities 移除 inspect.signature 依赖（Chaquopy 兼容性问题可能导致能力检测失败）
- 修复：HomeBridge.callPlugin 能力检测失败时 fallback 直接调用插件函数（绕过能力检测直接提交任务）
- 修复：call_func_json 调用失败时返回 {"error": "..."} 而非 "null"，UI 可见错误信息
- 优化：pyloader.py 加载日志输出到 stdout（logcat 可见）
- 修复：PyTaskQueue 改为直接从 Java 调用 module.callAttr(func) 而非 Python getattr（绕过 Chaquopy 模块对象 getattr 兼容性问题）
- 修复：函数调用失败时返回含 AttributeError 信息的 error JSON，UI 可见具体错误
- 新增：支持 JSON 视频源（苹果CMS 标准 API + 自定义端点两种模式，支持 home/category/search/detail/play/danmaku）
- 新增：支持 TXT 视频源（本地文本列表，分节分类与多集格式 "集名$url#集名$url"）
- 新增：弹幕功能（B站 XML / JSON 格式解析，Canvas 渲染，滚动/顶部/底部三种模式，进度驱动，开关按钮）
- 新增：VideoSource 统一源接口（py/json/txt 三种源实现 home/category/search/detail/play/danmaku 六方法）
- 新增：源导入支持 json/txt 文件（按扩展名 + 内容特征检测类型）
- 优化：播放器浮层新增 MediaController（暂停/进度控制）
- 优化：播放器浮层新增弹幕开关按钮与进度轮询驱动弹幕
- 当前快照：3.26.110210

【10-27】
- 优化：应用名称由「阅读D」改为「阅读」
- 优化：更换应用图标
- 优化：版本号与发布包名规范化
- 当前快照：3.26.102702

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


## **必读**
来源于fork仓库 [Luoyacheng/legado-E](https://github.com/Luoyacheng/legado-E)　  
[查看实时详细日志](https://gitee.com/lyc486/legado/commits/main)　 

【温馨提醒】 *更新前一定要做好备份，以免数据丢失！*  
* 阅读只是一个转码工具，不提供内容，第一次安装app，需要自己手动导入书源。
* 正文出现缺字漏字、内容缺失、排版错乱等情况，有可能是净化规则或简繁转换出现问题。
----

* [2025年日志](https://github.com/Luoyacheng/legado-E/blob/record2025/app/src/main/assets/updateLog.md)　
