"""插件桥接模块：提供统一的函数调用接口、能力检测和异常兜底。

call_func 封装插件函数调用，处理参数适配和异常，返回 JSON 字符串。
detect_capabilities 扫描插件模块，返回支持的函数签名列表。
"""

import inspect
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
        # 参数不匹配：尝试用签名信息做适配
        try:
            sig = inspect.signature(func)
            param_names = list(sig.parameters.keys())
            if len(param_names) == 1 and len(args) >= 1:
                result = func(args[0])
                return _to_json(result), None
        except Exception:
            pass
        return None, "TypeError: " + str(e)
    except Exception as e:
        return None, str(e)


def detect_capabilities(module):
    """检测插件模块的能力。

    Returns:
        JSON 字符串: {函数名: [参数名列表]}，仅包含模块中实际定义的函数
    """
    funcs = {}
    for name in ("search", "detail", "play", "category", "enabled"):
        func = getattr(module, name, None)
        if func is not None and callable(func):
            try:
                sig = inspect.signature(func)
                funcs[name] = list(sig.parameters.keys())
            except (ValueError, TypeError):
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
