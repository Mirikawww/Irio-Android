package com.irio

import android.content.Context
import android.content.SharedPreferences
import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File

/** Portable typed preferences. Never restore XML by copying over Android's live preference cache. */
internal object BackupPreferences {
    val names = setOf(
        "appearance_settings", "profile_settings", "provider_settings", "stt_settings", "search_settings",
        "speech_settings", "tool_settings", "tool_preferences", "image_generation_settings",
        "video_generation_settings", "irio_docs_mcp", "updates", "link_settings",
    )
    private fun portable(name: String, key: String): Boolean = when (name) {
        "provider_settings" -> key !in setOf("providers", "active_id", "encrypted_key", "name", "endpoint", "models", "selected_model")
        "stt_settings" -> key !in setOf("providers", "active_id")
        "search_settings" -> !key.endsWith("encrypted_key")
        else -> true
    }

    fun snapshot(context: Context): JSONObject = JSONObject().apply {
        names.forEach { name ->
            val values = JSONObject()
            context.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (key, value) ->
                if (portable(name, key)) values.put(key, encode(value))
            }
            put(name, values)
        }
    }

    private fun encode(value: Any?): JSONObject = JSONObject().apply {
        when (value) {
            is String -> put("type", "string").put("value", value)
            is Boolean -> put("type", "boolean").put("value", value)
            is Int -> put("type", "int").put("value", value)
            is Long -> put("type", "long").put("value", value.toString())
            is Float -> { require(value.isFinite()); put("type", "float").put("value", value.toString()) }
            is Set<*> -> { require(value.all { it is String }); put("type", "set").put("value", JSONArray(value.toList())) }
            else -> error("Unsupported backup preference type")
        }
    }

    fun decode(json: JSONObject): Map<String, Map<String, Any>> = buildMap {
        json.keys().forEach { name ->
            if (name !in names) return@forEach
            val values = json.getJSONObject(name)
            put(name, buildMap {
                values.keys().forEach value@{ key ->
                    if (!portable(name, key)) return@value
                    val item = values.getJSONObject(key)
                    val value: Any = when (item.getString("type")) {
                        "string" -> item.getString("value")
                        "boolean" -> item.getBoolean("value")
                        "int" -> item.getInt("value")
                        "long" -> item.getString("value").toLong()
                        "float" -> item.getString("value").toFloat().also { require(it.isFinite()) }
                        "set" -> item.getJSONArray("value").let { array -> (0 until array.length()).map { array.getString(it) }.toSet() }
                        else -> error("Unsupported backup preference type")
                    }
                    put(key, value)
                }
            })
        }
    }

    fun legacy(directory: File): Map<String, Map<String, Any>> = buildMap {
        directory.listFiles()?.filter { it.extension == "xml" && it.nameWithoutExtension in names }?.forEach { file ->
            val parser = Xml.newPullParser()
            file.reader().use { reader ->
                parser.setInput(reader)
                val values = linkedMapOf<String, Any>()
                while (parser.next() != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType != XmlPullParser.START_TAG || parser.name == "map") continue
                    val type = parser.name
                    val key = requireNotNull(parser.getAttributeValue(null, "name"))
                    val raw = parser.getAttributeValue(null, "value")
                    val value: Any = when (type) {
                        "string" -> parser.nextText()
                        "boolean" -> requireNotNull(raw).toBooleanStrict()
                        "int" -> requireNotNull(raw).toInt()
                        "long" -> requireNotNull(raw).toLong()
                        "float" -> requireNotNull(raw).toFloat().also { require(it.isFinite()) }
                        "set" -> buildSet {
                            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                                if (parser.eventType == XmlPullParser.END_TAG && parser.name == "set") break
                                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "string") add(parser.nextText())
                            }
                        }
                        else -> error("Unsupported legacy backup preference")
                    }
                    if (portable(file.nameWithoutExtension, key)) values[key] = value
                }
                put(file.nameWithoutExtension, values)
            }
        }
    }

    fun apply(context: Context, values: Map<String, Map<String, Any>>, overwrite: Boolean) {
        values.forEach { (name, incoming) ->
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            if (overwrite) prefs.all.keys.filter { portable(name, it) }.forEach(editor::remove)
            incoming.forEach { (key, value) -> if (overwrite || !prefs.contains(key)) editor.putValue(key, value) }
            check(editor.commit()) { "Unable to restore preferences" }
        }
    }

    private fun SharedPreferences.Editor.putValue(key: String, value: Any) {
        when (value) {
            is String -> putString(key, value)
            is Boolean -> putBoolean(key, value)
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is Float -> putFloat(key, value)
            is Set<*> -> putStringSet(key, value.map { it as String }.toSet())
            else -> error("Unsupported backup preference type")
        }
    }
}
