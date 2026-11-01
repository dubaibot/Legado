# Chaquopy 冒烟测试模块（构建时打包，非用户插件）
# 用于验证 Python 引擎在应用内可正常启动、导入、调用与异常传播


def smoke_test(device_name: str) -> str:
    """基础函数：字符串拼接，验证 Java->Python 传参与返回。"""
    return f"smoke ok on {device_name}"


def boom() -> str:
    """异常路径：验证 Python 异常能传播到 Java 侧被捕获。"""
    raise ValueError("smoke boom")
