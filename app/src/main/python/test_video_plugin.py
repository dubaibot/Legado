"""测试影视源插件：提供 search/detail/play 函数。

用于验证 Python 影视源管线：搜索 -> 详情 -> 播放。
返回 JSON 字符串，与 JS 源格式兼容。
"""

import json


def search(keyword):
    """搜索影视内容。

    Args:
        keyword: 搜索关键词

    Returns:
        JSON 字符串，包含搜索结果列表
    """
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
    """获取影视详情。

    Args:
        id: 影视 ID

    Returns:
        JSON 字符串，包含详情信息和播放线路
    """
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
    """获取播放地址。

    Args:
        url: 播放线路 URL

    Returns:
        JSON 字符串，包含最终播放地址和请求头
    """
    return json.dumps({
        "url": url,
        "headers": {"User-Agent": "Mozilla/5.0"},
    }, ensure_ascii=False)
