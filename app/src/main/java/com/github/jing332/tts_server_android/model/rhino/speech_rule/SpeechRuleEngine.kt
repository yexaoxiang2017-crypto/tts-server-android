package com.github.jing332.tts_server_android.model.rhino.speech_rule

import android.content.Context
import com.github.jing332.database.entities.SpeechRule
import com.github.jing332.database.entities.TagsDataMap
import com.github.jing332.database.entities.systts.SpeechRuleInfo

import com.github.jing332.script.runtime.console.Console
import com.github.jing332.script.simple.SimpleScriptEngine
import com.github.jing332.script.source.toScriptSource
import com.github.jing332.tts_server_android.R
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.ScriptRuntime
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.Undefined

class SpeechRuleEngine(
    val context: Context,
    private val rule: SpeechRule
) {
    companion object {
        const val OBJ_JS = "SpeechRuleJS"

        const val FUNC_GET_TAG_NAME = "getTagName"
        const val FUNC_HANDLE_TEXT = "handleText"
        const val FUNC_SPLIT_TEXT = "splitText"

        fun getTagName(context: Context, speechRule: SpeechRule, info: SpeechRuleInfo): String {
            val engine = SpeechRuleEngine(context, speechRule)
            engine.eval()

            val tagName = try {
                engine.getTagName(info.tag, info.tagData)
            } catch (_: NoSuchMethodException) {
                speechRule.tags[info.tag] ?: ""
            }

            return tagName
        }
    }

    val engine = SimpleScriptEngine(context, rule.ruleId)
    var console: Console
        get() = engine.runtime.console
        set(value) {
            engine.runtime.console = value
        }


    private val objJS
        get() = engine.get(OBJ_JS) as NativeObject

    fun eval() {
        engine.execute(rule.code.toScriptSource())
    }

    /** JS 对象 / Map -> Map<String, String> */
    private fun jsStringMap(obj: Any?): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        when (obj) {
            is Scriptable -> {
                for (id in obj.ids) {
                    if (id !is String) continue
                    val v = try {
                        obj.get(id, obj)
                    } catch (_: Throwable) {
                        null
                    }
                    if (v != null && v !== Scriptable.NOT_FOUND && v !== Undefined.instance) {
                        out[id] = org.mozilla.javascript.Context.toString(v)
                    }
                }
            }

            is Map<*, *> -> obj.forEach { (k, v) ->
                if (k != null && v != null) out[k.toString()] = v.toString()
            }
        }
        return out
    }

    /** JS 对象 / Map -> TagsDataMap（tag -> key -> {label, hint, items...}） */
    private fun jsTagsData(obj: Any?): TagsDataMap {
        val out = LinkedHashMap<String, Map<String, Map<String, String>>>()
        when (obj) {
            is Scriptable -> {
                for (id in obj.ids) {
                    if (id !is String) continue
                    val v = try {
                        obj.get(id, obj)
                    } catch (_: Throwable) {
                        null
                    }
                    if (v is Scriptable || v is Map<*, *>) out[id] = jsNestedMap(v)
                }
            }

            is Map<*, *> -> obj.forEach { (k, v) ->
                if (k != null) out[k.toString()] = jsNestedMap(v)
            }
        }
        return out
    }

    private fun jsNestedMap(obj: Any?): Map<String, Map<String, String>> {
        val out = LinkedHashMap<String, Map<String, String>>()
        when (obj) {
            is Scriptable -> {
                for (id in obj.ids) {
                    if (id !is String) continue
                    val v = try {
                        obj.get(id, obj)
                    } catch (_: Throwable) {
                        null
                    }
                    if (v is Scriptable || v is Map<*, *>) out[id] = jsStringMap(v)
                }
            }

            is Map<*, *> -> obj.forEach { (k, v) ->
                if (k != null) out[k.toString()] = jsStringMap(v)
            }
        }
        return out
    }

    @Suppress("UNCHECKED_CAST")
    fun evalInfo() {
        eval()
        objJS.apply {
            rule.name = get("name").toString()
            rule.ruleId = get("id").toString()
            rule.author = get("author").toString()

            // Rhino 的 JS 对象字面量不是 java.util.Map：直接 as 强转要么抛异常，
            // 要么把 Scriptable 原样留在里面，表现为规则里声明的 tags/tagsData 读不出来
            // （用户在配置界面上就看不到「角色名」这类附加输入框）。
            rule.tags = jsStringMap(get("tags"))
            rule.tagsData = jsTagsData(getOrDefault("tagsData", Undefined.instance))

            runCatching {
                rule.version = ScriptRuntime.toNumber(get("version")).toInt()
            }.onFailure {
                throw NumberFormatException(context.getString(R.string.plugin_bad_format))
            }
        }
    }

    fun getTagName(tag: String, tagMap: Map<String, String>): String {
        return engine.invokeMethod(objJS, FUNC_GET_TAG_NAME, tag, tagMap).toString()
    }

    data class TagData(val id: String, val value: String)

    fun handleText(text: String, list: List<SpeechRuleInfo> = emptyList()): List<TextWithTag> {
        val tagsDataMap: MutableMap<String, MutableMap<String, MutableList<Map<String, String>>>> =
            mutableMapOf()
        list.forEach { info ->
            if (tagsDataMap[info.tag] == null)
                tagsDataMap[info.tag] = mutableMapOf()

            info.tagData.forEach {
                if (tagsDataMap[info.tag]!![it.key] == null)
                    tagsDataMap[info.tag]!![it.key] = mutableListOf()

                tagsDataMap[info.tag]!![it.key]!!.add(
                    mapOf(
                        "id" to info.configId.toString(),
                        "value" to it.value
                    )
                )
            }
        }
        return handleText(text, tagsDataMap)
    }

    /** ['dialogue']['role']= List<TagData>
     *@param tagsDataSet 例： key: dialogue, value: map(key: role, value: [{tagDataId:111, 张三, 李四])
     */
    fun handleText(
        text: String,
        tagsDataMap: Map<String, Map<String, List<Map<String, String>>>>
    ): List<TextWithTag> {
        val resultList: MutableList<TextWithTag> = mutableListOf()
        engine.invokeMethod(objJS, FUNC_HANDLE_TEXT, text, tagsDataMap)
            ?.run { this as List<*> }
            ?.let { list ->
                list.forEach {
                    if (it is Map<*, *>) {
                        resultList.add(
                            TextWithTag(
                                it["text"].toString(),
                                it["tag"].toString(),
                                // JS 里的数字到了 Kotlin 是 Double，大数 toString() 会变成
                                // "1.794468590022E12" 这种科学计数法，直接 toLong() 会抛
                                // NumberFormatException 把整段朗读搞挂，这里显式处理
                                anyToLong(it.getOrDefault("id", 0)),
                            )
                        )
                    }
                }

            }
        return resultList
    }

    @Suppress("UNCHECKED_CAST")
    fun splitText(text: String): List<CharSequence> {
        return engine.invokeMethod(
            objJS,
            FUNC_SPLIT_TEXT,
            text
        ) as List<CharSequence>
    }

    private fun anyToLong(v: Any?): Long = when (v) {
        null -> 0L
        is Number -> v.toLong()
        is CharSequence -> v.toString().toDoubleOrNull()?.toLong() ?: 0L
        else -> v.toString().toDoubleOrNull()?.toLong() ?: 0L
    }

    data class TextWithTag(val text: String, val tag: String, val id: Long)
}