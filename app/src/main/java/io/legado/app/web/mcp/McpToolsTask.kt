package io.legado.app.web.mcp

/**
 * 定时任务 MCP 工具注册的占位实现。
 *
 * 原实现依赖 io.modelcontextprotocol:kotlin-sdk-server 的 Server/ToolSchema/ToolAnnotations,
 * 当前项目未引入该库。此处保留 registerTaskTools() 的公共 API 为空实现,
 * 保证代码可在不依赖 modelcontextprotocol 的情况下编译。
 */
internal class Server

internal fun Server.registerTaskTools() {
    // no-op:当前项目未集成 modelcontextprotocol MCP SDK
}
