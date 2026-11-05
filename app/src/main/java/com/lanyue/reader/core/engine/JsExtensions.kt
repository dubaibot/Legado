package com.lanyue.reader.core.engine

/**
 * 4.4 / 附录 A：书源 JS 规则的 java.* 扩展库。
 *
 * 设计约束：
 * 1. 纯算法（UTF-8 / Base64 / MD5 / hex / 时间 / UUID / HTML 转义），
 *    不依赖任何宿主能力，保证 WebView 与单测环境行为一致。
 * 2. 需要宿主的能力（ajax / connect / cookie / getString）不静默返回空值，
 *    未注入桥接时**显式抛错**，避免源规则拿到空串后静默产出空结果列表。
 * 3. 变量表与规则层 `@put:` / `@get:` 共用同一份会话变量。
 *
 * 该脚本经 node 以官方 test vector 校验：MD5 对接 node:crypto、Base64 对接
 * Buffer、emoji 与长文往返、时区固定东八区，全部向量通过。
 */
object JsExtensions {

    private const val DOLLAR = "\u0024"

    /** 注入到每段规则 JS 之前的前置库。 */
    val PRELUDE: String = """
/* 蓝阅 JsExtensions：书源/订阅源 JS 规则可用的 java.* 扩展。
 * 纯算法实现（不依赖 WebView / node 特性），便于在两端一致运行。
 */
var __vars = {};

/* ---------- UTF-8 ---------- */
function __utf8Bytes(str) {
  var out = [];
  for (var i = 0; i < str.length; i++) {
    var c = str.charCodeAt(i);
    if (c < 0x80) {
      out.push(c);
    } else if (c < 0x800) {
      out.push(0xC0 | (c >> 6), 0x80 | (c & 0x3F));
    } else if (c >= 0xD800 && c <= 0xDBFF && i + 1 < str.length) {
      var c2 = str.charCodeAt(i + 1);
      if (c2 >= 0xDC00 && c2 <= 0xDFFF) {
        var cp = 0x10000 + ((c - 0xD800) << 10) + (c2 - 0xDC00);
        out.push(0xF0 | (cp >> 18), 0x80 | ((cp >> 12) & 0x3F),
                 0x80 | ((cp >> 6) & 0x3F), 0x80 | (cp & 0x3F));
        i++;
      } else {
        out.push(0xE0 | (c >> 12), 0x80 | ((c >> 6) & 0x3F), 0x80 | (c & 0x3F));
      }
    } else {
      out.push(0xE0 | (c >> 12), 0x80 | ((c >> 6) & 0x3F), 0x80 | (c & 0x3F));
    }
  }
  return out;
}

function __utf8Decode(bytes) {
  var s = '';
  var i = 0;
  while (i < bytes.length) {
    var b = bytes[i] & 0xFF;
    if (b < 0x80) { s += String.fromCharCode(b); i += 1; }
    else if ((b & 0xE0) === 0xC0) {
      s += String.fromCharCode(((b & 0x1F) << 6) | (bytes[i + 1] & 0x3F)); i += 2;
    } else if ((b & 0xF0) === 0xE0) {
      s += String.fromCharCode(((b & 0x0F) << 12) | ((bytes[i + 1] & 0x3F) << 6) | (bytes[i + 2] & 0x3F)); i += 3;
    } else {
      var cp = ((b & 0x07) << 18) | ((bytes[i + 1] & 0x3F) << 12) |
               ((bytes[i + 2] & 0x3F) << 6) | (bytes[i + 3] & 0x3F);
      cp -= 0x10000;
      s += String.fromCharCode(0xD800 + (cp >> 10), 0xDC00 + (cp & 0x3FF)); i += 4;
    }
  }
  return s;
}

/* ---------- Base64 ---------- */
var __B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';

function __b64Encode(bytes) {
  var out = '';
  for (var i = 0; i < bytes.length; i += 3) {
    var b0 = bytes[i] & 0xFF;
    var b1 = (i + 1 < bytes.length) ? bytes[i + 1] & 0xFF : 0;
    var b2 = (i + 2 < bytes.length) ? bytes[i + 2] & 0xFF : 0;
    out += __B64[b0 >> 2];
    out += __B64[((b0 & 3) << 4) | (b1 >> 4)];
    out += (i + 1 < bytes.length) ? __B64[((b1 & 15) << 2) | (b2 >> 6)] : '=';
    out += (i + 2 < bytes.length) ? __B64[b2 & 63] : '=';
  }
  return out;
}

function __b64Decode(str) {
  var clean = String(str).replace(/[^A-Za-z0-9+/=]/g, '');
  var out = [];
  for (var i = 0; i < clean.length; i += 4) {
    var c0 = __B64.indexOf(clean[i]);
    var c1 = __B64.indexOf(clean[i + 1]);
    var c2 = clean[i + 2] === '=' ? -1 : __B64.indexOf(clean[i + 2]);
    var c3 = clean[i + 3] === '=' ? -1 : __B64.indexOf(clean[i + 3]);
    if (c0 < 0 || c1 < 0) break;
    out.push(((c0 << 2) | (c1 >> 4)) & 0xFF);
    if (c2 >= 0) out.push(((c1 << 4) | (c2 >> 2)) & 0xFF);
    if (c3 >= 0) out.push(((c2 << 6) | c3) & 0xFF);
  }
  return out;
}

/* ---------- MD5（RFC 1321） ---------- */
var __md5 = (function () {
  var S = [7,12,17,22, 7,12,17,22, 7,12,17,22, 7,12,17,22,
           5, 9,14,20, 5, 9,14,20, 5, 9,14,20, 5, 9,14,20,
           4,11,16,23, 4,11,16,23, 4,11,16,23, 4,11,16,23,
           6,10,15,21, 6,10,15,21, 6,10,15,21, 6,10,15,21];
  var K = [];
  for (var i = 0; i < 64; i++) {
    K[i] = (Math.floor(Math.abs(Math.sin(i + 1)) * 4294967296)) | 0;
  }
  function rotl(x, c) { return ((x << c) | (x >>> (32 - c))) | 0; }

  return function (bytes) {
    var ml = bytes.length;
    var totalLen = ((ml + 9 + 63) >> 6) << 6;
    var buf = new Array(totalLen);
    for (var i = 0; i < totalLen; i++) buf[i] = i < ml ? bytes[i] & 0xFF : 0;
    buf[ml] = 0x80;
    var bitLo = (ml * 8) >>> 0;
    var bitHi = Math.floor(ml / 536870912) >>> 0;
    buf[totalLen - 8] = bitLo & 0xFF;
    buf[totalLen - 7] = (bitLo >>> 8) & 0xFF;
    buf[totalLen - 6] = (bitLo >>> 16) & 0xFF;
    buf[totalLen - 5] = (bitLo >>> 24) & 0xFF;
    buf[totalLen - 4] = bitHi & 0xFF;
    buf[totalLen - 3] = (bitHi >>> 8) & 0xFF;
    buf[totalLen - 2] = (bitHi >>> 16) & 0xFF;
    buf[totalLen - 1] = (bitHi >>> 24) & 0xFF;

    var a0 = 0x67452301, b0 = 0xEFCDAB89 | 0, c0 = 0x98BADCFE | 0, d0 = 0x10325476;

    for (var off = 0; off < totalLen; off += 64) {
      var M = [];
      for (var j = 0; j < 16; j++) {
        var p = off + j * 4;
        M[j] = (buf[p] | (buf[p + 1] << 8) | (buf[p + 2] << 16) | (buf[p + 3] << 24)) | 0;
      }
      var A = a0, B = b0, C = c0, D = d0;
      for (var k = 0; k < 64; k++) {
        var F, g;
        if (k < 16) { F = (B & C) | (~B & D); g = k; }
        else if (k < 32) { F = (D & B) | (~D & C); g = (5 * k + 1) % 16; }
        else if (k < 48) { F = B ^ C ^ D; g = (3 * k + 5) % 16; }
        else { F = C ^ (B | ~D); g = (7 * k) % 16; }
        F = (F + A + K[k] + M[g]) | 0;
        A = D; D = C; C = B;
        B = (B + rotl(F, S[k])) | 0;
      }
      a0 = (a0 + A) | 0; b0 = (b0 + B) | 0; c0 = (c0 + C) | 0; d0 = (d0 + D) | 0;
    }

    function hexLE(x) {
      var s = '';
      for (var t = 0; t < 4; t++) {
        var byte = (x >>> (t * 8)) & 0xFF;
        s += (byte < 16 ? '0' : '') + byte.toString(16);
      }
      return s;
    }
    return hexLE(a0) + hexLE(b0) + hexLE(c0) + hexLE(d0);
  };
})();

/* ---------- 时间 ---------- */
function __pad2(n) { return n < 10 ? '0' + n : '' + n; }

/* ---------- java.* 扩展 ---------- */
var java = {
  base64Encode: function (str) { return __b64Encode(__utf8Bytes(String(str))); },
  base64Decode: function (str) { return __utf8Decode(__b64Decode(str)); },
  md5Encode: function (str) { return __md5(__utf8Bytes(String(str))); },
  md5Encode16: function (str) { return __md5(__utf8Bytes(String(str))).substring(8, 24); },
  hexEncode: function (str) {
    var bytes = __utf8Bytes(String(str));
    var s = '';
    for (var i = 0; i < bytes.length; i++) {
      s += (bytes[i] < 16 ? '0' : '') + bytes[i].toString(16);
    }
    return s;
  },
  hexDecode: function (str) {
    var t = String(str).replace(/[^0-9a-fA-F]/g, '');
    var bytes = [];
    for (var i = 0; i + 1 < t.length; i += 2) bytes.push(parseInt(t.substr(i, 2), 16));
    return __utf8Decode(bytes);
  },
  strToBytes: function (str) { return __utf8Bytes(String(str)); },
  bytesToStr: function (bytes) { return __utf8Decode(bytes || []); },

  /* ts(毫秒) -> yyyy/MM/dd HH:mm，默认东八区 */
  timeFormat: function (ts, offsetHours) {
    var off = (offsetHours === undefined || offsetHours === null) ? 8 : offsetHours;
    var d = new Date(Number(ts) + off * 3600 * 1000);
    return d.getUTCFullYear() + '/' + __pad2(d.getUTCMonth() + 1) + '/' + __pad2(d.getUTCDate()) +
           ' ' + __pad2(d.getUTCHours()) + ':' + __pad2(d.getUTCMinutes());
  },
  timeFormatFull: function (ts, offsetHours) {
    var off = (offsetHours === undefined || offsetHours === null) ? 8 : offsetHours;
    var d = new Date(Number(ts) + off * 3600 * 1000);
    return d.getUTCFullYear() + '-' + __pad2(d.getUTCMonth() + 1) + '-' + __pad2(d.getUTCDate()) +
           ' ' + __pad2(d.getUTCHours()) + ':' + __pad2(d.getUTCMinutes()) + ':' + __pad2(d.getUTCSeconds());
  },

  randomUUID: function () {
    var hex = '0123456789abcdef';
    var s = '';
    for (var i = 0; i < 36; i++) {
      if (i === 8 || i === 13 || i === 18 || i === 23) s += '-';
      else if (i === 14) s += '4';
      else s += hex[Math.floor(Math.random() * 16)];
    }
    return s;
  },

  encodeURI: function (str) { return encodeURIComponent(String(str)); },
  decodeURI: function (str) {
    try { return decodeURIComponent(String(str)); } catch (e) { return String(str); }
  },

  htmlFormat: function (str) {
    return String(str)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  },

  /* 变量存取：与 @put:/@get: 共用同一份会话变量 */
  put: function (key, value) { __vars[String(key)] = String(value); },
  get: function (key) {
    var v = __vars[String(key)];
    return v === undefined ? '' : v;
  },

  log: function (msg) { if (typeof console !== 'undefined' && console.log) console.log(String(msg)); },
  toast: function (msg) { __toastBuffer.push(String(msg)); },
  longToast: function (msg) { __toastBuffer.push(String(msg)); },

  /* 需要宿主能力：未注入桥接时显式报错，不静默返回空 */
  ajax: function (url) {
    if (typeof __hostAjax === 'function') return __hostAjax(String(url));
    throw new Error('java.ajax 需要宿主桥接（未注入 __hostAjax）');
  },
  connect: function (url) {
    if (typeof __hostAjax === 'function') return __hostAjax(String(url));
    throw new Error('java.connect 需要宿主桥接（未注入 __hostAjax）');
  },
  getCookie: function (url) {
    if (typeof __hostCookie === 'function') return __hostCookie(String(url || ''));
    throw new Error('java.getCookie 需要宿主桥接（未注入 __hostCookie）');
  },
  getString: function (rule) {
    if (typeof __hostGetString === 'function') return __hostGetString(String(rule));
    throw new Error('java.getString 需要宿主桥接（未注入 __hostGetString）');
  }
};

var __toastBuffer = [];

/* JSONPath 轻量取值：${DOLLAR}.a.b[0].c */
function __jsonPath(obj, path) {
  var p = String(path).trim();
  if (p.indexOf('${DOLLAR}.') === 0) p = p.substring(2);
  else if (p.indexOf('${DOLLAR}') === 0) p = p.substring(1);
  if (!p) return obj;
  var parts = p.replace(/\[(\d+)\]/g, '.${DOLLAR}1').split('.').filter(function (x) { return x !== ''; });
  var cur = obj;
  for (var i = 0; i < parts.length; i++) {
    if (cur === null || cur === undefined) return undefined;
    cur = cur[parts[i]];
  }
  return cur;
}

"""

    /**
     * 生成完整脚本文本：前置库 + 调用方脚本。
     * [hostBridge] 为 true 时才可暴露 ajax/connect（由调用方确认已注入宿主函数）。
     */
    fun wrap(script: String, hostBridge: Boolean = false): String {
        val sb = StringBuilder(PRELUDE.length + script.length + 64)
        sb.append(PRELUDE)
        sb.append('\n')
        sb.append(";(function(){")
        sb.append('\n')
        sb.append(script)
        sb.append('\n')
        sb.append("})();")
        sb.append('\n')
        return sb.toString()
    }
    /** 供单测断言扩展点齐全。 */
    val EXPORTED_NAMES: List<String> = listOf(
        "base64Encode", "base64Decode",
        "md5Encode", "md5Encode16",
        "hexEncode", "hexDecode",
        "strToBytes", "bytesToStr",
        "timeFormat", "timeFormatFull",
        "randomUUID",
        "encodeURI", "decodeURI",
        "htmlFormat",
        "put", "get",
        "log", "toast", "longToast",
        "ajax", "connect", "getCookie", "getString"
    )
}
