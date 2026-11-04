import re
import json
import base64
from urllib.parse import unquote, quote, urljoin

try:
    from base.spider import Spider
except ImportError:
    class Spider:
        pass


class Spider(Spider):
    def getName(self):
        return "独播库"

    def init(self, extend=""):
        self.host = "https://www.dbku.tv"
        self.session = None
        self.categories = [
            {"type_id": "1", "type_name": "电影"},
            {"type_id": "2", "type_name": "连续剧"},
            {"type_id": "3", "type_name": "综艺"},
            {"type_id": "4", "type_name": "动漫"},
            {"type_id": "13", "type_name": "陆剧"},
            {"type_id": "15", "type_name": "日韩剧"},
            {"type_id": "21", "type_name": "短剧"},
            {"type_id": "14", "type_name": "台泰剧"},
        ]
        self.headers = {
            "User-Agent": "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Mobile Safari/537.36",
            "Referer": self.host,
        }

    def _get_session(self):
        if self.session is None:
            import requests
            self.session = requests.Session()
            self.session.headers.update(self.headers)
        return self.session

    def _fetch(self, url):
        session = self._get_session()
        try:
            resp = session.get(url, timeout=8, proxies=session.proxies or None)
            resp.encoding = "utf-8"
            return resp.text
        except Exception as e:
            print(f"[_fetch error] {url}: {e}")
            return ""

    def _parse_pagecount(self, html, current_pg):
        m = re.search(r'class="visible-xs"><a class="btn btn-warm">(\d+)/(\d+)</a>', html)
        if m:
            return int(m.group(2))
        if re.search(r'下一页', html):
            return current_pg + 1
        return current_pg

    def _parse_list(self, html):
        items = []
        seen = set()
        pattern = r'<a class="myui-vodlist__thumb[^"]*" href="(/voddetail/\d+\.html)" title="([^"]*)"[^>]*data-original="([^"]*)"'
        for href, title, cover in re.findall(pattern, html):
            m = re.search(r'/voddetail/(\d+)', href)
            if not m:
                continue
            vod_id = m.group(1)
            if vod_id in seen:
                continue
            seen.add(vod_id)
            items.append({
                "vod_id": vod_id,
                "vod_name": title,
                "vod_pic": cover if cover.startswith("http") else urljoin(self.host, cover),
                "vod_remarks": "",
            })
        return items

    def _parse_detail(self, html, vod_id):
        title = re.search(r'<h1 class="title">([^<]+)</h1>', html)
        title = title.group(1) if title else ""
        cover = re.search(r'data-original="([^"]+)"', html)
        cover = cover.group(1) if cover else ""
        desc = re.search(r'<span class="sketch content">([^<]+)</span>', html)
        if not desc:
            desc = re.search(r'<p class="data hidden-xs">.*?<span class="sketch content">([^<]+)</span>', html)
        desc = desc.group(1) if desc else ""
        actor = re.search(r'主演：</span>(.*?)</p>', html)
        actor = re.sub(r'<[^>]+>', '', actor.group(1)).replace('&nbsp;', ' ').strip() if actor else ""
        director = re.search(r'导演：</span>(.*?)</p>', html)
        director = re.sub(r'<[^>]+>', '', director.group(1)).replace('&nbsp;', ' ').strip() if director else ""
        area = re.search(r'地区：</span><a href="[^"]+">([^<]+)</a>', html)
        area = area.group(1) if area else ""
        year = re.search(r'年份：</span><a href="[^"]+">([^<]+)</a>', html)
        year = year.group(1) if year else ""
        update = re.search(r'更新：</span><span class="text-red">([^<]+)</span>', html)
        update = update.group(1) if update else ""

        play_from = []
        play_url = []
        playlist_blocks = re.findall(r'<div id="playlist(\d+)" class="tab-pane[^>]*>.*?<ul class="myui-content__list[^>]*>(.*?)</ul>', html, re.DOTALL)
        if not playlist_blocks:
            blocks = re.findall(r'<ul class="myui-content__list[^>]*>(.*?)</ul>', html, re.DOTALL)
            playlist_blocks = [("1", b) for b in blocks]

        for idx, (pid, block) in enumerate(playlist_blocks):
            eps = re.findall(r'href="(/vodplay/\d+-\d+-\d+\.html)"[^>]*>([^<]+)</a>', block)
            if eps:
                urls = []
                for ep_href, ep_name in eps:
                    urls.append(f"{ep_name}${urljoin(self.host, ep_href)}")
                play_from.append(f"线路{idx+1}")
                play_url.append("#".join(urls))

        return {
            "vod_id": vod_id,
            "vod_name": title,
            "vod_pic": cover if cover.startswith("http") else urljoin(self.host, cover),
            "vod_actor": actor,
            "vod_director": director,
            "vod_area": area,
            "vod_year": year,
            "vod_remarks": update,
            "vod_content": desc,
            "vod_play_from": "$$$".join(play_from),
            "vod_play_url": "$$$".join(play_url),
        }

    def _decrypt_player_url(self, encrypted_url):
        try:
            decoded = base64.b64decode(encrypted_url).decode("utf-8")
            return unquote(decoded)
        except Exception:
            return encrypted_url

    def _parse_player(self, html):
        idx = html.find('var player_data = ')
        if idx < 0:
            return None
        json_start = idx + len('var player_data = ')
        brace_count = 0
        in_string = False
        escape = False
        json_end = -1
        for i, ch in enumerate(html[json_start:]):
            if escape:
                escape = False
                continue
            if ch == '\\':
                escape = True
                continue
            if ch == '"' and not escape:
                in_string = not in_string
                continue
            if not in_string:
                if ch == '{':
                    brace_count += 1
                elif ch == '}':
                    brace_count -= 1
                    if brace_count == 0:
                        json_end = json_start + i + 1
                        break
        if json_end < 0:
            return None
        try:
            player_data = json.loads(html[json_start:json_end])
        except Exception:
            return None
        encrypted_url = player_data.get("url", "")
        if not encrypted_url:
            return None
        return self._decrypt_player_url(encrypted_url) if player_data.get("encrypt") == 2 else unquote(encrypted_url)

    def homeContent(self, filter):
        return {"class": self.categories, "list": [], "filters": {}}

    def homeVideoContent(self):
        html = self._fetch(self.host)
        return {"list": self._parse_list(html)[:20]}

    def categoryContent(self, tid, pg, filter, extend):
        pg = int(pg or 1)
        url = f"{self.host}/vodtype/{tid}.html" if pg == 1 else f"{self.host}/vodshow/{tid}--------{pg}---.html"
        html = self._fetch(url)
        items = self._parse_list(html)
        pagecount = self._parse_pagecount(html, pg)
        limit = len(items) or 20
        return {
            "page": pg,
            "pagecount": pagecount,
            "limit": limit,
            "total": limit * pagecount,
            "list": items,
        }

    def detailContent(self, ids):
        vod_id = ids[0] if isinstance(ids, (list, tuple)) else str(ids)
        html = self._fetch(f"{self.host}/voddetail/{vod_id}.html")
        return {"list": [self._parse_detail(html, vod_id)]}

    def searchContent(self, key, quick, pg="1"):
        pg = int(pg or 1)
        url = f"{self.host}/vodsearch/-------------.html?wd={quote(key)}" if pg == 1 else f"{self.host}/vodsearch/-------------{pg}.html?wd={quote(key)}"
        html = self._fetch(url)
        items = []
        seen = set()
        for href, title, cover in re.findall(r'<a class="myui-vodlist__thumb[^"]*" href="(/voddetail/\d+\.html)" title="([^"]*)"[^>]*data-original="([^"]*)"', html):
            m = re.search(r'/voddetail/(\d+)', href)
            if not m or m.group(1) in seen:
                continue
            seen.add(m.group(1))
            items.append({
                "vod_id": m.group(1),
                "vod_name": title,
                "vod_pic": cover if cover.startswith("http") else urljoin(self.host, cover),
                "vod_remarks": "",
            })
        return {"list": items, "page": pg, "pagecount": 1}

    def playerContent(self, flag, id, vipFlags):
        html = self._fetch(id)
        real_url = self._parse_player(html)
        if not real_url:
            return {"parse": 0, "url": "", "header": {"User-Agent": self.headers["User-Agent"], "Referer": self.host}}
        return {"parse": 0, "url": real_url, "header": {"User-Agent": self.headers["User-Agent"], "Referer": self.host}}

    def localProxy(self, param):
        return [404, "text/plain", b"Not Found", {}]


