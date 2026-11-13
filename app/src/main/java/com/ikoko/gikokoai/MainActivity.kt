package com.ikoko.gikokoai

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object C {
    val BG = 0xFF080B10.toInt()
    val SURF = 0xFF10151D.toInt()
    val SURF2 = 0xFF171E28.toInt()
    val GREEN = 0xFF39FF88.toInt()
    val GOLD = 0xFFD6AE55.toInt()
    val BLUE = 0xFF258BFF.toInt()
    val RED = 0xFFFF3B4F.toInt()
    val TEXT = 0xFFF4F6FA.toInt()
    val TEXT2 = 0xFF929BA8.toInt()
    val DIV = 0xFF293341.toInt()
}

private data class Block(val code: Boolean, val lang: String, val body: String)

private const val SYSTEM_PROMPT =
    "You are G.I KO KO AI, a helpful creative and coding assistant inside an Android app. " +
        "Reply in the same language the user writes in (Burmese if they write Burmese). " +
        "When asked for a web page or HTML app, return ONE complete self-contained HTML file in a single ```html code block. " +
        "Never claim you executed code or produced files you did not produce."

private const val VIDEO_PROMPT =
    " The user wants a video. You cannot render video yourself and this app has no video renderer. " +
        "Produce a short script, a scene-by-scene storyboard (duration, visual, narration, subtitle text) " +
        "and one text-to-video prompt per scene. Say clearly that this is a plan, not a finished video."

class MainActivity : AppCompatActivity() {

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private lateinit var store: Store
    private lateinit var convStore: ConvStore
    private val scope = MainScope()
    private var conv = Conv()

    private lateinit var content: FrameLayout
    private lateinit var chatPage: View
    private lateinit var msgList: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var sendBtn: TextView
    private lateinit var attachLabel: TextView
    private val navViews = mutableListOf<TextView>()
    private var tab = 0

    private var busy = false
    private var stopped = false
    private var holder: Api.Holder? = null
    private var job: Job? = null

    private var attachFile: File? = null
    private var attachMime: String? = null
    private var attachName: String? = null
    private var pendingSave: File? = null

    private var player: MediaPlayer? = null
    private var playingPath: String? = null

    // ---------------------------------------------------------------- helpers

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun lp(w: Int, h: Int, wt: Float = 0f) = LinearLayout.LayoutParams(w, h, wt)

