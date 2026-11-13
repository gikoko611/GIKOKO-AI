package com.ikoko.gikokoai

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Provider(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "My provider",
    var baseUrl: String = "https://api.openai.com/v1",
    var apiKey: String = "",
    var model: String = "",
    var imageModel: String = "",
    var ttsModel: String = "",
    var ttsVoice: String = "alloy",
    var vision: Boolean = false,
    var image: Boolean = false,
    var tts: Boolean = false,
    var enabled: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("baseUrl", baseUrl).put("apiKey", apiKey)
        .put("model", model).put("imageModel", imageModel).put("ttsModel", ttsModel)
        .put("ttsVoice", ttsVoice).put("vision", vision).put("image", image)
        .put("tts", tts).put("enabled", enabled)

    companion object {
        fun from(j: JSONObject) = Provider(
            id = j.optString("id", UUID.randomUUID().toString()),
            name = j.optString("name", "Provider"),
            baseUrl = j.optString("baseUrl", ""),
            apiKey = j.optString("apiKey", ""),
            model = j.optString("model", ""),
            imageModel = j.optString("imageModel", ""),
            ttsModel = j.optString("ttsModel", ""),
            ttsVoice = j.optString("ttsVoice", "alloy"),
            vision = j.optBoolean("vision", false),
            image = j.optBoolean("image", false),
            tts = j.optBoolean("tts", false),
            enabled = j.optBoolean("enabled", true)
        )
    }
}

/** Providers (incl. API keys) live in Android-Keystore-backed EncryptedSharedPreferences. */
class Store(ctx: Context) {
    private val sp: SharedPreferences = create(ctx)

    companion object {
        private fun make(ctx: Context): SharedPreferences {
            val key = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            return EncryptedSharedPreferences.create(
                ctx, "gi_secure", key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }

        private fun create(ctx: Context): SharedPreferences = try {
            make(ctx)
        } catch (e: Exception) {
            ctx.deleteSharedPreferences("gi_secure")
            make(ctx)
        }
    }

    fun providers(): MutableList<Provider> {
        val out = mutableListOf<Provider>()
        try {
            val arr = JSONArray(sp.getString("providers", "[]"))
            for (i in 0 until arr.length()) out.add(Provider.from(arr.getJSONObject(i)))
        } catch (_: Exception) {
        }
        return out
    }

    fun save(list: List<Provider>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        sp.edit().putString("providers", arr.toString()).apply()
    }

    var activeId: String
        get() = sp.getString("active", "") ?: ""
        set(v) { sp.edit().putString("active", v).apply() }

    fun active(): Provider? {
        val l = providers()
        return l.firstOrNull { it.id == activeId } ?: l.firstOrNull { it.enabled }
    }
}

class Msg(
    var role: String,
    var text: String = "",
    var type: String = "text",       // text | image | audio | note
    var status: String = ""          // "" | processing | done | failed | cancelled
) {
    var path: String? = null
    var mime: String? = null
    var error: String = ""
    var prompt: String = ""
    var attachPath: String? = null
    var attachMime: String? = null
    var attachName: String? = null

    fun toJson(): JSONObject = JSONObject()
        .put("role", role).put("text", text).put("type", type).put("status", status)
        .put("path", path ?: JSONObject.NULL).put("mime", mime ?: JSONObject.NULL)
        .put("error", error).put("prompt", prompt)
        .put("attachPath", attachPath ?: JSONObject.NULL)
        .put("attachMime", attachMime ?: JSONObject.NULL)
        .put("attachName", attachName ?: JSONObject.NULL)

    companion object {
        private fun JSONObject.str(k: String): String? = if (isNull(k)) null else optString(k)
        fun from(j: JSONObject): Msg {
            val m = Msg(j.optString("role", "assistant"), j.optString("text", ""), j.optString("type", "text"), j.optString("status", ""))
            m.path = j.str("path"); m.mime = j.str("mime")
            m.error = j.optString("error", ""); m.prompt = j.optString("prompt", "")
            m.attachPath = j.str("attachPath"); m.attachMime = j.str("attachMime"); m.attachName = j.str("attachName")
            // A task that was running when the app died cannot continue: be honest about it.
            if (m.status == "processing") {
                m.status = "failed"
                m.error = "Interrupted (app was closed). Tap Retry."
            }
            return m
        }
    }
}

class Conv(val id: String = UUID.randomUUID().toString(), var title: String = "New chat") {
    val msgs = mutableListOf<Msg>()
    var updated: Long = System.currentTimeMillis()
}

data class ConvInfo(val id: String, val title: String, val updated: Long, val count: Int)

class ConvStore(private val ctx: Context) {
    private val dir = File(ctx.filesDir, "conv").apply { mkdirs() }

    fun save(c: Conv) {
        if (c.msgs.isEmpty()) return
        c.updated = System.currentTimeMillis()
        val arr = JSONArray()
        c.msgs.forEach { arr.put(it.toJson()) }
        val j = JSONObject().put("id", c.id).put("title", c.title).put("updated", c.updated).put("msgs", arr)
        val tmp = File(dir, "${c.id}.tmp")
        tmp.writeText(j.toString())
        tmp.renameTo(File(dir, "${c.id}.json"))
    }

    fun load(id: String): Conv? = try {
        val j = JSONObject(File(dir, "$id.json").readText())
        val c = Conv(j.getString("id"), j.optString("title", "Chat"))
        c.updated = j.optLong("updated", 0)
        val arr = j.getJSONArray("msgs")
        for (i in 0 until arr.length()) c.msgs.add(Msg.from(arr.getJSONObject(i)))
        c
    } catch (_: Exception) {
        null
    }

    fun list(): List<ConvInfo> {
        val out = mutableListOf<ConvInfo>()
        dir.listFiles { f -> f.name.endsWith(".json") }?.forEach { f ->
            try {
                val j = JSONObject(f.readText())
                out.add(ConvInfo(j.getString("id"), j.optString("title", "Chat"), j.optLong("updated", 0), j.optJSONArray("msgs")?.length() ?: 0))
            } catch (_: Exception) {
            }
        }
        return out.sortedByDescending { it.updated }
    }

    fun rename(id: String, title: String) {
        load(id)?.let { it.title = title; val u = it.updated; save(it); File(dir, "$id.json").setLastModified(u) }
    }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
        File(ctx.filesDir, "gen/$id").deleteRecursively()
        File(ctx.filesDir, "att/$id").deleteRecursively()
    }

    fun deleteAll() {
        dir.listFiles()?.forEach { it.delete() }
        File(ctx.filesDir, "gen").deleteRecursively()
    }
}