# ===== 模块级包装函数（App HomeBridge 调用入口） =====

_spider = None


def _get_spider():
    global _spider
    if _spider is None:
        _spider = Spider()
        _spider.init("")
    return _spider


def search(keyword):
    """App 搜索入口：返回 [{"id","title","url","pic"}]"""
    s = _get_spider()
    try:
        result = s.searchContent(keyword, False, "1")
    except Exception as e:
        print(f"[search error] keyword={keyword}: {e}")
        return []
    items = []
    for item in result.get("list", []):
        vid = item.get("vod_id", "")
        items.append({
            "id": vid,
            "title": item.get("vod_name", ""),
            "url": f"https://www.dbku.tv/voddetail/{vid}.html",
            "pic": item.get("vod_pic", ""),
        })
    return items


def detail(id):
    """App 详情入口：返回 {"title","pic","desc","play_urls":[{"name","url"}]}"""
    s = _get_spider()
    try:
        result = s.detailContent([id])
    except Exception as e:
        print(f"[detail error] id={id}: {e}")
        return {}
    lst = result.get("list", [])
    if not lst:
        return {}
    d = lst[0]
    play_urls = []
    froms = d.get("vod_play_from", "").split("$$$")
    urls = d.get("vod_play_url", "").split("$$$")
    for i, fname in enumerate(froms):
        eps = urls[i].split("#") if i < len(urls) else []
        for ep in eps:
            parts = ep.split("$", 1)
            if len(parts) == 2 and parts[1]:
                ep_name = parts[0].strip()
                ep_url = parts[1].strip()
                play_urls.append({"name": f"{fname}-{ep_name}", "url": ep_url})
    return {
        "title": d.get("vod_name", ""),
        "pic": d.get("vod_pic", ""),
        "desc": d.get("vod_content", ""),
        "play_urls": play_urls,
    }


