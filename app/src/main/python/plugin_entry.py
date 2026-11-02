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


def _to_json(obj):
    """将 Python 对象转为 JSON 字符串。"""
    if obj is None:
        return "null"
    if isinstance(obj, str):
        return json.dumps(obj, ensure_ascii=False)
    if isinstance(obj, (list, dict)):
        return json.dumps(obj, ensure_ascii=False, default=str)
    return json.dumps(str(obj), ensure_ascii=False)
