package com.lanyue.reader.core.security

import com.lanyue.reader.core.engine.JsExecMode

/**
 * 5.5 源权限分级。
 * 表的每一行都映射为一个 [SourcePermissionLevel]，决定默认执行环境与网络权限。
 */
enum class SourcePermissionLevel {
    /** 书源：主上下文 IIFE，DOM + 受控 GET/POST（规则内 method 声明）+ 基础 JS */
    BOOK,

    /** 订阅源：主上下文 IIFE，同书源 */
    SUBSCRIPTION,

    /** 影视源（CMS json/xml）：无 JS，仅受控 HTTP + JSON/XML */
    VIDEO_CMS,

    /** 影视源（drpy/JS）：复用 iframe 沙箱，DOM + 受控 GET/POST（req() 默认放行） */
    VIDEO_DRPY,

    /** 影视源（spider jar，二期）：独立进程 ClassLoader 隔离（非安全边界） */
    VIDEO_SPIDER,

    /** 弹幕源：无 JS（REST），GET + POST（仅 dandanplay v2 端点） */
    DANMAKU,

    /** 未知源：独立沙箱，最低权限，导入需高危确认 */
    UNKNOWN
}

/** 5.5 运行时权限裁决结果。 */
data class PermissionGrant(
    val level: SourcePermissionLevel,
    val mode: JsExecMode,
    val allowJs: Boolean,
    val allowDom: Boolean,
    val allowGet: Boolean,
    val allowPost: Boolean,
    val requireHighRiskConfirm: Boolean
) {
    companion object {
        fun of(level: SourcePermissionLevel): PermissionGrant = when (level) {
            SourcePermissionLevel.BOOK -> PermissionGrant(
                level, JsExecMode.MAIN, allowJs = true, allowDom = true,
                allowGet = true, allowPost = true, requireHighRiskConfirm = false
            )
            SourcePermissionLevel.SUBSCRIPTION -> PermissionGrant(
                level, JsExecMode.MAIN, allowJs = true, allowDom = true,
                allowGet = true, allowPost = true, requireHighRiskConfirm = false
            )
            SourcePermissionLevel.VIDEO_CMS -> PermissionGrant(
                level, JsExecMode.MAIN, allowJs = false, allowDom = false,
                allowGet = true, allowPost = false, requireHighRiskConfirm = false
            )
            SourcePermissionLevel.VIDEO_DRPY -> PermissionGrant(
                level, JsExecMode.SANDBOX_REUSE, allowJs = true, allowDom = true,
                allowGet = true, allowPost = true, requireHighRiskConfirm = false
            )
            SourcePermissionLevel.VIDEO_SPIDER -> PermissionGrant(
                level, JsExecMode.SANDBOX_ISOLATED, allowJs = true, allowDom = false,
                allowGet = true, allowPost = false, requireHighRiskConfirm = true
            )
            SourcePermissionLevel.DANMAKU -> PermissionGrant(
                level, JsExecMode.MAIN, allowJs = false, allowDom = false,
                allowGet = true, allowPost = true, requireHighRiskConfirm = false
            )
            SourcePermissionLevel.UNKNOWN -> PermissionGrant(
                level, JsExecMode.SANDBOX_ISOLATED, allowJs = false, allowDom = false,
                allowGet = true, allowPost = false, requireHighRiskConfirm = true
            )
        }
    }
}
