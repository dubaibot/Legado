package com.lanyue.reader.data.db

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 4.12 书源 JSON 中嵌套规则对象以 TypeConverter 序列化。
 * 规则对象在领域层是结构化类型，落库时统一转 JSON 字符串。
 */
class Converters {

    private val gson = Gson()

    @TypeConverter
    fun mapToJson(value: Map<String, String>?): String? =
        value?.let { gson.toJson(it) }

    @TypeConverter
    fun jsonToMap(value: String?): Map<String, String>? =
        value?.let {
            runCatching {
                gson.fromJson<Map<String, String>>(
                    it, object : TypeToken<Map<String, String>>() {}.type
                )
            }.getOrNull()
        }

    @TypeConverter
    fun listToJson(value: List<String>?): String? = value?.let { gson.toJson(it) }

    @TypeConverter
    fun jsonToList(value: String?): List<String>? =
        value?.let {
            runCatching {
                gson.fromJson<List<String>>(
                    it, object : TypeToken<List<String>>() {}.type
                )
            }.getOrNull()
        }

    /** 嵌套规则对象（ruleSearch / ruleToc ...）。 */
    @TypeConverter
    fun objectToJson(value: Any?): String? = value?.let { gson.toJson(it) }
}
