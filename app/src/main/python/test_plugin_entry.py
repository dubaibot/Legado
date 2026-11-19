import json
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import plugin_entry


class StandardSpider:
    def init(self, extend=""):
        self.initialized = True

    def homeContent(self, filter=""):
        return json.dumps({
            "class": [{"type_id": "1", "type_name": "电影"}],
            "list": [{"vod_id": "a", "vod_name": "A", "vod_pic": "p"}],
        })

    def categoryContent(self, tid, pg, filter="", extend=""):
        return {"page": pg, "pagecount": 3, "list": [{"vod_id": "b", "vod_name": "B"}]}

    def searchContent(self, key, quick=False, pg="1"):
        return [{"id": key, "title": "Search", "url": "u"}]

    def detailContent(self, ids):
        return {"list": [{"vod_id": ids[0], "vod_name": "Detail", "play_urls": [{"name": "Line", "url": "u"}]}]}

    def playerContent(self, flag, id, vipFlags=None):
        return json.dumps({"url": id, "header": {"Referer": "r"}, "parse": 0})


class PluginEntryTest(unittest.TestCase):
    def test_string_play_headers_survive_normalization(self):
        for header in ('{"Referer":"https://example.org"}', 'Referer=https://example.org&Cookie=a=b'):
            self.assertEqual(plugin_entry._normalize_play({"url": "u", "header": header})["headers"], header)

    def test_nested_play_urls_survive_normalization(self):
        lines = [{"name": "Route", "flag": "a", "episodes": [{"title": "One", "url": "id1"}]}]
        self.assertEqual(plugin_entry._normalize_detail({"play_urls": lines}, "v")["play_urls"], lines)

    def test_module_json_string_is_not_double_encoded(self):
        module = type("Module", (), {"search": lambda keyword: json.dumps([{"id": keyword}])})
        self.assertEqual(plugin_entry.call_func_json(module, "search", ["key"]), '[{"id": "key"}]')

    def test_spider_protocol_normalization(self):
        module = type("Module", (), {"Spider": StandardSpider})
        self.assertEqual(plugin_entry.call_spider_func(module, "home", []), json.dumps({
            "categories": [{"id": "1", "name": "电影"}],
            "videos": [{"id": "a", "title": "A", "url": "a", "pic": "p", "remarks": ""}],
        }, ensure_ascii=False))
        category = json.loads(plugin_entry.call_spider_func(module, "category", ["1", "2"]))
        self.assertEqual(category["page"], 2)
        self.assertEqual(category["videos"][0]["url"], "b")
        self.assertEqual(json.loads(plugin_entry.call_spider_func(module, "search", ["key"]))[0]["id"], "key")
        detail = json.loads(plugin_entry.call_spider_func(module, "detail", ["a"]))
        self.assertEqual(detail["play_urls"][0]["url"], "u")
        play = json.loads(plugin_entry.call_spider_func(module, "play", ["u"]))
        self.assertEqual(play["headers"]["Referer"], "r")
        self.assertEqual(play["parse"], 0)

    def test_home_requests_filters_and_preserves_them_with_recommendations(self):
        filters = {"1": [{"key": "year", "value": [{"n": "2026", "v": "2026"}]}]}

        class Spider:
            def homeContent(self, filter):
                self.filter = filter
                return {"class": [{"type_id": "1", "type_name": "Movies"}], "filters": filters}

            def homeVideoContent(self):
                return {"list": [{"vod_id": "recommended"}] if self.filter is True else []}

        result = json.loads(plugin_entry.call_spider_func(type("Module", (), {"Spider": Spider}), "home", []))
        self.assertEqual(result["filters"], filters)
        self.assertEqual(result["categories"], [{"id": "1", "name": "Movies"}])
        self.assertEqual(result["videos"][0]["id"], "recommended")

    def test_home_no_argument_signature(self):
        class Spider:
            def homeContent(self):
                return {"list": [{"vod_id": "home"}]}

        result = json.loads(plugin_entry.call_spider_func(type("Module", (), {"Spider": Spider}), "home", []))
        self.assertEqual(result["videos"][0]["id"], "home")
        self.assertNotIn("filters", result)

    def test_home_failure_still_loads_recommendations(self):
        class Spider:
            def homeContent(self, filter):
                raise RuntimeError("home unavailable")

            def homeVideoContent(self):
                return {"list": [{"vod_id": "recommended"}]}

        result = json.loads(plugin_entry.call_spider_func(type("Module", (), {"Spider": Spider}), "home", []))
        self.assertEqual(result["categories"], [])
        self.assertEqual(result["videos"][0]["id"], "recommended")

    def test_home_filters_key_presence(self):
        for filters in ({}, None):
            with self.subTest(filters=filters):
                self.assertEqual(plugin_entry._normalize_home({"filters": filters})["filters"], filters)
        self.assertNotIn("filters", plugin_entry._normalize_home({}))

    def test_category_decodes_extend_and_requests_filters(self):
        calls = []

        class Spider:
            def categoryContent(self, tid, pg, filter, extend):
                calls.append((tid, pg, filter, extend))
                return {"page": pg, "list": [{"vod_id": extend.get("year", "all")}]}

        for value in ({"year": "2026"}, '{"year": "2026"}'):
            with self.subTest(value=value):
                result = json.loads(plugin_entry.call_spider_func(
                    type("Module", (), {"Spider": Spider}), "category", [1, 2, value]))
                self.assertEqual(calls[-1], ("1", "2", True, {"year": "2026"}))
                self.assertIs(calls[-1][2], True)
                self.assertEqual(result["videos"][0]["id"], "2026")
        for args in ([1, 2], [1, 2, "invalid"], [1, 2, "[]"], [1, 2, None]):
            with self.subTest(args=args):
                plugin_entry.call_spider_func(type("Module", (), {"Spider": Spider}), "category", args)
                self.assertEqual(calls[-1][3], {})

    def test_category_supports_three_and_two_argument_spiders(self):
        class ThreeArgumentSpider:
            def categoryContent(self, tid, pg, extend):
                return {"list": [{"vod_id": extend["year"]}]}

        class TwoArgumentSpider:
            def categoryContent(self, tid, pg):
                return {"page": pg, "list": [{"vod_id": tid}]}

        for spider, expected_id in ((ThreeArgumentSpider, "2026"), (TwoArgumentSpider, "1")):
            with self.subTest(spider=spider):
                result = json.loads(plugin_entry.call_spider_func(
                    type("Module", (), {"Spider": spider}), "category", ["1", "2", '{"year": "2026"}']))
                self.assertEqual(result["videos"][0]["id"], expected_id)

    def test_module_fallback_tries_two_arguments_before_one(self):
        calls = []

        def category(*args):
            calls.append(args)
            if len(args) == 3:
                raise TypeError("two arguments required")
            return list(args)

        module = type("Module", (), {"category": category})
        for bridge in (plugin_entry.call_func, plugin_entry.call_func_json):
            with self.subTest(bridge=bridge.__name__):
                calls.clear()
                result = bridge(module, "category", ["1", "2", "{}"])
                if isinstance(result, tuple):
                    result, error = result
                    self.assertIsNone(error)
                self.assertEqual(json.loads(result), ["1", "2"])
                self.assertEqual([len(args) for args in calls], [3, 2])

    def test_module_single_argument_fallback(self):
        module = type("Module", (), {"play": lambda url: {"url": url}})
        for bridge in (plugin_entry.call_func, plugin_entry.call_func_json):
            for args in (["u", "flag"], ["u", "flag", "extra"]):
                with self.subTest(bridge=bridge.__name__, args=args):
                    result = bridge(module, "play", args)
                    if isinstance(result, tuple):
                        result, error = result
                        self.assertIsNone(error)
                    self.assertEqual(json.loads(result), {"url": "u"})

    def test_cms_detail_preserves_empty_group_positions_and_dollar_urls(self):
        detail = {
            "vod_play_from": "first$$$$$$third",
            "vod_play_url": "E1$https://example/one$token$$$$$$E3$https://example/three$$$E4$u4",
        }
        result = plugin_entry._normalize_detail({"list": [detail]}, "id")
        self.assertEqual(result["vod_play_from"], detail["vod_play_from"])
        self.assertEqual(result["vod_play_url"], detail["vod_play_url"])
        self.assertEqual(result["play_urls"], [
            {"name": "first·E1", "url": "https://example/one$token", "flag": "first"},
            {"name": "third·E3", "url": "https://example/three", "flag": "third"},
            {"name": "线路4·E4", "url": "u4", "flag": ""},
        ])

    def test_cms_detail_defaults_missing_line_names(self):
        for from_fields in ({}, {"vod_play_from": "$$$named"}):
            with self.subTest(from_fields=from_fields):
                result = plugin_entry._normalize_detail(
                    dict(from_fields, vod_play_url="u1$$$E2$u2"), "id")
                self.assertEqual(result["play_urls"][0], {"name": "线路1", "url": "u1", "flag": ""})
                self.assertEqual(result["play_urls"][1]["flag"], from_fields.get("vod_play_from", "")[3:])

    def test_detail_preserves_nested_play_lines_and_flat_flags(self):
        lines = [{"name": "route", "flag": "route-id", "episodes": [{"name": "E1", "url": "u", "extra": 1}]}]
        result = plugin_entry._normalize_detail({"play_lines": lines}, "id")
        self.assertIs(result["play_lines"], lines)
        result = plugin_entry._normalize_detail({"play_lines": lines, "vod_play_url": "E1$u"}, "id")
        self.assertIs(result["play_lines"], lines)
        result = plugin_entry._normalize_detail({"play_urls": [{"name": "E1", "url": "u", "flag": "route-id"}]}, "id")
        self.assertEqual(result["play_urls"][0]["flag"], "route-id")

    def test_play_passes_route_flag_across_signatures(self):
        class ThreeArgumentSpider:
            def playerContent(self, flag, url, vip_flags):
                return {"url": url, "header": {"Route": flag, "Vip": str(vip_flags)}}

        class TwoArgumentSpider:
            def playerContent(self, flag, url):
                return {"url": url, "header": {"Route": flag}}

        for spider in (ThreeArgumentSpider, TwoArgumentSpider):
            for args, flag in ((["u", "route-id"], "route-id"), (["u"], "")):
                with self.subTest(spider=spider, args=args):
                    result = json.loads(plugin_entry.call_spider_func(type("Module", (), {"Spider": spider}), "play", args))
                    self.assertEqual(result["url"], "u")
                    self.assertEqual(result["headers"]["Route"], flag)


if __name__ == "__main__":
    unittest.main()
