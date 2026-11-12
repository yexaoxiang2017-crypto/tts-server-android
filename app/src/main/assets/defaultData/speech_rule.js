/*
 * 朗读规则：旁白/对话分角色 + 文本清洗（openclaw 版）
 * App 用 Rhino 执行，规则对象固定叫 SpeechRuleJS，不能用 lookbehind 等新语法。
 *   handleText(text, tagsData) -> [{text, tag}]   tag 对应 TTS 配置里的「标签」(narration/dialogue)
 *   splitText(text)            -> [句子]          可选，用于把每段再切句
 */
var SpeechRuleJS = {
    name: "旁白/对话 + 文本清洗",
    id: "openclaw.multi_voice_clean",
    author: "openclaw",
    version: 1,
    tags: { narration: "旁白", dialogue: "对话" },

    handleText: function (text, tagsData) {
        return tagByRole(cleanText(text));
    },

    splitText: function (text) {
        return splitSentences(text);
    }
};

/* ---------------- 清洗 ---------------- */
var AD_PATTERNS = [
    /<[^>]{1,200}>/g,
    /https?:\/\/\S+/gi,
    /www\.[a-z0-9.\-_]+/gi,
    /\b[a-z0-9][a-z0-9\-]{1,30}\.(com|net|cn|org|cc|la|me|info|top|xyz)\b/gi,
    /求(月票|推荐票|收藏|订阅|打赏|评价票|鲜花|票票)/g,
    /(本章未完|未完待续|点击下一页[^。]{0,8}|下一页继续|手机版阅读网址|手机阅读|安卓客户端|本章完|全文完)/g
];

function cleanText(text) {
    if (!text) return "";
    var s = String(text);

    s = s.replace(/&nbsp;/gi, " ").replace(/&amp;/gi, "&").replace(/&quot;/gi, '"');

    for (var i = 0; i < AD_PATTERNS.length; i++) {
        s = s.replace(AD_PATTERNS[i], " ");
    }

    s = s.replace(/\.{3,}/g, "……");
    s = s.replace(/(。\s*){2,}/g, "。");
    s = s.replace(/[—\-－]{2,}/g, "——");

    // 删掉被清洗后留下的孤立标点
    s = s.replace(/\s+([，。！？；：、…—])/g, "$1");
    s = s.replace(/([，、；：])\s*[，、；：]+/g, "$1");
    s = s.replace(/([，、；：])([。！？])/g, "$2");
    s = s.replace(/([。！？])[。！？…]+/g, "$1");
    s = s.replace(/([，、；：。！？])\s+([，、；：。！？])/g, "$2");
    s = s.replace(/^[\s，、；：。！？…—]+/g, "");

    s = s.replace(/[ \t\u00A0\u3000]+/g, " ");
    s = s.replace(/\r/g, "\n").replace(/\n{2,}/g, "\n");
    return s;
}

/* ---------------- 旁白/对话 ---------------- */
var OPEN_Q = "“「『";
var CLOSE_Q = "”」』";

function hasWord(s) { return /[\u4e00-\u9fa5A-Za-z0-9]/.test(s); }

function trimEdge(s) {
    s = String(s).replace(/\s+/g, " ").trim();
    var changed = true;
    while (changed && s.length > 0) {
        changed = false;
        var head = s.charAt(0);
        if ("，。、；：!?！？…—\"'“”‘’".indexOf(head) >= 0) { s = s.substring(1); changed = true; continue; }
        var tail = s.charAt(s.length - 1);
        if ("，、；：…—\"'“”‘’".indexOf(tail) >= 0) { s = s.substring(0, s.length - 1); changed = true; continue; }
    }
    return s;
}

function tagByRole(text) {
    var out = [];
    var buf = "";
    var isDialogue = false;

    function flush() {
        var s = trimEdge(buf);
        buf = "";
        if (s.length === 0 || !hasWord(s)) return;
        out.push({ text: s, tag: isDialogue ? "dialogue" : "narration" });
    }

    for (var i = 0; i < text.length; i++) {
        var ch = text.charAt(i);
        if (OPEN_Q.indexOf(ch) >= 0) { flush(); isDialogue = true; continue; }
        if (CLOSE_Q.indexOf(ch) >= 0) { flush(); isDialogue = false; continue; }
        buf += ch;
    }
    flush();

    // 旁白过长再按句切开，避免单次请求太长
    var final = [];
    for (var j = 0; j < out.length; j++) {
        var item = out[j];
        if (item.tag === "narration" && item.text.length > 120) {
            var parts = splitSentences(item.text);
            for (var k = 0; k < parts.length; k++) final.push({ text: parts[k], tag: item.tag });
        } else {
            final.push(item);
        }
    }
    if (final.length === 0) {
        var fallback = trimEdge(text);
        if (fallback) final.push({ text: fallback, tag: "narration" });
    }
    return final;
}

/* ---------------- 切句（手写，避开 lookbehind） ---------------- */
var SENT_END = "。！？!?…";
var TRAILING = "”’」』\"')）】》";

function splitSentences(text) {
    var s = String(text).replace(/\s+/g, " ").trim();
    if (!s) return [];

    var out = [], buf = "";
    for (var i = 0; i < s.length; i++) {
        var ch = s.charAt(i);
        buf += ch;
        if (SENT_END.indexOf(ch) >= 0) {
            // 把紧跟的引号/括号并进来
            while (i + 1 < s.length && TRAILING.indexOf(s.charAt(i + 1)) >= 0) {
                buf += s.charAt(++i);
            }
            var p = trimEdge(buf);
            buf = "";
            if (p && hasWord(p)) out.push(p);
        }
    }
    var rest = trimEdge(buf);
    if (rest && hasWord(rest)) out.push(rest);
    return out;
}
