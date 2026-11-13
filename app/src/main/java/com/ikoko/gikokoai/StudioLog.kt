package com.ikoko.gikokoai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small persistent diagnostic history. Secrets and request bodies are never stored. */
object StudioLog {
    private const val PREFS = "gi_studio_logs"
    private const val KEY = "entries"
    private const val LIMIT = 120

    @Synchronized
    fun add(context: Context, area: String, provider: String = "", model: String = "", status: String, detail: String = "") {
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val old = JSONArray(prefs.getString(KEY, "[]"))
            val next = JSONArray()
            val entry = JSONObject()
                .put("time", System.currentTimeMillis())
                .put("area", area.take(48))
                .put("provider", provider.take(64))
                .put("model", model.take(96))
                .put("status", status.take(24))
                .put("detail", detail.replace(Regex("(?i)(api[_-]?key|authorization|bearer)\\s*[:=]?\\s*[^\\s,]+"), "$1=[redacted]").take(600))
            next.put(entry)
            for (i in 0 until minOf(old.length(), LIMIT - 1)) {
                val item = old.optJSONObject(i) ?: continue
                next.put(item)
            }
            prefs.edit().putString(KEY, next.toString()).apply()
        } catch (_: Exception) { }
    }

    fun entries(context: Context): List<String> {
        val arr = try { JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]")) }
        catch (_: Exception) { JSONArray() }
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        return (0 until arr.length()).mapNotNull { i ->
            val j = arr.optJSONObject(i) ?: return@mapNotNull null
            val time = fmt.format(Date(j.optLong("time")))
            val provider = j.optString("provider").takeIf { it.isNotBlank() }?.let { " • $it" } ?: ""
            val model = j.optString("model").takeIf { it.isNotBlank() }?.let { " / $it" } ?: ""
            "$time  [${j.optString("status")}] ${j.optString("area")}$provider$model\n${j.optString("detail")}"
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
