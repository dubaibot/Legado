package io.legado.app.help.python

/**
 * 内置插件源：提供预置测试插件的源码。
 * 首次启动时若无已安装插件，自动安装测试影视源。
 */
object PyBuiltinPlugins {

    /** 测试影视源插件源码 */
    val testVideoPlugin: String = """
# 测试影视源插件：提供 search/detail/play 函数。
# 用于验证 Python 影视源管线：搜索 -> 详情 -> 播放。
# 返回 JSON 字符串，与 JS 源格式兼容。

import json


def search(keyword):
    # 搜索影视内容。
    results = []
    for i in range(1, 4):
        results.append({
            "id": keyword + "_" + str(i),
            "title": keyword + " 测试结果 " + str(i),
            "url": "https://example.com/video/" + str(i),
            "type": "video",
        })
    return json.dumps(results, ensure_ascii=False)


def detail(id):
    # 获取影视详情。
    return json.dumps({
        "id": id,
        "title": "测试影视: " + id,
        "year": "2024",
        "director": "测试导演",
        "actors": ["测试演员A", "测试演员B"],
        "description": "这是一个测试影视源的详情页面。",
        "play_urls": [
            {"name": "线路1", "url": "https://example.com/play/" + id + "/1"},
            {"name": "线路2", "url": "https://example.com/play/" + id + "/2"},
        ],
    }, ensure_ascii=False)


def play(url):
    # 获取播放地址。
    return json.dumps({
        "url": url,
        "headers": {"User-Agent": "Mozilla/5.0"},
    }, ensure_ascii=False)
""".trimIndent()

    /** 测试插件元信息 JSON */
    val testVideoMeta: String = """
{"id":"test_video","name":"测试影视源","version":"1.0.0","type":"video","author":"Legado","desc":"测试影视源插件","permissions":["search","detail","play"]}
""".trimIndent()
}
