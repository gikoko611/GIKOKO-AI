package com.ikoko.gikokoai

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.icu.text.BreakIterator
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Lightweight local media utilities; all media selection uses SAF URIs. */
class StudioActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .build()

    private var audioUri: Uri? = null
    private var videoUri: Uri? = null
    private var audioLabel: TextView? = null
    private var videoLabel: TextView? = null
    private var transcript: EditText? = null
    private var pdfText: EditText? = null
    private var modelInput: EditText? = null
    private var status: TextView? = null
    private var busyBar: ProgressBar? = null
    private var framePreview: ImageView? = null
    private var player: MediaPlayer? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun text(s: String, size: Float = 14f, color: Int = C.TEXT, bold: Boolean = false) =
        TextView(this).apply { text = s; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD) }
    private fun button(s: String, color: Int = C.GOLD, action: () -> Unit) =
        TextView(this).apply {
            text = s; textSize = 12f; gravity = Gravity.CENTER; setTextColor(color)
            setPadding(dp(12), dp(11), dp(12), dp(11))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(C.SURF2); cornerRadius = dp(8).toFloat(); setStroke(dp(1), color)
            }
            setOnClickListener { action() }
        }
    private fun field(hintText: String, multiline: Boolean = false) =
        EditText(this).apply {
            hint = hintText; setTextColor(C.TEXT); setHintTextColor(C.TEXT2); textSize = 14f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(C.SURF2); cornerRadius = dp(8).toFloat(); setStroke(dp(1), C.DIV)
            }
            if (multiline) { minLines = 4; maxLines = 12; gravity = Gravity.TOP; isSingleLine = false }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildPage()
    }

    private fun buildPage() {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BG) }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)); setBackgroundColor(C.SURF) }
        header.addView(text("G.I KO KO AI  /  MEDIA STUDIO", 16f, C.GOLD, true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(button("CLOSE", C.TEXT2) { finish() })
        page.addView(header)
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(24)) }
        status = text("Local-first tools • Android 10 compatible", 12f, C.TEXT2)
        body.addView(status)
        busyBar = ProgressBar(this).apply { visibility = View.GONE; isIndeterminate = true }
        body.addView(busyBar)

        section(body, "01  AUDIO TRANSCRIPTION", "Select an audio file and use your configured OpenAI-compatible provider's transcription endpoint.")
        audioLabel = text("No audio selected", 12f, C.TEXT2)
        body.addView(audioLabel)
        val audioButtons = horizontal()
        audioButtons.addView(button("IMPORT AUDIO") { pick(101, "audio/*") })
        audioButtons.addView(button("PLAY") { playAudio() })
        audioButtons.addView(button("TRANSCRIBE", C.GREEN) { transcribe() })
        body.addView(audioButtons)
        modelInput = field("Transcription model (default: whisper-1)")
        modelInput?.setText("whisper-1")
        body.addView(modelInput)
        transcript = field("Transcription result appears here…", true)
        body.addView(transcript)

        section(body, "02  VIDEO INSPECTOR", "Inspect video metadata locally and extract a preview frame without uploading the video.")
        videoLabel = text("No video selected", 12f, C.TEXT2)
        body.addView(videoLabel)
        body.addView(button("IMPORT VIDEO") { pick(102, "video/*") })
        body.addView(button("INSPECT VIDEO + EXTRACT FRAME", C.GREEN) { inspectVideo() })
        framePreview = ImageView(this).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(C.SURF2) }
        body.addView(framePreview, LinearLayout.LayoutParams(-1, dp(190)).apply { topMargin = dp(8) })

        section(body, "03  PDF CREATOR", "Create a PDF from text. Android's system font fallback is used for Burmese Unicode.")
        pdfText = field("Write or paste Burmese / English text here…", true)
        body.addView(pdfText)
        body.addView(button("EXPORT PDF", C.GREEN) { exportPdf() })

        body.addView(text("\nPOWERED BY I KO KO", 10f, C.GOLD, true))
        scroll.addView(body)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(page)
    }

    private fun horizontal() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(8), 0, dp(8))
    }

    private fun section(parent: LinearLayout, title: String, description: String) {
        parent.addView(text("\n$title", 15f, C.GOLD, true))
        parent.addView(text(description, 12f, C.TEXT2))
    }

    private fun pick(code: Int, mime: String) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime)
        startActivityForResult(intent, code)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            101 -> { audioUri = uri; audioLabel?.text = displayName(uri) }
            102 -> { videoUri = uri; videoLabel?.text = displayName(uri) }
        }
    }

    private fun displayName(uri: Uri): String {
        var name = "Selected file"
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0) name = c.getString(i) ?: name
            }
        }
        return name
    }

    private fun copyToCache(uri: Uri, maxBytes: Long): File {
        val out = File(cacheDir, "media_${System.currentTimeMillis()}")
        contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { output ->
                val buffer = ByteArray(32 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    require(total <= maxBytes) { "File is larger than ${maxBytes / (1024 * 1024)} MB." }
                    output.write(buffer, 0, n)
                }
            }
        } ?: throw IllegalStateException("Cannot open selected file")
        return out
    }

    private fun transcribe() {
        val uri = audioUri ?: run { toast("Import an audio file first."); return }
        val provider = Store(this).active() ?: run { toast("Add an API provider in Settings first."); return }
        if (provider.apiKey.isBlank()) { toast("The active provider has no API key."); return }
        val model = modelInput?.text?.toString()?.trim().orEmpty().ifBlank { "whisper-1" }
        setBusy(true, "Transcribing audio…")
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val file = copyToCache(uri, 25L * 1024 * 1024)
                    try {
                        val mime = contentResolver.getType(uri)?.toMediaTypeOrNull() ?: "application/octet-stream".toMediaTypeOrNull()
                        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                            .addFormDataPart("model", model)
                            .addFormDataPart("response_format", "json")
                            .addFormDataPart("file", displayName(uri), file.asRequestBody(mime))
                            .build()
                        val base = provider.baseUrl.trim().trimEnd('/')
                        val request = Request.Builder().url("$base/audio/transcriptions")
                            .header("Authorization", "Bearer ${provider.apiKey.trim()}")
                            .post(body).build()
                        http.newCall(request).execute().use { response ->
                            val raw = response.body?.string().orEmpty()
                            if (!response.isSuccessful) {
                                val message = try { JSONObject(raw).optJSONObject("error")?.optString("message") } catch (_: Exception) { null }
                                throw IllegalStateException("HTTP ${response.code}: ${message?.takeIf { it.isNotBlank() } ?: raw.take(240)}")
                            }
                            TranscriptParser.parse(raw)
                        }
                    } finally { file.delete() }
                }
                transcript?.setText(result)
                setBusy(false, "Transcription complete.")
            } catch (e: Exception) {
                setBusy(false, "Transcription failed: ${e.message ?: "Unknown error"}")
            }
        }
    }

    private fun playAudio() {
        val uri = audioUri ?: run { toast("Import an audio file first."); return }
        try {
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(this@StudioActivity, uri)
                setOnPreparedListener { it.start(); status?.text = "Playing audio…" }
                setOnCompletionListener { status?.text = "Audio playback complete." }
                setOnErrorListener { _, _, _ -> status?.text = "Audio playback failed."; true }
                prepareAsync()
            }
        } catch (e: Exception) { toast("Cannot play audio: ${e.message}") }
    }

    private fun inspectVideo() {
        val uri = videoUri ?: run { toast("Import a video first."); return }
        setBusy(true, "Reading video metadata locally…")
        scope.launch {
            try {
                val data = withContext(Dispatchers.IO) {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(this@StudioActivity, uri)
                        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) ?: "?"
                        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) ?: "?"
                        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) ?: "0"
                        val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE) ?: "?"
                        val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        Triple("Duration: ${duration / 1000}s\nResolution: ${width} × ${height}\nRotation: ${rotation}°\nBitrate: ${bitrate} bps", frame, duration)
                    } finally { retriever.release() }
                }
                status?.text = data.first
                framePreview?.setImageBitmap(data.second)
                setBusy(false, "Video inspection complete. No video was uploaded.")
            } catch (e: Exception) { setBusy(false, "Video inspection failed: ${e.message}") }
        }
    }

    private fun exportPdf() {
        val content = pdfText?.text?.toString().orEmpty()
        if (content.isBlank()) { toast("Enter some text first."); return }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/pdf").putExtra(Intent.EXTRA_TITLE, "GIKOKOAI-document.pdf")
        pendingPdfText = content
        startActivityForResult(intent, 103)
    }

    private var pendingPdfText: String = ""

    private fun writePdf(uri: Uri, content: String) {
        val pdf = PdfDocument()
        try {
            val pageWidth = 595
            val pageHeight = 842
            val margin = 42f
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.BLACK
                textSize = 12f
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            }
            val lineHeight = 19f
            val maxWidth = pageWidth - margin * 2
            val lines = wrapUnicode(content, paint, maxWidth)
            var pageNo = 1
            var page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNo).create())
            var canvas: Canvas = page.canvas
            var y = margin
            for (line in lines) {
                if (y > pageHeight - margin) {
                    pdf.finishPage(page)
                    pageNo++
                    page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNo).create())
                    canvas = page.canvas
                    y = margin
                }
                canvas.drawText(line, margin, y, paint)
                y += lineHeight
            }
            pdf.finishPage(page)
            contentResolver.openOutputStream(uri)?.use { pdf.writeTo(it) }
                ?: throw IllegalStateException("Cannot create PDF output")
        } finally { pdf.close() }
    }

    private fun wrapUnicode(value: String, paint: Paint, maxWidth: Float): List<String> {
        val out = mutableListOf<String>()
        val paragraphs = value.replace("\r", "").split("\n")
        for (paragraph in paragraphs) {
            if (paragraph.isEmpty()) { out.add(""); continue }
            val breaker = BreakIterator.getCharacterInstance(Locale("my"))
            breaker.setText(paragraph)
            var start = breaker.first()
            var line = StringBuilder()
            var end = breaker.next()
            while (end != BreakIterator.DONE) {
                val piece = paragraph.substring(start, end)
                if (line.isNotEmpty() && paint.measureText(line.toString() + piece) > maxWidth) {
                    out.add(line.toString())
                    line = StringBuilder()
                }
                line.append(piece)
                start = end
                end = breaker.next()
            }
            if (line.isNotEmpty()) out.add(line.toString())
        }
        return out
    }

    private fun setBusy(busy: Boolean, message: String) {
        busyBar?.visibility = if (busy) View.VISIBLE else View.GONE
        status?.text = message
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        player?.release()
        player = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 103 && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            try { writePdf(uri, pendingPdfText); toast("PDF exported successfully.") }
            catch (e: Exception) { toast("PDF export failed: ${e.message}") }
        }
    }
}

internal object TranscriptParser {
    fun parse(json: String): String {
        val obj = JSONObject(json)
        return obj.optString("text").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Provider returned no transcript text.")
    }
}
