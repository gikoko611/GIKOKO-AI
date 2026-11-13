package com.ikoko.gikokoai

import android.util.Base64
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** HTTP error returned by the provider (not retried). */
class ApiError(msg: String) : Exception(msg)

object Api {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    private val slow = client.newBuilder().readTimeout(300, TimeUnit.SECONDS).build()
    private val JSON = "application/json".toMediaType()

    class Holder {
        @Volatile var call: Call? = null
        @Volatile var cancelled = false
        fun cancel() { cancelled = true; call?.cancel() }
    }

    private fun base(p: Provider) = p.baseUrl.trim().trimEnd('/')

    private fun req(p: Provider, path: String) = Request.Builder()
        .url(base(p) + path)
        .header("Authorization", "Bearer " + p.apiKey.trim())

    private fun errText(r: Response): String {
        val b = try { r.body?.string() } catch (_: Exception) { null } ?: ""
        val m = try { JSONObject(b).optJSONObject("error")?.optString("message") } catch (_: Exception) { null }
        return "HTTP ${r.code}: " + (if (!m.isNullOrBlank()) m else b.take(300))
    }

    /** Retries network failures only (timeouts, dropped connection). Cancellation and HTTP errors are not retried. */
    fun <T> retry(times: Int, f: () -> T): T {
        var last: Exception? = null
        for (i in 1..times) {
            try {
                return f()
            } catch (e: ApiError) {
                throw e
            } catch (e: IOException) {
                if (e.message == "Canceled") throw e
                last = e
                try { Thread.sleep(800L * i) } catch (_: InterruptedException) { throw e }
            }
        }
        throw last ?: IOException("Request failed")
    }

    fun listModels(p: Provider): List<String> {
        slowCall(client, req(p, "/models").get().build(), null).use { r ->
            if (!r.isSuccessful) throw ApiError(errText(r))
            val j = JSONObject(r.body!!.string())
            val arr: JSONArray = j.optJSONArray("data") ?: j.optJSONArray("models") ?: JSONArray()
            val out = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i)
                val id = o?.optString("id")?.takeIf { it.isNotBlank() } ?: o?.optString("name") ?: arr.optString(i)
                if (!id.isNullOrBlank()) out.add(id)
            }
            return out.sorted()
        }
    }

    private fun slowCall(c: OkHttpClient, rq: Request, h: Holder?): Response {
        if (h?.cancelled == true) throw IOException("Canceled")
        val call = c.newCall(rq)
        h?.call = call
        return call.execute()
    }

    /** Streaming chat completion. onDelta receives the full text so far (called on the calling thread). */
    fun chat(p: Provider, messages: JSONArray, h: Holder, onDelta: (String) -> Unit): String {
        val body = JSONObject().put("model", p.model).put("messages", messages).put("stream", true)
            .toString().toRequestBody(JSON)
        slowCall(client, req(p, "/chat/completions").post(body).build(), h).use { r ->
            if (!r.isSuccessful) throw ApiError(errText(r))
            val sb = StringBuilder()
            val ct = r.header("Content-Type") ?: ""
            if (ct.contains("event-stream")) {
                val src = r.body!!.source()
                while (true) {
                    val line = src.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val d = line.substring(5).trim()
                    if (d == "[DONE]") break
                    try {
                        val ch = JSONObject(d).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
                        val c = if (ch == null || ch.isNull("content")) "" else ch.optString("content")
                        if (c.isNotEmpty()) { sb.append(c); onDelta(sb.toString()) }
                    } catch (_: Exception) {
                    }
                }
            } else {
                val j = JSONObject(r.body!!.string())
                val m = j.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                if (!m.isNull("content")) sb.append(m.optString("content"))
                onDelta(sb.toString())
            }
            if (sb.isEmpty()) throw ApiError("The model returned an empty response.")
            return sb.toString()
        }
    }

    private fun sniffExt(b: ByteArray): String = when {
        b.size > 4 && b[0] == 0x89.toByte() && b[1] == 0x50.toByte() -> "png"
        b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "jpg"
        b.size > 12 && b[0] == 'R'.code.toByte() && b[8] == 'W'.code.toByte() -> "webp"
        else -> "png"
    }

    /** Text -> image. Handles both b64_json and url responses. Returns the saved file. */
    fun image(p: Provider, prompt: String, dir: File, h: Holder): File {
        val body = JSONObject().put("model", p.imageModel).put("prompt", prompt).put("n", 1)
            .toString().toRequestBody(JSON)
        val bytes: ByteArray = slowCall(slow, req(p, "/images/generations").post(body).build(), h).use { r ->
            if (!r.isSuccessful) throw ApiError(errText(r))
            val d = JSONObject(r.body!!.string()).getJSONArray("data").getJSONObject(0)
            if (!d.isNull("b64_json") && d.optString("b64_json").isNotEmpty()) {
                Base64.decode(d.getString("b64_json"), Base64.DEFAULT)
            } else if (!d.isNull("url")) {
                slowCall(slow, Request.Builder().url(d.getString("url")).build(), h).use { ir ->
                    if (!ir.isSuccessful) throw ApiError("Image download failed: HTTP ${ir.code}")
                    ir.body!!.bytes()
                }
            } else throw ApiError("The provider returned no image data.")
        }
        if (bytes.isEmpty()) throw ApiError("Empty image received.")
        dir.mkdirs()
        val f = File(dir, "img_${System.currentTimeMillis()}.${sniffExt(bytes)}")
        f.writeBytes(bytes)
        return f
    }

    /** Text -> speech (mp3). */
    fun speech(p: Provider, text: String, dir: File, h: Holder): File {
        val body = JSONObject().put("model", p.ttsModel).put("input", text)
            .put("voice", p.ttsVoice.ifBlank { "alloy" }).put("response_format", "mp3")
            .toString().toRequestBody(JSON)
        val bytes = slowCall(slow, req(p, "/audio/speech").post(body).build(), h).use { r ->
            if (!r.isSuccessful) throw ApiError(errText(r))
            r.body!!.bytes()
        }
        if (bytes.isEmpty()) throw ApiError("Empty audio received.")
        dir.mkdirs()
        val f = File(dir, "voice_${System.currentTimeMillis()}.mp3")
        f.writeBytes(bytes)
        return f
    }

    /** Heuristic capability detection from the model list. */
    data class Caps(val imageModel: String?, val ttsModel: String?, val vision: Boolean)

    fun detect(ids: List<String>): Caps {
        val img = ids.firstOrNull { Regex("dall-e|gpt-image|imagen|flux|stable-diffusion|sdxl", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        val tts = ids.firstOrNull { Regex("tts", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        val vis = ids.any { Regex("gpt-4o|gpt-4\\.1|gpt-5|vision|gemini|claude|llava|pixtral|qwen.*vl", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        return Caps(img, tts, vis)
    }
}