def play(url):
    """App 播放入口：传入播放页 URL，返回 {"url": 真实m3u8, "headers": {...}}"""
    s = _get_spider()
    try:
        result = s.playerContent("", url, [])
    except Exception as e:
        print(f"[play error] url={url}: {e}")
        return {"url": "", "headers": {}}
    return {
        "url": result.get("url", ""),
        "headers": result.get("header", {}),
    }


def home():
    """App 首页入口：返回分类列表（不依赖网络，确保快速加载）
    推荐视频尝试加载，网络不通时返回空列表，分类标签栏始终可用"""
    s = _get_spider()
    hc = s.homeContent("")
    categories = [{"id": c["type_id"], "name": c["type_name"]} for c in hc.get("class", [])]
    videos = []
    try:
        hv = s.homeVideoContent()
        videos = [_to_video(v) for v in hv.get("list", [])]
    except Exception as e:
        print(f"[home video error] {e}")
        videos = []
    return {"categories": categories, "videos": videos}


def category(tid, pg=1):
    """App 分类入口：返回 {"page":pg, "pagecount":pc, "videos":[{"id","title","pic","remarks"}]}"""
    s = _get_spider()
    try:
        cc = s.categoryContent(tid, pg, "", "")
    except Exception as e:
        print(f"[category error] tid={tid} pg={pg}: {e}")
        return {"page": pg, "pagecount": 1, "videos": []}
    videos = [_to_video(v) for v in cc.get("list", [])]
    return {"page": cc.get("page", pg), "pagecount": cc.get("pagecount", 1), "videos": videos}


def _to_video(v):
    return {
        "id": v.get("vod_id", ""),
        "title": v.get("vod_name", ""),
        "pic": v.get("vod_pic", ""),
        "remarks": v.get("vod_remarks", ""),
    }

