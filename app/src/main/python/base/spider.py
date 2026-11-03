"""hipy/FongMi 源网络桥接基类。

通过 Chaquopy 的 JavaImporter 把 Java 层 Spider 类暴露给 Python。
py 源 `from base.spider import Spider` 即可获得带 fetch/post/postJson 的基类。
所有网络请求走 OkHttp 连接池，享受 HTTP/2 + 正常 TLS 栈。
"""

try:
    from com.legado.app.pybridge.PySpider import Spider
except ImportError:
    # 桥接不可用时提供空壳，py 源仍能加载但不可用 fetch
    class Spider:
        def fetch(self, url, params=None, headers=None):
            raise RuntimeError("PySpider bridge not available")

        def post(self, url, data=None, headers=None):
            raise RuntimeError("PySpider bridge not available")

        def postJson(self, url, json, headers=None):
            raise RuntimeError("PySpider bridge not available")

        def md5(self, text):
            raise RuntimeError("PySpider bridge not available")

        def base64Encode(self, text):
            raise RuntimeError("PySpider bridge not available")

        def base64Decode(self, text):
            raise RuntimeError("PySpider bridge not available")
