"""hipy/FongMi 源网络桥接基类。

通过 Chaquopy 的 JavaImporter 把 Java 层 Spider 类暴露给 Python。
py 源 `from base.spider import Spider` 即可获得带 fetch/post/postJson 的基类。
所有网络请求走 OkHttp 连接池，享受 HTTP/2 + 正常 TLS 栈。
"""

try:
    from com.legado.app.pybridge.PySpider import Spider as _JavaSpider

    _BRIDGE_AVAILABLE = True
except ImportError:
    _BRIDGE_AVAILABLE = False
    _JavaSpider = None


class Spider:
    """Python 包装层：桥接 Java Spider 的网络方法。

    Chaquopy 调用 Java 方法时仅支持位置参数，
    本包装层负责将 Python 风格的关键字参数映射到 Java 位置参数。
    """

    def __init__(self):
        if _BRIDGE_AVAILABLE and _JavaSpider is not None:
            self._java = _JavaSpider()
        else:
            self._java = None

    def fetch(self, url, params=None, headers=None):
        """GET 请求。params 和 headers 均为可选。"""
        if self._java is None:
            raise RuntimeError("PySpider bridge not available")
        return self._java.fetch(url, params or {}, headers or {})

    def post(self, url, data=None, headers=None):
        """POST 表单。data 和 headers 均为可选。"""
        if self._java is None:
            raise RuntimeError("PySpider bridge not available")
        return self._java.post(url, data or {}, headers or {})

    def postJson(self, url, json, headers=None):
        """POST JSON。"""
        if self._java is None:
            raise RuntimeError("PySpider bridge not available")
        return self._java.postJson(url, json, headers or {})

    def md5(self, text):
        if self._java is None:
            raise RuntimeError("PySpider bridge not available")
        return self._java.md5(text)

    def base64Encode(self, text):
        if self._java is None:
            raise RuntimeError("PySpider bridge not available")
        return self._java.base64Encode(text)

    def base64Decode(self, text):
        if self._java is None:
            raise RuntimeError("PySpider bridge not available")
        return self._java.base64Decode(text)

    def base64UrlEncode(self, text):
        if self._java is None:
            raise RuntimeError("PySpider bridge not available")
        return self._java.base64UrlEncode(text)

    def base64UrlDecode(self, text):
        if self._java is None:
            raise RuntimeError("PySpider bridge not available")
        return self._java.base64UrlDecode(text)

    def log(self, msg):
        if self._java is not None:
            self._java.log(msg)
        else:
            print("[Spider] " + str(msg))

    # 兼容 FongMi/TV Spider 接口：子类覆盖这些方法

    def homeContent(self, filter=""):
        return ""

    def homeVideoContent(self):
        return ""

    def categoryContent(self, tid, pg, filter="", extend=""):
        return ""

    def detailContent(self, ids):
        return ""

    def searchContent(self, key, quick=False, pg="1"):
        return ""

    def playerContent(self, flag, id, vipFlags=None):
        return ""

    def init(self, extend=""):
        pass
