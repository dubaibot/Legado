"""插件桥接模块：提供统一的函数调用接口、能力检测和异常兜底。

call_func 封装插件函数调用，处理参数适配和异常，返回 JSON 字符串。
detect_capabilities 扫描插件模块，返回支持的函数签名列表。
"""

import json


def call_func(module, func_name, args):
    """调用插件模块的函数，带异常兜底。

    Args:
        module: 插件模块对象
        func_name: 函数名
        args: 参数列表

    Returns:
        tuple: (result_json_string, error_message)
        result 为 JSON 字符串，error 为 None 表示成功
    """
    func = getattr(module, func_name, None)
    if func is None or not callable(func):
        return None, "function not found or not callable: " + func_name

    try:
        result = func(*args)
        return _to_json(result), None
    except TypeError as e:
        # 参数不匹配：尝试用第一个参数调用（单参数函数）
        if len(args) >= 1:
            try:
                result = func(args[0])
                return _to_json(result), None
            except Exception:
                pass
        return None, "TypeError: " + str(e)
    except Exception as e:
        return None, str(e)


def call_func_json(module, func_name, args):
    """调用插件函数，仅返回 JSON 字符串（不返回错误信息）。

    供 PyTaskQueue 使用：避免 Java 侧处理 Python tuple。
    调用失败时返回 {"error": "..."} 并打印错误到 stdout。
    """
    func = getattr(module, func_name, None)
    if func is None or not callable(func):
        print("[call_func_json] function not found: " + func_name)
        return json.dumps({"error": "function not found: " + func_name})
    try:
        result = func(*args)
        return _to_json(result)
    except TypeError as e:
        # 参数不匹配：尝试用第一个参数调用（单参数函数）
        if len(args) >= 1:
            try:
                result = func(args[0])
                return _to_json(result)
            except Exception:
                pass
        err = "TypeError: " + str(e)
        print("[call_func_json] " + err)
        return json.dumps({"error": err})
    except Exception as e:
        err = func_name + ": " + str(e)
        print("[call_func_json] " + err)
        return json.dumps({"error": err})


def detect_capabilities(module):
    """检测插件模块的能力。

    Returns:
        JSON 字符串: {函数名: [参数名列表]}，仅包含模块中实际定义的函数
    """
    funcs = {}
    for name in ("search", "detail", "play", "home", "category", "enabled"):
        func = getattr(module, name, None)
        if func is not None and callable(func):
            funcs[name] = []
    return json.dumps(funcs, ensure_ascii=False)


def call_spider_func(module, func_name, args):
    """Spider 类兼容层：模块级函数不存在时，实例化 Spider 子类调用对应方法。

    FongMi/TV 约定：Spider 子类方法 homeContent/categoryContent/detailContent/
    searchContent/playerContent。本函数将模块级函数名映射到 Spider 方法名并调用。

    Args:
        module: 插件模块对象
        func_name: 模块级函数名（home/category/search/detail/play）
        args: 参数列表

    Returns:
        JSON 字符串（成功时为结果 JSON，失败时为 {"error": "..."}）
    """
    spider_cls = getattr(module, "Spider", None)
    if spider_cls is None or not isinstance(spider_cls, type):
        return None  # 返回 None 表示无 Spider 类，Kotlin 侧走原始错误

    try:
        spider = spider_cls()
        init_func = getattr(spider, "init", None)
        if callable(init_func):
            try:
                init_func("")
            except Exception:
                pass
    except Exception as e:
        return json.dumps({"error": "Spider instantiation failed: " + str(e)})

    try:
        if func_name == "home":
            raw = spider.homeContent("")
            result = _normalize_home(raw)
        elif func_name == "category":
            tid = str(args[0]) if args else ""
            pg = str(args[1]) if len(args) > 1 else "1"
            raw = spider.categoryContent(tid, pg, "", "")
            result = _normalize_category(raw)
        elif func_name == "search":
            keyword = str(args[0]) if args else ""
            raw = spider.searchContent(keyword, False, "1")
            result = _normalize_search(raw)
        elif func_name == "detail":
            id_val = str(args[0]) if args else ""
            raw = spider.detailContent([id_val])
            result = _normalize_detail(raw, id_val)
        elif func_name == "play":
            url = str(args[0]) if args else ""
            raw = spider.playerContent("", url, [])
            result = _normalize_play(raw)
        else:
            return json.dumps({"error": "Unsupported func: " + func_name})
        return _to_json(result)
    except Exception as e:
        print("[call_spider_func] " + func_name + ": " + str(e))
        return json.dumps({"error": func_name + ": " + str(e)})


