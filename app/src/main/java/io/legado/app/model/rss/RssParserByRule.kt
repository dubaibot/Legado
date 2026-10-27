package io.legado.app.model.rss

import androidx.annotation.Keep
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.RssSource
import io.legado.app.data.repository.debug.RssExecutionRecorder
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.config.AppConfig
import io.legado.app.help.source.autoNextPageEnabled
import io.legado.app.model.Debug
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setRuleData
import io.legado.app.model.analyzeRule.RuleData
import io.legado.app.model.debug.DebugCategory
import io.legado.app.model.debug.RssExecutionStep
import io.legado.app.model.debug.RuleExecutionTracker
import io.legado.app.model.debug.RuleType
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.isAbsUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import splitties.init.appCtx
import java.util.Locale

@Keep
object RssParserByRule {

    @Throws(Exception::class)
    suspend fun parseXML(
        sortName: String,
        sortUrl: String,
        redirectUrl: String,
        body: String?,
        rssSource: RssSource,
        ruleData: RuleData
    ): Pair<MutableList<RssArticle>, String?> {
        val sourceUrl = rssSource.sourceUrl
        val recorder = RssExecutionRecorder
        RssExecutionRecorder.startSession(sourceUrl, rssSource.sourceName)
        // 相对地址解析基址：`sortUrl` 可能是 `@js:`/`<js>` 规则或相对模板（不是合法绝对地址），
        // 用它当基址会让「下一页/规则内相对地址」解析失败并抛 MalformedURLException。
        // 此时回落到「规则求值后实际请求到的 URL」(redirectUrl)；sortUrl 本身是绝对地址时沿用，既有源零变化。
        val listBaseUrl = resolveListBaseUrl(sortUrl, redirectUrl)
        AppLog.putDebugWithTag(AppLog.TAG_RSS, "开始解析RSS XML sourceHash=${rssSource.sourceUrl.hashCode()} sortName=$sortName bodyLen=${body?.length ?: 0}", level = AppLog.Level.INFO)
        var nextUrl: String? = null
        if (body.isNullOrBlank()) {
            RssExecutionRecorder.failed(RssExecutionStep.PARSE_LIST, "响应内容为空")
            throw NoStackTraceException(
                appCtx.getString(R.string.error_get_web_content, rssSource.sourceUrl)
            )
        }
        Debug.log(sourceUrl, body, state = 10, category = DebugCategory.RSS)
        // 预处理 body：将 HTML 实体 &nbsp; 替换为 XML 合法数字实体 &#160;，
        // 避免 KXmlParser 报 XmlPullParserException: unresolved: &nbsp;
        val processedBody = body!!.replace("&nbsp;", "&#160;")
        var ruleArticles = rssSource.ruleArticles
        if (ruleArticles.isNullOrBlank()) {
            Debug.log(sourceUrl, "⇒列表规则为空, 使用默认规则解析", category = DebugCategory.RSS)
            AppLog.putDebugWithTag(AppLog.TAG_RSS, "RSS使用默认规则解析 sourceHash=${rssSource.sourceUrl.hashCode()}", level = AppLog.Level.INFO)
            val result = RssParserDefault.parseXML(sortName, processedBody, sourceUrl)
            if (result.first.isNotEmpty()) {
                recorder.success(
                    RssExecutionStep.PARSE_LIST,
                    detail = "默认XML解析获取${result.first.size}条"
                )
            } else {
                recorder.failed(RssExecutionStep.PARSE_LIST, "默认XML解析未获取到文章")
            }
            RssExecutionRecorder.endSession()
            return result
        } else {
            // 循环外的 analyzeRule 用于获取列表集合/下一页/规则拆分（串行执行，结果不可变，可安全共享）
            val analyzeRule = AnalyzeRule(ruleData, rssSource)
            analyzeRule.setCoroutineContext(currentCoroutineContext())
            analyzeRule.setContent(processedBody).setBaseUrl(listBaseUrl)
            analyzeRule.setRedirectUrl(redirectUrl)
            var reverse = false
            if (ruleArticles.startsWith("-")) {
                reverse = true
                ruleArticles = ruleArticles.substring(1)
            }
            // 列表规则追踪：构建执行树用于调试展示
            Debug.log(sourceUrl, "┌获取列表", category = DebugCategory.RSS)
            val parseStart = System.currentTimeMillis()
            val listTracker = RuleExecutionTracker(rssSource, ruleArticles, "列表规则")
            listTracker.startStep(RuleType.DEFAULT, ruleArticles, body)
            val collections = analyzeRule.getElements(ruleArticles)
            val listDuration = System.currentTimeMillis() - parseStart
            listTracker.endStep("${collections.size}个元素", matchCount = collections.size)
            val listTree = listTracker.buildTree()
            Debug.log(sourceUrl, "└列表大小:${collections.size}", category = DebugCategory.RSS)
            if (collections.isNotEmpty()) {
                recorder.success(
                    RssExecutionStep.PARSE_LIST,
                    detail = "获取${collections.size}条",
                    duration = listDuration
                )
                recorder.ruleSuccess(
                    step = RssExecutionStep.PARSE_RULE_ARTICLES,
                    ruleContent = ruleArticles,
                    executionTree = listTree,
                    input = body.take(200),
                    output = "${collections.size}个元素",
                    matchCount = collections.size,
                    duration = listDuration
                )
            } else {
                recorder.failed(
                    RssExecutionStep.PARSE_LIST,
                    "列表为空",
                    duration = listDuration
                )
            }
            if (!rssSource.ruleNextPage.isNullOrEmpty()) {
                Debug.log(sourceUrl, "┌获取下一页链接", category = DebugCategory.RSS)
                val nextStart = System.currentTimeMillis()
                val nextTracker = RuleExecutionTracker(rssSource, rssSource.ruleNextPage!!, "下一页规则")
                nextTracker.startStep(RuleType.DEFAULT, rssSource.ruleNextPage!!, null)
                if (rssSource.ruleNextPage!!.uppercase(Locale.getDefault()) == "PAGE") {
                    nextUrl = sortUrl
                } else {
                    nextUrl = analyzeRule.getString(rssSource.ruleNextPage)
                    if (nextUrl.isNotEmpty()) {
                        nextUrl = NetworkUtils.getAbsoluteURL(listBaseUrl, nextUrl)
                    }
                }
                val nextDuration = System.currentTimeMillis() - nextStart
                nextTracker.endStep(nextUrl)
                val nextTree = nextTracker.buildTree()
                Debug.log(sourceUrl, "└$nextUrl", category = DebugCategory.RSS)
                recorder.ruleSuccess(
                    step = RssExecutionStep.PARSE_RULE_NEXT_PAGE,
                    ruleContent = rssSource.ruleNextPage,
                    executionTree = nextTree,
                    output = nextUrl,
                    duration = nextDuration
                )
            } else if (rssSource.autoNextPageEnabled(sortUrl)) {
                // 老源兼容：未填下一页规则但URL含{{page}}占位符 → 隐式PAGE模式自动翻页
                Debug.log(sourceUrl, "┌获取下一页链接(隐式PAGE模式)", category = DebugCategory.RSS)
                nextUrl = sortUrl
            }
            val ruleTitle = analyzeRule.splitSourceRule(rssSource.ruleTitle)
            val rulePubDate = analyzeRule.splitSourceRule(rssSource.rulePubDate)
            val ruleDescription = analyzeRule.splitSourceRule(rssSource.ruleDescription)
            val ruleImage = analyzeRule.splitSourceRule(rssSource.ruleImage)
            val ruleLink = analyzeRule.splitSourceRule(rssSource.ruleLink)
            val variable = ruleData.getVariable()
            // P1-1 并行化：for 循环改 async{}.awaitAll() + Semaphore 限流
            // 🔴 硬性前提1：每item独立 AnalyzeRule 实例（getItem内setRuleData/setContent修改实例状态，复用会并发数据错乱；AnalyzeRule evalJSCallCount++/topScopeRef/scriptCache非线程安全）
            // 🔴 硬性前提2：articleList不在并行块内add（mutableListOf非线程安全），awaitAll后批量收集
            // 顺序保证：mapIndexed+awaitAll保持发起顺序，filterNotNull保持顺序，与原for循环结果一致
            // 并发数：源级parseConcurrency(>0)优先，否则使用全局配置rssParseConcurrency(默认3)
            val parseConcurrency = if (rssSource.parseConcurrency > 0) {
                rssSource.parseConcurrency
            } else {
                AppConfig.rssParseConcurrency
            }
            Debug.log(sourceUrl, "┌并行解析列表项(共${collections.size}项,限流${parseConcurrency})", category = DebugCategory.RSS)
            val parseSemaphore = Semaphore(parseConcurrency)
            val articleList = coroutineScope {
                collections.mapIndexed { index, item ->
                    async(Dispatchers.IO) {
                        parseSemaphore.withPermit {
                            // 硬性前提1：独立 AnalyzeRule 实例
                            val itemRule = AnalyzeRule(ruleData, rssSource)
                            itemRule.setCoroutineContext(currentCoroutineContext())
                            itemRule.setBaseUrl(listBaseUrl)
                            itemRule.setRedirectUrl(redirectUrl)
                            try {
                                getItem(
                                    sourceUrl, item, itemRule, variable, rssSource.type, index == 0,
                                    ruleTitle, rulePubDate, ruleDescription, ruleImage, ruleLink,
                                    rssSource, recorder
                                )
                            } catch (e: Exception) {
                                AppLog.put("RSS列表项解析失败 index=$index", e)
                                null
                            }
                        }
                    }
                }.awaitAll().filterNotNull().toMutableList()
            }
            // 硬性前提2：统一设置 sort 和 origin（原在for循环内逐项设置，现批量设置）
            articleList.forEach {
                it.sort = sortName
                it.origin = sourceUrl
            }
            Debug.log(sourceUrl, "└并行解析完成(成功${articleList.size}/${collections.size}项)", category = DebugCategory.RSS)
            if (reverse) {
                articleList.reverse()
            }
            AppLog.putDebugWithTag(AppLog.TAG_RSS, "RSS自定义规则解析完成 sourceHash=${rssSource.sourceUrl.hashCode()} 文章数=${articleList.size} 总数=${collections.size}", level = AppLog.Level.INFO)
            RssExecutionRecorder.endSession()
            return Pair(articleList, nextUrl)
        }
    }

