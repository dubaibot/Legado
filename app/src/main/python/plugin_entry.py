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
        # 参数不匹配：依次兼容双参数和单参数函数。
        for count in (2, 1):
            if len(args) <= count:
                continue
            try:
                result = func(*args[:count])
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
        # 参数不匹配：依次兼容双参数和单参数函数。
        for count in (2, 1):
            if len(args) <= count:
                continue
            try:
                result = func(*args[:count])
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
        None 表示无 Spider 类，Kotlin 侧走原始错误路径
    """
    spider_cls = getattr(module, "Spider", None)
    if spider_cls is None:
        print("[call_spider_func] no Spider class found in module")
        return None

    # Chaquopy 兼容：Java 类在 Chaquopy 中不是 Python type，
    # 尝试直接实例化而非依赖 isinstance 检查
    try:
        spider = spider_cls()
    except Exception as e:
        print("[call_spider_func] Spider instantiation failed: " + str(e))
        return None

    try:
        init_func = getattr(spider, "init", None)
        if callable(init_func):
            try:
                init_func("")
            except Exception:
                pass
    except Exception as e:
        print("[call_spider_func] init failed (ignored): " + str(e))

    try:
        if func_name == "home":
            try:
                raw = _call_method(spider, "homeContent", [True], [])
                result = _normalize_home(raw)
            except Exception as e:
                print("[call_spider_func] homeContent: " + str(e))
                result = _normalize_home(None)
            # 调用 homeVideoContent 获取首页推荐视频（ElderTV 行为）
            try:
                video_raw = _call_method(spider, "homeVideoContent", [], None)
                video_result = _normalize_home(video_raw)
                if video_result.get("videos"):
                    result["videos"] = video_result["videos"]
            except Exception:
                pass
        elif func_name == "category":
            tid = str(args[0]) if args else ""
            pg = str(args[1]) if len(args) > 1 else "1"
            extend = _as_mapping(args[2]) if len(args) > 2 else {}
            if not isinstance(extend, dict):
                extend = {}
            raw = _call_method(
                spider,
                "categoryContent",
                [tid, pg, True, extend],
                [tid, pg, extend],
                [tid, pg],
            )
            result = _normalize_category(raw)
        elif func_name == "search":
            keyword = str(args[0]) if args else ""
            raw = _call_method(
                spider,
                "searchContent",
                [keyword, False, "1"],
                [keyword, "1"],
                [keyword],
            )
            result = _normalize_search(raw)
        elif func_name == "detail":
            id_val = str(args[0]) if args else ""
            raw = _call_method(spider, "detailContent", [[id_val]], [id_val])
            result = _normalize_detail(raw, id_val)
        elif func_name == "play":
            url = str(args[0]) if args else ""
            flag = str(args[1]) if len(args) > 1 else ""
            raw = _call_method(
                spider,
                "playerContent",
                [flag, url, []],
                [flag, url],
                [url],
            )
            result = _normalize_play(raw)
        else:
            return json.dumps({"error": "Unsupported func: " + func_name})
        return _to_json(result)
    except Exception as e:
        print("[call_spider_func] " + func_name + ": " + str(e))
        return json.dumps({"error": func_name + ": " + str(e)})


def _call_method(obj, name, *arg_options):
    """Call a Spider method across common FongMi/hipy signatures."""
    method = getattr(obj, name, None)
    if not callable(method):
        if arg_options and arg_options[-1] is None:
            return None
        raise AttributeError("Spider method not found: " + name)
    last_error = None
    for option in arg_options:
        if option is None:
            continue
        try:
            return method(*option)
        except TypeError as error:
            last_error = error
    if last_error is not None:
        raise last_error
    return None


def _as_mapping(raw):
    """Decode a Spider result that may be a dict, list, or JSON string."""
    if isinstance(raw, str):
        try:
            return json.loads(raw)
        except Exception:
            return raw
    return raw


def _normalize_video_item(v):
    """将 Spider 视频项（vod_id/vod_name/vod_pic/vod_remarks）归一化为 App 格式。"""
    if not isinstance(v, dict):
        return {"id": "", "title": str(v), "url": str(v)}
    return {
        "id": str(v.get("vod_id", v.get("id", ""))),
        "title": v.get("vod_name", v.get("title", "")),
        "url": v.get("url", v.get("link", v.get("vod_id", v.get("id", "")))),
        "pic": v.get("vod_pic", v.get("pic", "")),
        "remarks": v.get("vod_remarks", v.get("remarks", "")),
    }


def _normalize_home(raw):
    """归一化 homeContent 输出：{class,list,filters} -> {categories,videos}。"""
    raw = _as_mapping(raw)
    if isinstance(raw, list):
        return {"categories": [], "videos": [_normalize_video_item(v) for v in raw]}
    if not isinstance(raw, dict):
        return {"categories": [], "videos": []}
    cats = []
    for c in raw.get("class", raw.get("categories", [])) or []:
        if isinstance(c, dict):
            cats.append({
                "id": str(c.get("type_id", c.get("id", c.get("tid", "")))),
                "name": c.get("type_name", c.get("name", c.get("title", ""))),
            })
    vids = [_normalize_video_item(v) for v in (raw.get("list", raw.get("videos", [])) or [])]
    result = {"categories": cats, "videos": vids}
    if "filters" in raw:
        result["filters"] = raw["filters"]
    return result


def _normalize_category(raw):
    """归一化 categoryContent 输出：{page,pagecount,list} -> {page,pagecount,videos}。"""
    raw = _as_mapping(raw)
    if isinstance(raw, list):
        return {"page": 1, "pagecount": 1, "videos": [_normalize_video_item(v) for v in raw]}
    if not isinstance(raw, dict):
        return {"page": 1, "pagecount": 1, "videos": []}
    return {
        "page": int(raw.get("page", raw.get("pg", 1)) or 1),
        "pagecount": int(raw.get("pagecount", raw.get("page_count", 1)) or 1),
        "videos": [_normalize_video_item(v) for v in (raw.get("list", raw.get("videos", [])) or [])],
    }


def _normalize_search(raw):
    """归一化 searchContent 输出：{list,page,pagecount} -> [items]。"""
    raw = _as_mapping(raw)
    if isinstance(raw, list):
        return [_normalize_video_item(v) for v in raw]
    if not isinstance(raw, dict):
        return []
    return [_normalize_video_item(v) for v in (raw.get("list", raw.get("videos", [])) or [])]


def _normalize_detail(raw, fallback_id):
    """归一化 detailContent 输出：{list:[detail]} -> {id,title,pic,desc,play_urls}。

    play_urls 格式：[{"name","url","flag"}]，保留 CMS 原始字段与嵌套线路。
    """
    raw = _as_mapping(raw)
    detail = {}
    if isinstance(raw, dict) and isinstance(raw.get("list"), list) and raw["list"]:
        detail = raw["list"][0]
    elif isinstance(raw, dict):
        detail = raw.get("video", raw.get("detail", raw))
    else:
        return {"id": fallback_id, "title": fallback_id, "pic": "", "desc": "", "play_urls": []}

    play_urls = []
    from_str = str(detail.get("vod_play_from", ""))
    url_str = str(detail.get("vod_play_url", ""))
    from_names = from_str.split("$$$")
    url_groups = url_str.split("$$$")
    for idx, url_group in enumerate(url_groups):
        flag = from_names[idx] if idx < len(from_names) else ""
        from_name = flag or f"线路{idx + 1}"
        for ep in url_group.split("#"):
            if not ep:
                continue
            parts = ep.split("$", 1)
            ep_url = parts[-1]
            if ep_url:
                ep_name = parts[0] if len(parts) >= 2 else ""
                play_urls.append({"name": f"{from_name}·{ep_name}" if ep_name else from_name, "url": ep_url, "flag": flag})

    if not play_urls:
        for line in detail.get("play_urls", detail.get("play_lines", [])) or []:
            if isinstance(line, dict) and line.get("url", line.get("link")):
                play_urls.append({
                    "name": line.get("name", line.get("title", "线路")),
                    "url": line.get("url", line.get("link")),
                    "flag": line.get("flag", ""),
                })

    result = {
        "id": str(detail.get("vod_id", detail.get("id", fallback_id))),
        "title": detail.get("vod_name", detail.get("title", fallback_id)),
        "pic": detail.get("vod_pic", detail.get("pic", "")),
        "desc": detail.get("vod_content", detail.get("desc", "")),
        "play_urls": play_urls,
    }
    for key in ("vod_play_from", "vod_play_url", "play_lines", "play_urls"):
        if key in detail:
            result[key] = detail[key]
    return result


def _normalize_play(raw):
    """归一化 playerContent 输出：{parse,url,header} -> {url,headers}。"""
    raw = _as_mapping(raw)
    if not isinstance(raw, dict):
        return {"url": str(raw) if raw else "", "headers": {}}
    result = {}
    url = raw.get("url", "")
    if url:
        result["url"] = str(url)
    header = raw.get("header")
    if isinstance(header, (dict, str)):
        result["headers"] = header
    elif isinstance(raw.get("headers"), (dict, str)):
        result["headers"] = raw["headers"]
    if "parse" in raw:
        try:
            result["parse"] = int(raw["parse"])
        except (TypeError, ValueError):
            pass
    return result


def _to_json(obj):
    """将 Python 对象转为 JSON 字符串。"""
    if obj is None:
        return "null"
    if isinstance(obj, str):
        try:
            parsed = json.loads(obj)
            if isinstance(parsed, (dict, list)):
                return obj
        except Exception:
            pass
        return json.dumps(obj, ensure_ascii=False)
    if isinstance(obj, (list, dict)):
        return json.dumps(obj, ensure_ascii=False, default=str)
    return json.dumps(str(obj), ensure_ascii=False)