    private fun box(fill: Int, stroke: Int, r: Int = 8, sw: Int = 1) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(r).toFloat(); setStroke(maxOf(1, dp(sw)), stroke)
    }

    private fun tv(t: CharSequence, size: Float = 14f, color: Int = C.TEXT, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun styleBtn(b: TextView, label: String, color: Int) {
        b.text = label; b.setTextColor(color); b.background = box(Color.TRANSPARENT, color, 6)
    }

    private fun btn(label: String, color: Int = C.GOLD, onClick: () -> Unit) = TextView(this).apply {
        textSize = 12f; gravity = Gravity.CENTER; setPadding(dp(12), 0, dp(12), 0)
        setTypeface(typeface, Typeface.BOLD)
        styleBtn(this, label, color)
        setOnClickListener { onClick() }
    }

    private fun row(vararg v: View): View {
        val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val l = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        v.forEach { l.addView(it, lp(WRAP, dp(40)).apply { marginEnd = dp(8) }) }
        hs.addView(l)
        return hs
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun copy(text: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("G.I KO KO AI", text))
        toast("Copied")
    }

    private fun genDir() = File(filesDir, "gen/${conv.id}").apply { mkdirs() }

    private fun mimeOf(f: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "application/octet-stream"

    private fun fmtSize(b: Long): String = when {
        b > 1024 * 1024 -> "%.1f MB".format(b / 1048576.0)
        b > 1024 -> "%d KB".format(b / 1024)
        else -> "$b B"
    }

    private fun confirm(msg: String, ok: () -> Unit) {
        AlertDialog.Builder(this).setMessage(msg).setNegativeButton("Cancel", null)
            .setPositiveButton("OK") { _, _ -> ok() }.show()
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_GI)
        super.onCreate(savedInstanceState)
        store = Store(this)
        convStore = ConvStore(this)
        conv = convStore.list().firstOrNull()?.let { convStore.load(it.id) } ?: Conv()
        buildUi()
    }

    override fun onDestroy() {
        holder?.cancel()
        releasePlayer()
        saveConv()
        scope.cancel()
        super.onDestroy()
    }

    private fun saveConv() = try { convStore.save(conv) } catch (_: Exception) { }

    // ---------------------------------------------------------------- layout

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BG) }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8)); setBackgroundColor(C.SURF)
        }
        head.addView(ImageView(this).apply { setImageResource(R.drawable.logo_gi) }, lp(dp(40), dp(40)))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
        titles.addView(tv("G.I KO KO AI", 17f, C.GOLD, true))
        titles.addView(tv("CREATE BEYOND LIMITS", 9f, C.GREEN, true))
        head.addView(titles, lp(0, WRAP, 1f))
        head.addView(btn("＋ NEW", C.GREEN) { newChat() }, lp(WRAP, dp(40)))
        root.addView(head, lp(MATCH, WRAP))
        root.addView(View(this).apply { setBackgroundColor(C.GOLD) }, lp(MATCH, dp(1)))

        content = FrameLayout(this)
        root.addView(content, lp(MATCH, 0, 1f))

        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(C.SURF) }
        listOf("CHAT", "HISTORY", "FILES", "SETTINGS", "STUDIO").forEachIndexed { i, n ->
            val t = tv(n, 10f, C.TEXT2, true).apply {
                gravity = Gravity.CENTER
                setOnClickListener {
                    if (i == 4) startActivity(Intent(this@MainActivity, StudioActivity::class.java))
                    else show(i)
                }
            }
            navViews.add(t)
            nav.addView(t, lp(0, dp(52), 1f))
        }
        root.addView(View(this).apply { setBackgroundColor(C.DIV) }, lp(MATCH, dp(1)))
        root.addView(nav, lp(MATCH, WRAP))
        setContentView(root)

        chatPage = buildChat()
        renderAll()
        show(0)
    }

    private fun show(i: Int) {
        tab = i
        content.removeAllViews()
        content.addView(
            when (i) {
                0 -> chatPage
                1 -> buildHistory()
                2 -> buildFiles()
                else -> buildSettings()
            }, FrameLayout.LayoutParams(MATCH, MATCH)
        )
        navViews.forEachIndexed { k, t -> t.setTextColor(if (k == i) C.GOLD else C.TEXT2) }
    }

    // ---------------------------------------------------------------- chat page

    private fun buildChat(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this)
        msgList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        scroll.addView(msgList)
        root.addView(scroll, lp(MATCH, 0, 1f))

        attachLabel = tv("", 12f, C.GREEN).apply {
            visibility = View.GONE; setPadding(dp(14), dp(6), dp(14), dp(2))
            setOnClickListener { clearAttachment() }
        }
        root.addView(attachLabel)

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8)); setBackgroundColor(C.SURF)
        }
        bar.addView(btn("＋", C.BLUE) { pickFile() }, lp(dp(44), dp(44)).apply { marginEnd = dp(6) })
        input = EditText(this).apply {
            hint = "Message G.I KO KO AI…"; setTextColor(C.TEXT); setHintTextColor(0xFF5A6472.toInt()); textSize = 15f
            background = box(C.SURF2, C.DIV, 10); setPadding(dp(12), dp(8), dp(12), dp(8)); maxLines = 5
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        bar.addView(input, lp(0, WRAP, 1f))
        sendBtn = btn("SEND", C.GREEN) { if (busy) stopRun() else submit() }
        bar.addView(sendBtn, lp(dp(68), dp(44)).apply { marginStart = dp(6) })
        root.addView(bar, lp(MATCH, WRAP))
        return root
    }

    private fun setBusy(b: Boolean) {
        busy = b
        if (b) styleBtn(sendBtn, "STOP", C.RED) else styleBtn(sendBtn, "SEND", C.GREEN)
    }

    private fun welcomeView(): View {
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(8), dp(24), dp(8), dp(8)); tag = "welcome" }
        l.addView(ImageView(this).apply { setImageResource(R.drawable.logo_gi) }, lp(dp(96), dp(96)))
        l.addView(tv("G.I KO KO AI", 20f, C.GOLD, true).apply { setPadding(0, dp(8), 0, 0) })
        l.addView(tv("CREATE BEYOND LIMITS", 11f, C.GREEN, true))
        l.addView(tv(
            "\nJust ask — chat, code, documents.\n\n" +
                "/image <prompt>  → generate an image\n" +
                "/voice <text>  → create a voiceover (mp3)\n" +
                "＋  → attach a text file or image\n\n" +
                "First, add your API provider in SETTINGS.", 13f, C.TEXT2
        ))
        l.addView(btn("OPEN SETTINGS", C.GOLD) { show(3) }, lp(WRAP, dp(44)).apply { topMargin = dp(12) })
        l.addView(tv("\nPOWERED BY I KO KO", 10f, C.GOLD))
        return l
    }

    private fun renderAll() {
        msgList.removeAllViews()
        if (conv.msgs.isEmpty()) { msgList.addView(welcomeView()); return }
        conv.msgs.forEachIndexed { i, m -> msgList.addView(msgView(i, m)) }
        scrollDown()
    }

    private fun addMsgView(m: Msg) {
        if (msgList.childCount == 1 && msgList.getChildAt(0).tag == "welcome") msgList.removeAllViews()
        msgList.addView(msgView(conv.msgs.indexOf(m), m))
        scrollDown()
    }

    private fun scrollDown() { scroll.post { scroll.fullScroll(View.FOCUS_DOWN) } }

    private fun msgView(i: Int, m: Msg): View {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10))
            background = box(
                if (m.role == "user") 0xFF1B2433.toInt() else C.SURF2,
                if (m.role == "user") C.BLUE else if (m.type == "note") C.GOLD else C.DIV, 10
            )
        }
        val l = if (m.role == "user") lp(WRAP, WRAP).apply { gravity = Gravity.END; marginStart = dp(36) } else lp(MATCH, WRAP)
        wrap.addView(card, l)
        fill(card, i, m)
        return wrap
    }

    private fun refill(i: Int) {
        if (i < 0 || i >= conv.msgs.size || i >= msgList.childCount) return
        val w = msgList.getChildAt(i) as? LinearLayout ?: return
        val card = w.getChildAt(0) as? LinearLayout ?: return
        fill(card, i, conv.msgs[i])
    }

    private fun inLastTurn(i: Int) = i > conv.msgs.indexOfLast { it.role == "user" }

    private fun progressRow(label: String): View {
        val l = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(2)) }
        l.addView(ProgressBar(this).apply { isIndeterminate = true; indeterminateTintList = ColorStateList.valueOf(C.GREEN) }, lp(dp(22), dp(22)))
        l.addView(tv(label, 12f, C.GREEN).apply { setPadding(dp(8), 0, 0, 0) })
        return l
    }

    private fun fill(card: LinearLayout, i: Int, m: Msg) {
        card.removeAllViews()
        val user = m.role == "user"
        card.addView(tv(if (user) "YOU" else "G.I KO KO AI", 10f, if (user) C.BLUE else C.GOLD, true))
        when (m.type) {
            "image" -> fillImage(card, i, m)
            "audio" -> fillAudio(card, i, m)
            "note" -> card.addView(tv(m.text, 13f, C.GOLD).apply { setTextIsSelectable(true) })
            else -> fillText(card, i, m)
        }
    }

    private fun failedBlock(card: LinearLayout, i: Int, m: Msg) {
        card.addView(tv(if (m.status == "cancelled") "■ Stopped" else "⚠ " + m.error, 13f, C.RED))
        val b = mutableListOf<View>()
        if (inLastTurn(i)) b.add(btn("RETRY", C.GREEN) { regenerate() })
        b.add(btn("DELETE", C.RED) { deleteMsg(i) })
        card.addView(row(*b.toTypedArray()))
    }

    private fun fillText(card: LinearLayout, i: Int, m: Msg) {
        if (m.role == "user") {
            card.addView(tv(m.text, 15f).apply { setTextIsSelectable(true) })
            if (m.attachName != null) card.addView(tv("📎 ${m.attachName}", 12f, C.GREEN))
            if (!busy) card.addView(row(btn("EDIT", C.BLUE) { editFrom(i) }))
            return
        }
        if (m.status == "processing" && m.text.isEmpty()) { card.addView(progressRow("Waiting for provider…")); return }
        renderMarkdown(card, m.text, m.status != "processing")
        when (m.status) {
            "processing" -> card.addView(progressRow("Streaming…"))
            "failed", "cancelled" -> failedBlock(card, i, m)
            else -> {
                val b = mutableListOf<View>(btn("COPY", C.GOLD) { copy(m.text) })
                if (inLastTurn(i) && !busy) b.add(btn("REGENERATE", C.GREEN) { regenerate() })
                card.addView(row(*b.toTypedArray()))
            }
        }
    }

    private fun fillImage(card: LinearLayout, i: Int, m: Msg) {
        when (m.status) {
            "processing" -> { card.addView(progressRow("Generating image… waiting for the provider")); return }
            "failed", "cancelled" -> { failedBlock(card, i, m); return }
        }
        val f = m.path?.let { File(it) }
        if (f == null || !f.exists()) {
            card.addView(tv("File no longer exists.", 13f, C.RED))
            card.addView(row(btn("DELETE", C.RED) { deleteMsg(i) })); return
        }
        val bmp = decodeSampled(f.path, 720)
        if (bmp != null) {
            card.addView(ImageView(this).apply {
                setImageBitmap(bmp); adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER
                setOnClickListener { showFullImage(f) }
            }, lp(MATCH, WRAP).apply { topMargin = dp(8) })
        }
        if (m.prompt.isNotBlank()) card.addView(tv(m.prompt, 12f, C.TEXT2).apply { setPadding(0, dp(6), 0, 0) })
        val b = mutableListOf<View>(
            btn("SAVE", C.GOLD) { saveAs(f) },
            btn("SHARE", C.BLUE) { share(f) }
        )
        if (inLastTurn(i) && !busy) b.add(btn("REGENERATE", C.GREEN) { regenerate() })
        b.add(btn("DELETE", C.RED) { deleteMsg(i) })
        card.addView(row(*b.toTypedArray()))
    }

    private fun fillAudio(card: LinearLayout, i: Int, m: Msg) {
        when (m.status) {
            "processing" -> { card.addView(progressRow("Generating voiceover… waiting for the provider")); return }
            "failed", "cancelled" -> { failedBlock(card, i, m); return }
        }
        val f = m.path?.let { File(it) }
        if (f == null || !f.exists()) {
            card.addView(tv("File no longer exists.", 13f, C.RED))
            card.addView(row(btn("DELETE", C.RED) { deleteMsg(i) })); return
        }
        card.addView(tv("🔊 ${f.name} · ${fmtSize(f.length())}", 13f, C.TEXT))
        if (m.prompt.isNotBlank()) card.addView(tv(m.prompt.take(300), 12f, C.TEXT2).apply { setPadding(0, dp(4), 0, 0) })
        val playing = playingPath == f.path
        val b = mutableListOf<View>(
            btn(if (playing) "■ STOP" else "▶ PLAY", if (playing) C.RED else C.GREEN) { togglePlay(f, i) },
            btn("SAVE", C.GOLD) { saveAs(f) },
            btn("SHARE", C.BLUE) { share(f) }
        )
        if (inLastTurn(i) && !busy) b.add(btn("REGENERATE", C.GREEN) { regenerate() })
        b.add(btn("DELETE", C.RED) { deleteMsg(i) })
        card.addView(row(*b.toTypedArray()))
    }

    // ---------------------------------------------------------------- markdown / code

    private fun parseBlocks(t: String): List<Block> {
        val out = mutableListOf<Block>()
        t.split("```").forEachIndexed { i, part ->
            if (i % 2 == 0) {
                if (part.isNotBlank()) out.add(Block(false, "", part.trim('\n')))
            } else {
                val nl = part.indexOf('\n')
                val lang = if (nl >= 0) part.substring(0, nl).trim().lowercase() else ""
                val body = if (nl >= 0) part.substring(nl + 1) else part
                out.add(Block(true, lang, body.trimEnd()))
            }
        }
        return out
    }

    private fun renderMarkdown(card: LinearLayout, text: String, finished: Boolean) {
        val blocks = parseBlocks(text)
        val hasHtml = blocks.any { it.code && (it.lang == "html" || it.lang == "htm") }
        blocks.forEach { b ->
            if (!b.code) {
                card.addView(tv(styleInline(b.body), 15f).apply { setTextIsSelectable(finished); setPadding(0, dp(4), 0, dp(2)) })
            } else codeBlock(card, b, finished, if (hasHtml) text else null)
        }
    }

    private fun codeBlock(card: LinearLayout, b: Block, finished: Boolean, zipSource: String?) {
        val boxV = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = box(0xFF0B0F16.toInt(), C.DIV, 6)
            setPadding(dp(8), dp(6), dp(8), dp(8))
        }
        boxV.addView(tv(if (b.lang.isEmpty()) "code" else b.lang, 10f, C.TEXT2, true))
        val hs = HorizontalScrollView(this)
        hs.addView(TextView(this).apply {
            text = highlight(b.body); typeface = Typeface.MONOSPACE; textSize = 12f; setTextColor(C.TEXT)
        })
        boxV.addView(hs)
        if (finished) {
            val btns = mutableListOf<View>(
                btn("COPY", C.GOLD) { copy(b.body) },
                btn("SAVE FILE", C.BLUE) { saveAs(codeFile(b)) }
            )
            if (b.lang == "html" || b.lang == "htm" || b.lang == "svg") btns.add(btn("PREVIEW", C.GREEN) { previewHtml(b) })
            if (zipSource != null) btns.add(btn("ZIP", C.GOLD) { zipProject(zipSource) })
            boxV.addView(row(*btns.toTypedArray()))
        }
        card.addView(boxV, lp(MATCH, WRAP).apply { topMargin = dp(6); bottomMargin = dp(4) })
    }

    private fun extFor(lang: String) = when (lang) {
        "html", "htm" -> "html"; "css" -> "css"; "js", "javascript" -> "js"; "python", "py" -> "py"
        "kotlin", "kt" -> "kt"; "java" -> "java"; "json" -> "json"; "svg" -> "svg"; "xml" -> "xml"
        "md", "markdown" -> "md"; "csv" -> "csv"; "bash", "sh" -> "sh"; else -> "txt"
    }

    private fun codeFile(b: Block): File {
        val f = File(genDir(), "code_${System.currentTimeMillis()}.${extFor(b.lang)}")
        f.writeText(b.body); return f
    }

    private fun zipProject(text: String) {
        try {
            val f = File(genDir(), "project_${System.currentTimeMillis()}.zip")
            var n = 0
            ZipOutputStream(f.outputStream()).use { z ->
                parseBlocks(text).filter { it.code }.forEach { b ->
                    val name = when (extFor(b.lang)) {
                        "html" -> "index.html"; "css" -> "style.css"; "js" -> "script.js"
                        else -> "file${++n}.${extFor(b.lang)}"
                    }
                    z.putNextEntry(ZipEntry(name)); z.write(b.body.toByteArray()); z.closeEntry()
                }
            }
            saveAs(f)
        } catch (e: Exception) { toast("ZIP failed: ${e.message}") }
    }

    private fun previewHtml(b: Block) {
        val html = if (b.lang == "svg") "<html><body style='margin:0;background:#fff'>${b.body}</body></html>" else b.body
        val d = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BG) }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(6), dp(12), dp(6)); setBackgroundColor(C.SURF) }
        top.addView(tv("PREVIEW (sandboxed)", 12f, C.GREEN, true), lp(0, WRAP, 1f))
        top.addView(btn("CLOSE", C.RED) { d.dismiss() }, lp(WRAP, dp(40)))
        l.addView(top, lp(MATCH, WRAP))
        val wv = WebView(this)
        wv.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = false
            allowFileAccess = false; allowContentAccess = false
            setGeolocationEnabled(false)
        }
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?) = true
        }
        wv.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        l.addView(wv, lp(MATCH, 0, 1f))
        d.setContentView(l)
        d.setOnDismissListener { wv.destroy() }
        d.show()
    }

    private fun showFullImage(f: File) {
        val d = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val iv = ImageView(this).apply {
            setBackgroundColor(Color.BLACK); scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(decodeSampled(f.path, 1600)); setOnClickListener { d.dismiss() }
        }
        d.setContentView(iv); d.show()
    }

    private fun highlight(code: String): SpannableStringBuilder {
        val sb = SpannableStringBuilder(code)
        fun mark(re: Regex, color: Int) {
            re.findAll(code).forEach { sb.setSpan(ForegroundColorSpan(color), it.range.first, it.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        }
        mark(Regex("\\b(fun|val|var|def|class|import|from|return|if|else|elif|for|while|function|const|let|new|true|false|null|None|True|False|in|is|not|and|or|try|catch|except|object|public|private|void|int|String)\\b"), C.GOLD)
        mark(Regex("\"[^\"\\n]*\"|'[^'\\n]*'"), C.GREEN)
        mark(Regex("//[^\\n]*|(?m)^\\s*#[^\\n]*"), C.TEXT2)
        return sb
    }

    private fun styleInline(src: String): CharSequence {
        val sb = SpannableStringBuilder()
        val ex = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        val inline = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`")
        src.split("\n").forEachIndexed { idx, raw ->
            if (idx > 0) sb.append("\n")
            var line = raw
            var head = false
            val hm = Regex("^#{1,6}\\s+(.*)$").find(line)
            if (hm != null) { line = hm.groupValues[1]; head = true }
            line = line.replace(Regex("^\\s*[-*]\\s+"), "• ")
            val start = sb.length
            var last = 0
            for (m in inline.findAll(line)) {
                sb.append(line.substring(last, m.range.first))
                val s = sb.length
                if (m.groups[1] != null) {
                    sb.append(m.groupValues[1]); sb.setSpan(StyleSpan(Typeface.BOLD), s, sb.length, ex)
                } else {
                    sb.append(m.groupValues[2])
                    sb.setSpan(TypefaceSpan("monospace"), s, sb.length, ex)
                    sb.setSpan(ForegroundColorSpan(C.GREEN), s, sb.length, ex)
                }
                last = m.range.last + 1
            }
            sb.append(line.substring(last))
            if (head) {
                sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, ex)
                sb.setSpan(ForegroundColorSpan(C.GOLD), start, sb.length, ex)
            }
        }
        return sb
    }

    // ---------------------------------------------------------------- media helpers

    private fun decodeSampled(path: String, max: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        if (o.outWidth <= 0) return null
        var s = 1
        while (o.outWidth / s > max * 2 || o.outHeight / s > max * 2) s *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
    }

    private fun togglePlay(f: File, i: Int) {
        val was = playingPath
        releasePlayer()
        if (was != f.path) {
            try {
                player = MediaPlayer().apply {
                    setDataSource(f.path); prepare(); start()
                    setOnCompletionListener { runOnUiThread { releasePlayer(); refill(i) } }
                }
                playingPath = f.path
            } catch (e: Exception) { releasePlayer(); toast("Cannot play: ${e.message}") }
        }
        refill(i)
    }

    private fun releasePlayer() {
        try { player?.release() } catch (_: Exception) { }
        player = null; playingPath = null
    }

    private fun share(f: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val i = Intent(Intent.ACTION_SEND).setType(mimeOf(f)).putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(i, "Share"))
        } catch (e: Exception) { toast("Share failed: ${e.message}") }
    }

    /** Storage Access Framework: the user picks where the file is saved. */
    private fun saveAs(f: File) {
        pendingSave = f
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(mimeOf(f)).putExtra(Intent.EXTRA_TITLE, f.name)
        startActivityForResult(i, 2)
    }

    private fun pickFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        startActivityForResult(i, 1)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != Activity.RESULT_OK || uri == null) return
        if (requestCode == 2) {
            val src = pendingSave ?: return
            try {
                contentResolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
                toast("Saved")
            } catch (e: Exception) { toast("Save failed: ${e.message}") }
        } else if (requestCode == 1) {
            try {
                var name = "attachment"
                contentResolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val ix = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (ix >= 0) name = c.getString(ix) ?: name
                        val sz = c.getColumnIndex(OpenableColumns.SIZE)
                        if (sz >= 0 && !c.isNull(sz) && c.getLong(sz) > 20L * 1024 * 1024) { toast("File is larger than 20 MB — too big for this build."); return }
                    }
                }
                val dir = File(filesDir, "att/${conv.id}").apply { mkdirs() }
                val f = File(dir, "${System.currentTimeMillis()}_${name.replace('/', '_')}")
                contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { i.copyTo(it) } }
                attachFile = f; attachName = name
                attachMime = contentResolver.getType(uri) ?: mimeOf(f)
                attachLabel.text = "📎 $name  (tap to remove)"; attachLabel.visibility = View.VISIBLE
            } catch (e: Exception) { toast("Attach failed: ${e.message}") }
        }
    }

    private fun clearAttachment() {
        attachFile = null; attachName = null; attachMime = null; attachLabel.visibility = View.GONE
    }

    // ---------------------------------------------------------------- chat flow

    private fun newChat() {
        if (busy) { toast("Stop the current task first."); return }
        saveConv(); conv = Conv(); clearAttachment(); renderAll(); show(0)
    }

    private fun submit() {
        val t = input.text.toString().trim()
        if (busy || (t.isEmpty() && attachFile == null)) return
        val m = Msg("user", if (t.isEmpty()) "(attachment)" else t)
        attachFile?.let { m.attachPath = it.path; m.attachMime = attachMime; m.attachName = attachName }
        if (conv.msgs.isEmpty()) conv.title = (if (t.isNotEmpty()) t else attachName ?: "Chat").take(40)
        conv.msgs.add(m)
        input.setText(""); clearAttachment()
        addMsgView(m)
        saveConv()
        startRun()
    }

    private fun stopRun() {
        stopped = true
        holder?.cancel()
    }

    private fun dropMsg(m: Msg) {
        m.path?.let { File(it).delete() }
    }

    private fun deleteMsg(i: Int) {
        if (busy) { toast("Stop the current task first."); return }
        confirm("Delete this item${if (conv.msgs[i].path != null) " and its file" else ""}?") {
            dropMsg(conv.msgs[i]); conv.msgs.removeAt(i); renderAll(); saveConv()
        }
    }

    private fun regenerate() {
        if (busy) return
        while (conv.msgs.isNotEmpty() && conv.msgs.last().role != "user") { dropMsg(conv.msgs.last()); conv.msgs.removeAt(conv.msgs.lastIndex) }
        renderAll(); startRun()
    }

    private fun editFrom(i: Int) {
        if (busy) return
        val m = conv.msgs[i]
        while (conv.msgs.size > i) { dropMsg(conv.msgs.last()); conv.msgs.removeAt(conv.msgs.lastIndex) }
        input.setText(m.text.takeIf { it != "(attachment)" } ?: "")
        renderAll(); saveConv()
    }

    private fun detectIntent(t: String): Pair<String, Boolean> {
        val s = t.trim().lowercase()
        if (s.startsWith("/image ")) return "image" to true
        if (s.startsWith("/voice ") || s.startsWith("/tts ")) return "tts" to true
        if (s.startsWith("/video ")) return "video" to true
        val create = Regex("\\b(create|generate|make|draw|design|render|produce)\\b").containsMatchIn(s) ||
            t.contains("ဖန်တီး") || t.contains("ဆွဲ")
        if (!create) return "chat" to false
        if (Regex("\\b(html|css|javascript|website|web page|webpage|code|python|script|program|app|kotlin|json|pdf)\\b").containsMatchIn(s)) return "chat" to false
        if (Regex("\\b(video|animation|animated|cartoon|film|movie|reel)\\b").containsMatchIn(s) || t.contains("ဗီဒီယို") || t.contains("ကာတွန်း")) return "video" to false
        if (Regex("\\b(image|picture|photo|logo|poster|illustration|wallpaper|artwork)\\b").containsMatchIn(s) || t.contains("ပုံ") || t.contains("လိုဂို")) return "image" to false
        if (Regex("\\b(voiceover|voice-over|narration|speech|audio|tts)\\b").containsMatchIn(s) || t.contains("အသံ")) return "tts" to false
        return "chat" to false
    }

    private fun addNote(text: String) {
        val m = Msg("assistant", text, "note"); m.status = "done"
        conv.msgs.add(m); addMsgView(m); saveConv()
    }

    private fun startRun() {
        val p = store.active()
        if (p == null || !p.enabled || p.apiKey.isBlank() || p.baseUrl.isBlank() || p.model.isBlank()) {
            addNote("⚠ No AI provider is configured yet. Open SETTINGS and enter an API base URL, API key and chat model ID."); return
        }
        val last = conv.msgs.last { it.role == "user" }
        val (kind0, explicit) = detectIntent(last.text)
        var kind = kind0
        val imageOk = p.image && p.imageModel.isNotBlank()
        val ttsOk = p.tts && p.ttsModel.isNotBlank()
        if (kind == "image" && !imageOk) {
            if (explicit) { addNote("This provider has no image generation set up. In SETTINGS enable “image generation” and enter an image model ID (for example dall-e-3)."); return }
            kind = "chat"
        }
        if (kind == "tts" && !ttsOk) {
            if (explicit) { addNote("This provider has no text-to-speech set up. In SETTINGS enable “text-to-speech” and enter a speech model ID (for example tts-1)."); return }
            kind = "chat"
        }
        val stripped = if (explicit) last.text.replace(Regex("^/\\w+\\s+"), "") else last.text
        when (kind) {
            "image" -> runMedia(p, true, stripped)
            "tts" -> runMedia(p, false, stripped)
            "video" -> runChat(p, VIDEO_PROMPT, true)
            else -> runChat(p, null, false)
        }
    }

    private fun launchJob(h: Api.Holder, body: suspend () -> Unit) {
        setBusy(true); holder = h; stopped = false
        job = scope.launch {
            try { body() } finally {
                setBusy(false)
                for (k in maxOf(0, conv.msgs.size - 4) until conv.msgs.size) refill(k)
                saveConv()
            }
        }
    }

    private fun failMsg(m: Msg, e: Exception) {
        if (stopped) { m.status = "cancelled"; m.error = "" } else {
            m.status = "failed"
            m.error = (e.message ?: e.toString()).let {
                if (e is ApiError) it else "Network problem: $it. Check your connection and the API base URL."
            }
        }
    }

    private fun runChat(p: Provider, extra: String?, videoNote: Boolean) {
        val api = buildApi(p, extra)
        val m = Msg("assistant", "", "text", "processing")
        conv.msgs.add(m); addMsgView(m)
        val idx = conv.msgs.size - 1
        val h = Api.Holder()
        launchJob(h) {
            var lastDraw = 0L
            try {
                val out = withContext(Dispatchers.IO) {
                    Api.chat(p, api, h) { t ->
                        runOnUiThread {
                            m.text = t
                            val n = SystemClock.uptimeMillis()
                            if (n - lastDraw > 120) { lastDraw = n; refill(idx); scrollDown() }
                        }
                    }
                }
                m.text = out; m.status = "done"
                if (videoNote) addNote("🎬 Video rendering is not available in this build: it has no video-generation provider or render engine yet. Above is a script + storyboard you can use with a video-generation service.")
            } catch (e: Exception) {
                if (m.text.isNotEmpty() && stopped) m.status = "cancelled" else failMsg(m, e)
            }
            refill(idx)
        }
    }

    private fun buildApi(p: Provider, extra: String?): org.json.JSONArray {
        val arr = org.json.JSONArray()
        arr.put(org.json.JSONObject().put("role", "system").put("content", SYSTEM_PROMPT + (extra ?: "")))
        val hist = conv.msgs.filter {
            (it.role == "user" || (it.role == "assistant" && it.type == "text")) &&
                (it.status == "" || it.status == "done") && it.text.isNotBlank()
        }.takeLast(24)
        hist.forEachIndexed { i, m ->
            if (m.role == "user" && i == hist.lastIndex && m.attachPath != null) arr.put(userWithAttachment(p, m))
            else arr.put(org.json.JSONObject().put("role", m.role).put("content", m.text))
        }
        return arr
    }

    private fun userWithAttachment(p: Provider, m: Msg): org.json.JSONObject {
        val f = File(m.attachPath!!)
        val mime = m.attachMime ?: ""
        val o = org.json.JSONObject().put("role", "user")
        val name = m.attachName ?: f.name
        if (mime.startsWith("image/") && p.vision && f.exists()) {
            val b = decodeSampled(f.path, 1024)
            if (b != null) {
                val bo = ByteArrayOutputStream(); b.compress(Bitmap.CompressFormat.JPEG, 85, bo)
                val url = "data:image/jpeg;base64," + Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP)
                val parts = org.json.JSONArray()
                    .put(org.json.JSONObject().put("type", "text").put("text", m.text))
                    .put(org.json.JSONObject().put("type", "image_url").put("image_url", org.json.JSONObject().put("url", url)))
                return o.put("content", parts)
            }
        }
        val textual = mime.startsWith("text/") || mime.contains("json") || mime.contains("xml") || mime.contains("javascript") ||
            f.extension.lowercase() in setOf("txt", "md", "csv", "json", "html", "css", "js", "py", "kt", "java", "xml", "log", "yml", "yaml")
        val c = if (textual && f.exists()) m.text + "\n\n[Attached file: $name]\n" + f.readText().take(20000)
        else m.text + "\n\n[The user attached '$name' ($mime). This app version cannot read this file type${if (mime.startsWith("image/")) " (vision is disabled for this provider)" else ""}; tell the user honestly.]"
        return o.put("content", c)
    }

    private fun runMedia(p: Provider, image: Boolean, prompt: String) {
        val m = Msg("assistant", "", if (image) "image" else "audio", "processing")
        m.prompt = prompt
        conv.msgs.add(m); addMsgView(m)
        val idx = conv.msgs.size - 1
        val h = Api.Holder()
        launchJob(h) {
            try {
                if (filesDir.usableSpace < 30L * 1024 * 1024) throw ApiError("Not enough free storage (need at least 30 MB).")
                val f = withContext(Dispatchers.IO) {
                    val out = if (image) Api.retry(2) { Api.image(p, prompt, genDir(), h) }
                    else Api.retry(2) { Api.speech(p, prompt, genDir(), h) }
                    verify(out, image)
                    out
                }
                m.path = f.path; m.mime = mimeOf(f); m.status = "done"
            } catch (e: Exception) { failMsg(m, e) }
            refill(idx)
        }
    }

    /** Confirms the generated file really is a decodable image / playable audio. */
    private fun verify(f: File, image: Boolean) {
        if (image) {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            if (o.outWidth <= 0) { f.delete(); throw ApiError("The provider returned data that is not a valid image.") }
        } else {
            val mp = MediaPlayer()
            try {
                mp.setDataSource(f.path); mp.prepare()
                if (mp.duration <= 0) throw ApiError("The generated audio is empty.")
            } catch (e: ApiError) { f.delete(); throw e }
            catch (e: Exception) { f.delete(); throw ApiError("The generated audio is not playable.") }
            finally { mp.release() }
        }
    }

    // ---------------------------------------------------------------- history page

    private fun buildHistory(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), 0) }
        val search = EditText(this).apply {
            hint = "Search conversations…"; setTextColor(C.TEXT); setHintTextColor(0xFF5A6472.toInt()); textSize = 14f
            setSingleLine(true); background = box(C.SURF2, C.DIV, 8); setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        root.addView(search, lp(MATCH, WRAP))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(16)) }
        val sv = ScrollView(this); sv.addView(list)
        root.addView(sv, lp(MATCH, 0, 1f))
        val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)

        fun refresh() {
            val q = search.text.toString().trim()
            list.removeAllViews()
            val items = convStore.list().filter { ci ->
                q.isEmpty() || ci.title.contains(q, true) || convStore.load(ci.id)?.msgs?.any { it.text.contains(q, true) } == true
            }
            if (items.isEmpty()) list.addView(tv(if (q.isEmpty()) "No saved conversations yet." else "No matches.", 13f, C.TEXT2).apply { setPadding(0, dp(16), 0, 0) })
            items.forEach { ci ->
                val r = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    background = box(C.SURF2, if (ci.id == conv.id) C.GOLD else C.DIV, 8); setPadding(dp(12), dp(10), dp(8), dp(10))
                }
                val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                info.addView(tv(ci.title, 14f, C.TEXT, true).apply { maxLines = 1 })
                info.addView(tv("${fmt.format(Date(ci.updated))} · ${ci.count} items", 11f, C.TEXT2))
                r.addView(info, lp(0, WRAP, 1f))
                r.addView(btn("⋮", C.GOLD) {
                    AlertDialog.Builder(this).setItems(arrayOf("Rename", "Delete")) { _, w ->
                        if (w == 0) renameDialog(ci) { refresh() } else {
                            if (busy && ci.id == conv.id) toast("Stop the current task first.")
                            else confirm("Delete “${ci.title}” and its generated files?") {
                                convStore.delete(ci.id)
                                if (ci.id == conv.id) { conv = Conv(); renderAll() }
                                refresh()
                            }
                        }
                    }.show()
                }, lp(dp(44), dp(44)))
                r.setOnClickListener {
                    if (busy) { toast("Stop the current task first.") } else {
                        saveConv()
                        conv = convStore.load(ci.id) ?: return@setOnClickListener
                        clearAttachment(); renderAll(); show(0)
                    }
                }
                list.addView(r, lp(MATCH, WRAP).apply { bottomMargin = dp(8) })
            }
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { refresh() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        saveConv()
        refresh()
        return root
    }

    private fun renameDialog(ci: ConvInfo, done: () -> Unit) {
        val e = EditText(this).apply { setText(ci.title); setSingleLine(true) }
        AlertDialog.Builder(this).setTitle("Rename").setView(e).setNegativeButton("Cancel", null)
            .setPositiveButton("OK") { _, _ ->
                val t = e.text.toString().trim().ifEmpty { ci.title }
                if (ci.id == conv.id) { conv.title = t; saveConv() } else convStore.rename(ci.id, t)
                done()
            }.show()
    }

    // ---------------------------------------------------------------- files page

    private fun buildFiles(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), 0) }
        val genRoot = File(filesDir, "gen")
        val files = genRoot.walkTopDown().filter { it.isFile }.sortedByDescending { it.lastModified() }.toList()
        val total = files.sumOf { it.length() }
        root.addView(tv("GENERATED FILES", 12f, C.GOLD, true))
        root.addView(tv("${files.size} files · ${fmtSize(total)} · free storage ${fmtSize(filesDir.usableSpace)}", 11f, C.TEXT2))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(16)) }
        val sv = ScrollView(this); sv.addView(list)
        root.addView(sv, lp(MATCH, 0, 1f))
        if (files.isEmpty()) list.addView(tv("Nothing here yet. Images, voiceovers, code files and ZIPs you generate appear here.", 13f, C.TEXT2))
        val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        files.forEach { f ->
            val r = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = box(C.SURF2, C.DIV, 8); setPadding(dp(12), dp(10), dp(12), dp(10)) }
            r.addView(tv(f.name, 13f, C.TEXT, true))
            r.addView(tv("${fmtSize(f.length())} · ${fmt.format(Date(f.lastModified()))}", 11f, C.TEXT2))
            val b = mutableListOf<View>(btn("SAVE", C.GOLD) { saveAs(f) }, btn("SHARE", C.BLUE) { share(f) })
            if (f.extension == "html") b.add(btn("PREVIEW", C.GREEN) { previewHtml(Block(true, "html", f.readText())) })
            b.add(btn("DELETE", C.RED) { confirm("Delete ${f.name}?") { f.delete(); show(2) } })
            r.addView(row(*b.toTypedArray()))
            list.addView(r, lp(MATCH, WRAP).apply { bottomMargin = dp(8) })
        }
        return root
    }

    // ---------------------------------------------------------------- settings page

    private fun field(parent: LinearLayout, label: String, hint: String, pw: Boolean = false): EditText {
        parent.addView(tv(label, 11f, C.TEXT2).apply { setPadding(0, dp(10), 0, dp(2)) })
        val e = EditText(this).apply {
            setTextColor(C.TEXT); setHintTextColor(0xFF5A6472.toInt()); this.hint = hint; textSize = 14f
            background = box(C.SURF2, C.DIV, 6); setPadding(dp(10), dp(8), dp(10), dp(8))
            inputType = InputType.TYPE_CLASS_TEXT or (if (pw) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_URI)
            setSingleLine(true)
        }
        parent.addView(e, lp(MATCH, WRAP))
        return e
    }

    private fun check(parent: LinearLayout, label: String) = CheckBox(this).apply {
        text = label; setTextColor(C.TEXT); textSize = 13f; buttonTintList = ColorStateList.valueOf(C.GOLD)
        parent.addView(this, lp(MATCH, WRAP))
    }

    private fun buildSettings(): View {
        val provs = store.providers()
        if (provs.isEmpty()) provs.add(Provider())
        var cur = provs.firstOrNull { it.id == store.activeId } ?: provs.first()

        val sv = ScrollView(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(28)) }
        sv.addView(col)
        col.addView(tv("AI PROVIDERS", 13f, C.GOLD, true))
        col.addView(tv("OpenAI-compatible APIs only (no local AI). API keys are stored encrypted with the Android Keystore.", 12f, C.TEXT2))

        val pick = btn("", C.GOLD) {}
        col.addView(pick, lp(MATCH, dp(44)).apply { topMargin = dp(10) })
        val fName = field(col, "Provider name", "My provider")
        val fUrl = field(col, "API base URL (https)", "https://api.openai.com/v1")
        val fKey = field(col, "API key", "sk-…", true)
        val fModel = field(col, "Chat model ID", "gpt-4o-mini")
        val fImg = field(col, "Image model ID (optional)", "dall-e-3")
        val fTts = field(col, "Speech model ID (optional)", "tts-1")
        val fVoice = field(col, "Speech voice", "alloy")
        val cbOn = check(col, "Provider enabled")
        val cbVis = check(col, "Supports image understanding (vision)")
        val cbImg = check(col, "Supports image generation")
        val cbTts = check(col, "Supports text-to-speech")
        col.addView(tv("Video generation and speech-to-text are not available in this build.", 11f, C.TEXT2))
        val status = tv("", 12f, C.GREEN).apply { setPadding(0, dp(8), 0, dp(4)) }

        fun load(p: Provider) {
            fName.setText(p.name); fUrl.setText(p.baseUrl); fKey.setText(p.apiKey); fModel.setText(p.model)
            fImg.setText(p.imageModel); fTts.setText(p.ttsModel); fVoice.setText(p.ttsVoice)
            cbOn.isChecked = p.enabled; cbVis.isChecked = p.vision; cbImg.isChecked = p.image; cbTts.isChecked = p.tts
            styleBtn(pick, "PROVIDER: ${p.name}  ▾", C.GOLD)
        }
        fun collect(p: Provider) {
            p.name = fName.text.toString().trim().ifEmpty { "Provider" }; p.baseUrl = fUrl.text.toString().trim()
            p.apiKey = fKey.text.toString().trim(); p.model = fModel.text.toString().trim()
            p.imageModel = fImg.text.toString().trim(); p.ttsModel = fTts.text.toString().trim()
            p.ttsVoice = fVoice.text.toString().trim().ifEmpty { "alloy" }
            p.enabled = cbOn.isChecked; p.vision = cbVis.isChecked; p.image = cbImg.isChecked; p.tts = cbTts.isChecked
        }
        fun say(s: String, ok: Boolean) { status.text = s; status.setTextColor(if (ok) C.GREEN else C.RED) }
        load(cur)

        pick.setOnClickListener {
            collect(cur)
            AlertDialog.Builder(this).setItems(provs.map { (if (it.id == cur.id) "✓ " else "") + it.name }.toTypedArray()) { _, w ->
                cur = provs[w]; load(cur)
            }.show()
        }

        val presets = listOf(
            "OpenAI" to "https://api.openai.com/v1", "OpenRouter" to "https://openrouter.ai/api/v1",
            "Groq" to "https://api.groq.com/openai/v1", "Google Gemini (OpenAI-compatible)" to "https://generativelanguage.googleapis.com/v1beta/openai",
            "Custom" to ""
        )
        val b1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        b1.addView(btn("SAVE", C.GREEN) {
            collect(cur)
            if (!cur.baseUrl.startsWith("https://")) { say("Base URL must start with https://", false); return@btn }
            store.save(provs); store.activeId = cur.id
            say("Saved ✔  Active provider: ${cur.name}", true)
        }, lp(0, dp(44), 1f).apply { marginEnd = dp(6) })
        b1.addView(btn("NEW", C.BLUE) {
            collect(cur)
            AlertDialog.Builder(this).setTitle("New provider").setItems(presets.map { it.first }.toTypedArray()) { _, w ->
                val p = Provider(name = presets[w].first, baseUrl = presets[w].second)
                provs.add(p); cur = p; load(p)
            }.show()
        }, lp(0, dp(44), 1f).apply { marginEnd = dp(6) })
        b1.addView(btn("DELETE", C.RED) {
            confirm("Delete provider “${cur.name}”?") {
                provs.remove(cur); if (provs.isEmpty()) provs.add(Provider())
                cur = provs.first(); store.save(provs); store.activeId = cur.id; load(cur); say("Deleted.", true)
            }
        }, lp(0, dp(44), 1f))
        col.addView(b1, lp(MATCH, WRAP).apply { topMargin = dp(12) })

        val b2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        b2.addView(btn("TEST CONNECTION", C.BLUE) {
            collect(cur)
            if (cur.baseUrl.isBlank() || cur.apiKey.isBlank()) { say("Enter base URL and API key first.", false); return@btn }
            say("Testing…", true)
            val snap = cur.copy()
            scope.launch {
                try {
                    val ids = withContext(Dispatchers.IO) { Api.listModels(snap) }
                    say("Connected ✔  ${ids.size} models available.", true)
                } catch (e: Exception) {
                    StudioLog.add(this@MainActivity, "Provider connection", snap.name, snap.model, "ERROR", e.message.orEmpty())
                    say("Failed: ${e.message}. You can still type a model ID manually.", false)
                }
            }
        }, lp(0, dp(44), 1f).apply { marginEnd = dp(6) })
        b2.addView(btn("LOAD MODELS", C.GOLD) {
            collect(cur)
            if (cur.baseUrl.isBlank() || cur.apiKey.isBlank()) { say("Enter base URL and API key first.", false); return@btn }
            say("Loading models…", true)
            val snap = cur.copy()
            scope.launch {
                try {
                    val ids = withContext(Dispatchers.IO) { Api.listModels(snap) }
                    if (ids.isEmpty()) { say("The provider returned no models. Enter a model ID manually.", false); return@launch }
                    val caps = Api.detect(ids)
                    if (caps.imageModel != null && fImg.text.isBlank()) { fImg.setText(caps.imageModel); cbImg.isChecked = true }
                    if (caps.ttsModel != null && fTts.text.isBlank()) { fTts.setText(caps.ttsModel); cbTts.isChecked = true }
                    if (caps.vision) cbVis.isChecked = true
                    say("${ids.size} models loaded. Capabilities were guessed from model names — review the checkboxes.", true)
                    val shown = ids.take(300)
                    AlertDialog.Builder(this@MainActivity).setTitle("Choose chat model").setItems(shown.toTypedArray()) { _, w -> fModel.setText(shown[w]) }.show()
                } catch (e: Exception) {
                    StudioLog.add(this@MainActivity, "Load models", snap.name, snap.model, "ERROR", e.message.orEmpty())
                    say("Failed: ${e.message}", false)
                }
            }
        }, lp(0, dp(44), 1f))
        col.addView(b2, lp(MATCH, WRAP).apply { topMargin = dp(8) })
        col.addView(status)

        col.addView(View(this).apply { setBackgroundColor(C.DIV) }, lp(MATCH, dp(1)).apply { topMargin = dp(12); bottomMargin = dp(12) })
        col.addView(tv("STORAGE & PRIVACY", 13f, C.GOLD, true))
        col.addView(btn("CLEAR GENERATED FILES", C.RED) {
            if (busy) toast("Stop the current task first.") else confirm("Delete all generated files? Chat text is kept.") {
                File(filesDir, "gen").deleteRecursively(); renderAll(); toast("Done")
            }
        }, lp(MATCH, dp(44)).apply { topMargin = dp(8) })
        col.addView(btn("DELETE ALL CHATS & FILES", C.RED) {
            if (busy) toast("Stop the current task first.") else confirm("Delete ALL conversations and generated files?") {
                convStore.deleteAll(); File(filesDir, "att").deleteRecursively(); conv = Conv(); renderAll(); toast("Done")
            }
        }, lp(MATCH, dp(44)).apply { topMargin = dp(8) })
        col.addView(btn("DIAGNOSTICS", C.BLUE) { diagnostics() }, lp(MATCH, dp(44)).apply { topMargin = dp(8) })
        col.addView(btn("ERROR & HISTORY LOG", C.GOLD) { showStudioLogs() }, lp(MATCH, dp(44)).apply { topMargin = dp(8) })

        col.addView(View(this).apply { setBackgroundColor(C.DIV) }, lp(MATCH, dp(1)).apply { topMargin = dp(14); bottomMargin = dp(12) })
        val about = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        about.addView(ImageView(this).apply { setImageResource(R.drawable.logo_gi) }, lp(dp(96), dp(96)))
        about.addView(tv("G.I KO KO AI", 18f, C.GOLD, true))
        about.addView(tv("CREATE BEYOND LIMITS", 11f, C.GREEN, true))
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "" }
        about.addView(tv("Version $ver", 11f, C.TEXT2))
        about.addView(tv("POWERED BY I KO KO", 11f, C.GOLD, true).apply { setPadding(0, dp(6), 0, 0) })
        col.addView(about, lp(MATCH, WRAP))
        return sv
    }

    private fun showStudioLogs() {
        val entries = StudioLog.entries(this)
        val message = if (entries.isEmpty()) "No studio events have been recorded yet." else entries.take(30).joinToString("\n\n").take(9000)
        AlertDialog.Builder(this).setTitle("ERROR & HISTORY LOG")
            .setMessage(message)
            .setNeutralButton("COPY") { _, _ -> copy(message) }
            .setNegativeButton("CLEAR") { _, _ ->
                confirm("Clear all saved error and history entries?") { StudioLog.clear(this); toast("Log cleared.") }
            }
            .setPositiveButton("CLOSE", null).show()
    }

    private fun diagnostics() {
        val p = store.active()
        val s = "App: $packageName\nAndroid: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\nDevice: ${Build.MODEL}\n" +
            "ABIs: ${Build.SUPPORTED_ABIS.joinToString()}\nJVM heap limit: ${Runtime.getRuntime().maxMemory() / 1048576} MB\n" +
            "Free storage: ${fmtSize(filesDir.usableSpace)}\nActive provider: ${p?.name ?: "none"}\n" +
            "Capabilities: chat" + (if (p?.vision == true) ", vision" else "") + (if (p?.image == true) ", image" else "") + (if (p?.tts == true) ", tts" else "")
        AlertDialog.Builder(this).setTitle("Diagnostics").setMessage(s).setPositiveButton("OK", null).show()
    }
}