    private fun getItem(
        sourceUrl: String,
        item: Any,
        analyzeRule: AnalyzeRule,
        variable: String?,
        type: Int,
        log: Boolean,
        ruleTitle: List<AnalyzeRule.SourceRule>,
        rulePubDate: List<AnalyzeRule.SourceRule>,
        ruleDescription: List<AnalyzeRule.SourceRule>,
        ruleImage: List<AnalyzeRule.SourceRule>,
        ruleLink: List<AnalyzeRule.SourceRule>,
        rssSource: RssSource,
        recorder: RssExecutionRecorder
    ): RssArticle? {
        val rssArticle = RssArticle(variable = variable)
        analyzeRule.setRuleData(rssArticle)
        analyzeRule.setContent(item)

        // 追踪标题规则执行
        val titleRuleStr = ruleTitle.joinToString("&&") { it.rule }
        val titleStart = System.currentTimeMillis()
        Debug.log(sourceUrl, "┌获取标题", log, category = DebugCategory.RSS)
        rssArticle.title = analyzeRule.getString(ruleTitle)
        val titleDuration = System.currentTimeMillis() - titleStart
        Debug.log(sourceUrl, "└${rssArticle.title}", log, category = DebugCategory.RSS)
        if (log) {
            if (rssArticle.title.isNotBlank()) {
                recorder.success(RssExecutionStep.EXTRACT_TITLE, detail = rssArticle.title.take(50))
                if (titleRuleStr.isNotBlank()) {
                    recorder.ruleSuccess(
                        step = RssExecutionStep.PARSE_RULE_TITLE,
                        ruleContent = titleRuleStr,
                        output = rssArticle.title,
                        duration = titleDuration
                    )
                }
            } else {
                recorder.failed(RssExecutionStep.EXTRACT_TITLE, "标题为空")
            }
        }

        // 追踪发布日期规则执行
        val pubDateRuleStr = rulePubDate.joinToString("&&") { it.rule }
        val pubDateStart = System.currentTimeMillis()
        Debug.log(sourceUrl, "┌获取时间", log, category = DebugCategory.RSS)
        rssArticle.pubDate = analyzeRule.getString(rulePubDate)
        val pubDateDuration = System.currentTimeMillis() - pubDateStart
        Debug.log(sourceUrl, "└${rssArticle.pubDate}", log, category = DebugCategory.RSS)
        if (log) {
            if (!rssArticle.pubDate.isNullOrBlank()) {
                recorder.success(RssExecutionStep.EXTRACT_PUB_DATE, detail = rssArticle.pubDate?.take(50))
                if (pubDateRuleStr.isNotBlank()) {
                    recorder.ruleSuccess(
                        step = RssExecutionStep.PARSE_RULE_PUB_DATE,
                        ruleContent = pubDateRuleStr,
                        output = rssArticle.pubDate,
                        duration = pubDateDuration
                    )
                }
            } else {
                recorder.failed(RssExecutionStep.EXTRACT_PUB_DATE, "未提取到发布日期")
            }
        }

        Debug.log(sourceUrl, "┌获取描述", log, category = DebugCategory.RSS)
        if (ruleDescription.isEmpty()) {
            rssArticle.description = null
            Debug.log(sourceUrl, "└描述规则为空，将会解析内容页", log, category = DebugCategory.RSS)
            if (log) recorder.success(RssExecutionStep.EXTRACT_DESCRIPTION, detail = "规则为空，将解析内容页")
        } else {
            // 追踪描述规则执行
            val descRuleStr = ruleDescription.joinToString("&&") { it.rule }
            val descStart = System.currentTimeMillis()
            rssArticle.description = analyzeRule.getString(ruleDescription)
            val descDuration = System.currentTimeMillis() - descStart
            Debug.log(sourceUrl, "└${rssArticle.description}", log, category = DebugCategory.RSS)
            if (log) {
                if (!rssArticle.description.isNullOrBlank()) {
                    recorder.success(RssExecutionStep.EXTRACT_DESCRIPTION, detail = rssArticle.description!!.take(50))
                    if (descRuleStr.isNotBlank()) {
                        recorder.ruleSuccess(
                            step = RssExecutionStep.PARSE_RULE_DESCRIPTION,
                            ruleContent = descRuleStr,
                            output = rssArticle.description,
                            duration = descDuration
                        )
                    }
                } else {
                    recorder.failed(RssExecutionStep.EXTRACT_DESCRIPTION, "未提取到描述")
                }
            }
        }

        Debug.log(sourceUrl, "┌获取图片url", log, category = DebugCategory.RSS)
        try {
            // 追踪图片规则执行
            val imageRuleStr = ruleImage.joinToString("&&") { it.rule }
            val imageStart = System.currentTimeMillis()
            analyzeRule.getString(ruleImage).let {
                if (it.isNotEmpty()) {
                    rssArticle.image = NetworkUtils.getAbsoluteURL(sourceUrl, it)
                }
            }
            val imageDuration = System.currentTimeMillis() - imageStart
            Debug.log(sourceUrl, "└${rssArticle.image ?: ""}", log, category = DebugCategory.RSS)
            if (log) {
                if (!rssArticle.image.isNullOrBlank()) {
                    recorder.success(RssExecutionStep.EXTRACT_IMAGE, detail = rssArticle.image!!.take(80))
                    if (imageRuleStr.isNotBlank()) {
                        recorder.ruleSuccess(
                            step = RssExecutionStep.PARSE_RULE_IMAGE,
                            ruleContent = imageRuleStr,
                            output = rssArticle.image,
                            duration = imageDuration
                        )
                    }
                } else {
                    recorder.failed(RssExecutionStep.EXTRACT_IMAGE, "未提取到图片")
                }
            }
        } catch (e: Exception) {
            AppLog.putDebugWithTag(AppLog.TAG_RSS, "RSS文章图片解析失败 sourceUrlLen=${sourceUrl.length}", e)
            Debug.log(sourceUrl, "└${e.localizedMessage}", log, category = DebugCategory.RSS)
            if (log) recorder.failed(RssExecutionStep.EXTRACT_IMAGE, e.message ?: "提取图片异常")
        }

        // 追踪链接规则执行
        val linkRuleStr = ruleLink.joinToString("&&") { it.rule }
        val linkStart = System.currentTimeMillis()
        Debug.log(sourceUrl, "┌获取文章链接", log, category = DebugCategory.RSS)
        rssArticle.link = analyzeRule.getString(ruleLink, isUrl = true)
        val linkDuration = System.currentTimeMillis() - linkStart
        Debug.log(sourceUrl, "└${rssArticle.link}", log, category = DebugCategory.RSS)
        if (log) {
            if (rssArticle.link.isNotBlank()) {
                recorder.success(RssExecutionStep.EXTRACT_LINK, detail = rssArticle.link.take(80))
                if (linkRuleStr.isNotBlank()) {
                    recorder.ruleSuccess(
                        step = RssExecutionStep.PARSE_RULE_LINK,
                        ruleContent = linkRuleStr,
                        output = rssArticle.link,
                        duration = linkDuration
                    )
                }
            } else {
                recorder.failed(RssExecutionStep.EXTRACT_LINK, "未提取到链接")
            }
        }
        rssArticle.type = type
        if (rssArticle.title.isBlank()) {
            return null
        }
        return rssArticle
    }
}

/**
 * 选择 RSS 列表解析时的「相对地址基址」。
 *
 * - `sortUrl` 本身是绝对地址（http/https）⇒ 沿用（既有源行为零变化）；
 * - 否则（`@js:` / `<js>` 规则串、`/path?pg={{page}}` 相对模板）⇒ 用「规则求值后实际请求到的 URL」，
 *   避免以非法基址调用 `URL(base)` 抛 `MalformedURLException` 致「下一页/规则内相对地址」解析失败。
 *
 * 纯函数，供 JVM 单测覆盖。
 */
internal fun resolveListBaseUrl(sortUrl: String, resolvedUrl: String): String =
    if (sortUrl.isAbsUrl()) sortUrl else resolvedUrl.ifBlank { sortUrl }