def _normalize_video_item(v):
    """将 Spider 视频项（vod_id/vod_name/vod_pic/vod_remarks）归一化为 App 格式。"""
    return {
        "id": str(v.get("vod_id", v.get("id", ""))),
        "title": v.get("vod_name", v.get("title", "")),
        "pic": v.get("vod_pic", v.get("pic", "")),
        "remarks": v.get("vod_remarks", v.get("remarks", "")),
    }


def _normalize_home(raw):
    """归一化 homeContent 输出：{class,list,filters} -> {categories,videos}。"""
    if not isinstance(raw, dict):
        return {"categories": [], "videos": []}
    cats = []
    for c in raw.get("class", []):
        cats.append({"id": str(c.get("type_id", "")), "name": c.get("type_name", "")})
    vids = [_normalize_video_item(v) for v in raw.get("list", [])]
    return {"categories": cats, "videos": vids}


def _normalize_category(raw):
    """归一化 categoryContent 输出：{page,pagecount,list} -> {page,pagecount,videos}。"""
    if not isinstance(raw, dict):
        return {"page": 1, "pagecount": 1, "videos": []}
    return {
        "page": int(raw.get("page", 1) or 1),
        "pagecount": int(raw.get("pagecount", 1) or 1),
        "videos": [_normalize_video_item(v) for v in raw.get("list", [])],
    }


def _normalize_search(raw):
    """归一化 searchContent 输出：{list,page,pagecount} -> [items]。"""
    if not isinstance(raw, dict):
        return []
    items = []
    for v in raw.get("list", []):
        vid = str(v.get("vod_id", v.get("id", "")))
        items.append({
            "id": vid,
            "title": v.get("vod_name", v.get("title", "")),
            "url": v.get("url", v.get("link", "")),
            "pic": v.get("vod_pic", v.get("pic", "")),
        })
    return items


def _normalize_detail(raw, fallback_id):
    """归一化 detailContent 输出：{list:[detail]} -> {id,title,pic,desc,play_urls}。

    play_urls 格式：[{"name","url"}]，由 CMS vod_play_from/vod_play_url 转换。
    """
    detail = {}
    if isinstance(raw, dict) and isinstance(raw.get("list"), list) and raw["list"]:
        detail = raw["list"][0]
    elif isinstance(raw, dict):
        detail = raw
    else:
        return {"id": fallback_id, "title": fallback_id, "pic": "", "desc": "", "play_urls": []}

    play_urls = []
    from_str = str(detail.get("vod_play_from", ""))
    url_str = str(detail.get("vod_play_url", ""))
    from_names = [n for n in from_str.split("$$$") if n]
    url_groups = [g for g in url_str.split("$$$") if g]
    for idx, from_name in enumerate(from_names):
        url_group = url_groups[idx] if idx < len(url_groups) else ""
        for ep in url_group.split("#"):
            if not ep:
                continue
            parts = ep.split("$")
            ep_url = parts[-1]
            if ep_url:
                ep_name = parts[0] if len(parts) >= 2 else ""
                play_urls.append({"name": f"{from_name}·{ep_name}" if ep_name else from_name, "url": ep_url})

    return {
        "id": str(detail.get("vod_id", detail.get("id", fallback_id))),
        "title": detail.get("vod_name", detail.get("title", fallback_id)),
        "pic": detail.get("vod_pic", detail.get("pic", "")),
        "desc": detail.get("vod_content", detail.get("desc", "")),
        "play_urls": play_urls,
    }


def _normalize_play(raw):
    """归一化 playerContent 输出：{parse,url,header} -> {url,headers}。"""
    if not isinstance(raw, dict):
        return {"url": str(raw) if raw else "", "headers": {}}
    result = {}
    url = raw.get("url", "")
    if url:
        result["url"] = str(url)
    header = raw.get("header")
    if isinstance(header, dict):
        result["headers"] = header
    elif isinstance(raw.get("headers"), dict):
        result["headers"] = raw["headers"]
    return result


def _to_json(obj):
    """将 Python 对象转为 JSON 字符串。"""
    if obj is None:
        return "null"
    if isinstance(obj, str):
        return json.dumps(obj, ensure_ascii=False)
    if isinstance(obj, (list, dict)):
        return json.dumps(obj, ensure_ascii=False, default=str)
    return json.dumps(str(obj), ensure_ascii=False)
