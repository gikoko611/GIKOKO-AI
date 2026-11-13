package com.ikoko.gikokoai

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Multi-provider AI animation authoring with an offline WebView preview and native H.264 MP4 export. */
class AnimationStudioActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val store by lazy { Store(this) }
    private val providers by lazy { store.providers().filter { it.enabled && it.apiKey.isNotBlank() } }
    private lateinit var web: WebView
    private lateinit var prompt: EditText
    private lateinit var html: EditText
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var directorModel: EditText
    private lateinit var codeModel: EditText
    private lateinit var reviewModel: EditText
    private lateinit var directorProvider: TextView
    private lateinit var codeProvider: TextView
    private lateinit var reviewProvider: TextView
    private var director = store.active()
    private var coder = store.active()
    private var reviewer = store.active()
    private var exportFile: File? = null
    private val running = AtomicBoolean(false)
    private val requestExport = 501
    private val requestSaveHtml = 502

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun text(s: String, size: Float = 13f, color: Int = C.TEXT, bold: Boolean = false) =
        TextView(this).apply { text = s; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD) }
    private fun button(s: String, color: Int = C.GOLD, action: () -> Unit) =
        TextView(this).apply {
            text = s; textSize = 12f; gravity = Gravity.CENTER; setTextColor(color)
            setPadding(dp(10), dp(11), dp(10), dp(11))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(C.SURF2); cornerRadius = dp(8).toFloat(); setStroke(dp(1), color)
            }
            setOnClickListener { action() }
        }
    private fun field(hintText: String, value: String = "", multiline: Boolean = false) =
        EditText(this).apply {
            hint = hintText; setText(value); setTextColor(C.TEXT); setHintTextColor(C.TEXT2); textSize = 13f
            setPadding(dp(10), dp(9), dp(10), dp(9))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(C.SURF2); cornerRadius = dp(8).toFloat(); setStroke(dp(1), C.DIV)
            }
            if (multiline) { minLines = 4; maxLines = 10; gravity = Gravity.TOP; isSingleLine = false }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        director = store.active(); coder = store.active(); reviewer = store.active()
        buildPage()
        loadRoles()
        intent.getStringExtra("brief")?.let { prompt.setText(it) }
        showPreview()
    }

    private fun buildPage() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BG) }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10)); setBackgroundColor(C.SURF) }
        header.addView(text("G.I KO KO AI  /  ANIMATION STUDIO", 15f, C.GOLD, true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(button("CLOSE", C.TEXT2) { finish() })
        root.addView(header)
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(24)) }
        body.addView(text("MULTI-MODEL AI PIPELINE", 14f, C.GOLD, true))
        body.addView(text("Choose separate providers and model IDs for planning, code generation and code review. Any configured OpenAI-compatible provider can be used.", 12f, C.TEXT2))
        directorProvider = button("DIRECTOR PROVIDER", C.BLUE) { chooseProvider("director") }
        directorModel = field("Director model ID")
        roleBlock(body, "01 • DIRECTOR MODEL", directorProvider, directorModel)
        codeProvider = button("CODE PROVIDER", C.BLUE) { chooseProvider("code") }
        codeModel = field("Code generation model ID")
        roleBlock(body, "02 • CODE GENERATOR MODEL", codeProvider, codeModel)
        reviewProvider = button("REVIEW PROVIDER", C.BLUE) { chooseProvider("review") }
        reviewModel = field("Code review model ID")
        roleBlock(body, "03 • REVIEW / FIX MODEL", reviewProvider, reviewModel)

        body.addView(text("\nVIDEO BRIEF", 14f, C.GOLD, true))
        prompt = field("Describe the animation or video you want…", "", true)
        body.addView(prompt, LinearLayout.LayoutParams(-1, dp(112)).apply { topMargin = dp(6) })
        val createRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        createRow.addView(button("CREATE • PLAN → CODE → REVIEW", C.GREEN) { createWithAi() }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(6) })
        createRow.addView(button("PREVIEW", C.GOLD) { showPreview() })
        body.addView(createRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        body.addView(text("\nHTML / JAVASCRIPT ANIMATION CODE", 14f, C.GOLD, true))
        html = field("AI-generated self-contained HTML will appear here…", "", true)
        body.addView(html, LinearLayout.LayoutParams(-1, dp(220)).apply { topMargin = dp(6) })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button("UPDATE PREVIEW", C.BLUE) { showPreview() }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(5) })
        actions.addView(button("SAVE HTML", C.GOLD) { saveHtml() }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(5) })
        actions.addView(button("EXPORT MP4", C.GREEN) { exportMp4() }, LinearLayout.LayoutParams(0, dp(44), 1f))
        body.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        body.addView(text("\nLIVE ANIMATION PREVIEW", 14f, C.GOLD, true))
        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            webChromeClient = WebChromeClient()
            isVerticalScrollBarEnabled = false
        }
        body.addView(web, LinearLayout.LayoutParams(-1, dp(320)).apply { topMargin = dp(6) })
        progress = ProgressBar(this).apply { visibility = View.GONE; isIndeterminate = true }
        body.addView(progress)
        status = text("Ready • HTML preview runs locally. MP4 export uses Android H.264 encoder.", 12f, C.TEXT2)
        body.addView(status)
        body.addView(text("\nNote: remote image/audio/video APIs depend on the selected provider. Rendering of the generated HTML is local; no cloud renderer is required.", 11f, C.TEXT2))
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun roleBlock(parent: LinearLayout, title: String, providerButton: TextView, modelField: EditText) {
        parent.addView(text("\n$title", 12f, C.TEXT2, true))
        parent.addView(providerButton, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(5) })
        parent.addView(modelField, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(5) })
    }

    private fun loadRoles() {
        val prefs = getSharedPreferences("gi_animation_roles", MODE_PRIVATE)
        val j = try { JSONObject(prefs.getString("roles", "{}")) } catch (_: Exception) { JSONObject() }
        fun load(role: String, default: Provider?): Pair<Provider?, String> {
            val item = j.optJSONObject(role)
            val p = providers.firstOrNull { it.id == item?.optString("providerId") } ?: default
            return p to (item?.optString("model")?.takeIf { it.isNotBlank() } ?: p?.model.orEmpty())
        }
        val d = load("director", director); director = d.first; directorModel.setText(d.second)
        val c = load("code", coder); coder = c.first; codeModel.setText(c.second)
        val r = load("review", reviewer); reviewer = r.first; reviewModel.setText(r.second)
        refreshRoleLabels()
    }

    private fun saveRoles() {
        val j = JSONObject()
        j.put("director", JSONObject().put("providerId", director?.id ?: "").put("model", directorModel.text.toString().trim()))
        j.put("code", JSONObject().put("providerId", coder?.id ?: "").put("model", codeModel.text.toString().trim()))
        j.put("review", JSONObject().put("providerId", reviewer?.id ?: "").put("model", reviewModel.text.toString().trim()))
        getSharedPreferences("gi_animation_roles", MODE_PRIVATE).edit().putString("roles", j.toString()).apply()
    }

    private fun refreshRoleLabels() {
        directorProvider.text = "DIRECTOR: ${director?.name ?: "No provider"}  ▾"
        codeProvider.text = "CODE: ${coder?.name ?: "No provider"}  ▾"
        reviewProvider.text = "REVIEW: ${reviewer?.name ?: "No provider"}  ▾"
    }

    private fun chooseProvider(role: String) {
        if (providers.isEmpty()) { toast("Add and enable an API provider in Settings first."); return }
        val names = providers.map { it.name + "  (" + it.baseUrl + ")" }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Select $role provider").setItems(names) { _, which ->
            when (role) {
                "director" -> { director = providers[which]; if (directorModel.text.isBlank()) directorModel.setText(director?.model) }
                "code" -> { coder = providers[which]; if (codeModel.text.isBlank()) codeModel.setText(coder?.model) }
                else -> { reviewer = providers[which]; if (reviewModel.text.isBlank()) reviewModel.setText(reviewer?.model) }
            }
            refreshRoleLabels(); saveRoles()
        }.show()
    }

    private fun modelFor(role: String): Pair<Provider, String>? {
        val p = when (role) { "director" -> director; "code" -> coder; else -> reviewer } ?: return null
        val model = when (role) { "director" -> directorModel.text; "code" -> codeModel.text; else -> reviewModel.text }.toString().trim().ifBlank { p.model }
        if (p.apiKey.isBlank() || p.baseUrl.isBlank() || model.isBlank()) return null
        return p.copy(model = model) to model
    }

    private suspend fun ask(role: String, system: String, user: String): String {
        val choice = modelFor(role) ?: throw IllegalStateException("Configure provider, API key and model ID for $role.")
        val (p, model) = choice
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", user))
        return withContext(Dispatchers.IO) { Api.chat(p, messages, Api.Holder()) {} }.also {
            StudioLog.add(this@AnimationStudioActivity, "AI $role", p.name, model, "SUCCESS", "Response received.")
        }
    }

    private fun extractHtml(raw: String): String {
        val match = Regex("(?is)\\x60\\x60\\x60(?:html)?\\s*(.*?)\\x60\\x60\\x60").find(raw)
        return (match?.groupValues?.get(1) ?: raw).trim()
    }

    private fun createWithAi() {
        val brief = prompt.text.toString().trim()
        if (brief.isBlank()) { toast("Describe your animation first."); return }
        if (!running.compareAndSet(false, true)) return
        saveRoles(); setBusy(true, "Director is planning scenes…")
        scope.launch {
            try {
                val plan = ask("director",
                    "You are a video director. Create a concise timed scene plan for a 10-second animation. Return scene timings, visuals, camera, motion, title/subtitle and audio cues. Keep it feasible for HTML Canvas/WebView.",
                    brief)
                setBusy(true, "Code model is building the animation…")
                val generated = ask("code",
                    "You are a senior creative-coding engineer. Return exactly one complete self-contained HTML document in a single html code block. It must animate continuously with HTML Canvas or CSS, fit a 480x854 portrait canvas, use no external dependencies or network assets, and include responsive sizing. The result must run in Android WebView. Do not return a storyboard.",
                    "USER BRIEF:\n$brief\n\nDIRECTOR PLAN:\n$plan")
                var result = extractHtml(generated)
                if (!result.contains("<html", true) && !result.contains("<canvas", true)) {
                    throw IllegalStateException("The code model did not return a complete HTML animation.")
                }
                setBusy(true, "Review model is checking and repairing the HTML…")
                try {
                    val reviewed = ask("review",
                        "You are a strict HTML animation code reviewer. Find and fix syntax/runtime issues. Return only the complete corrected self-contained HTML document in one html code block. Preserve the intended animation.",
                        result)
                    val fixed = extractHtml(reviewed)
                    if (fixed.contains("<html", true) || fixed.contains("<canvas", true)) result = fixed
                } catch (reviewError: Exception) {
                    StudioLog.add(this@AnimationStudioActivity, "AI review", reviewer?.name.orEmpty(), reviewModel.text.toString(), "WARNING", reviewError.message.orEmpty())
                }
                html.setText(result)
                showPreview()
                setBusy(false, "Animation generated and preview loaded.")
                StudioLog.add(this@AnimationStudioActivity, "Animation pipeline", coder?.name.orEmpty(), codeModel.text.toString(), "SUCCESS", "Plan → code → review completed.")
            } catch (e: Exception) {
                setBusy(false, "Generation failed: ${e.message}")
                StudioLog.add(this@AnimationStudioActivity, "Animation pipeline", coder?.name.orEmpty(), codeModel.text.toString(), "ERROR", e.message.orEmpty())
            } finally { running.set(false) }
        }
    }

    private fun showPreview() {
        val source = html.text.toString().trim().ifBlank { SAMPLE_HTML }
        web.loadDataWithBaseURL("https://gikoko.local/", source, "text/html", "UTF-8", null)
        status.text = "Preview loaded locally."
        StudioLog.add(this, "Animation preview", "", "", "SUCCESS", "HTML loaded in WebView.")
    }

    private fun exportMp4() {
        if (running.get()) { toast("Wait for the current task to finish."); return }
        val width = 480
        val height = 854
        val fps = 24
        val seconds = 8
        val output = File(cacheDir, "gikoko_animation_${System.currentTimeMillis()}.mp4")
        running.set(true)
        setBusy(true, "Preparing H.264 MP4 renderer…")
        scope.launch {
            var encoder: Mp4CanvasEncoder? = null
            try {
                encoder = withContext(Dispatchers.IO) { Mp4CanvasEncoder(output, width, height, fps) }
                val total = seconds * fps
                for (frame in 0 until total) {
                    val bitmap = withContext(Dispatchers.Main) {
                        delay(1000L / fps)
                        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bmp)
                        canvas.drawColor(Color.BLACK)
                        val vw = web.width.coerceAtLeast(1)
                        val vh = web.height.coerceAtLeast(1)
                        canvas.save()
                        canvas.scale(width.toFloat() / vw, height.toFloat() / vh)
                        web.draw(canvas)
                        canvas.restore()
                        bmp
                    }
                    withContext(Dispatchers.IO) { encoder?.writeFrame(bitmap, frame.toLong() * 1_000_000L / fps) }
                    bitmap.recycle()
                    status.text = "Rendering MP4… ${frame + 1}/$total frames"
                }
                withContext(Dispatchers.IO) { encoder?.finish() }
                exportFile = output
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("video/mp4").putExtra(Intent.EXTRA_TITLE, "GIKOKOAI-animation.mp4")
                startActivityForResult(intent, requestExport)
                setBusy(false, "MP4 rendered. Choose where to save it.")
                StudioLog.add(this@AnimationStudioActivity, "MP4 render", "", "", "SUCCESS", "480x854, 24fps, 8 seconds, H.264.")
            } catch (e: Exception) {
                try { withContext(Dispatchers.IO) { encoder?.abort() } } catch (_: Exception) { }
                setBusy(false, "MP4 export failed: ${e.message}")
                StudioLog.add(this@AnimationStudioActivity, "MP4 render", "", "", "ERROR", e.message.orEmpty())
            } finally { running.set(false) }
        }
    }

    private fun saveHtml() {
        val source = html.text.toString().trim()
        if (source.isBlank()) { toast("There is no HTML to save."); return }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/html").putExtra(Intent.EXTRA_TITLE, "GIKOKOAI-animation.html")
        startActivityForResult(intent, requestSaveHtml)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == requestSaveHtml && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.openOutputStream(uri)?.use { it.write(html.text.toString().toByteArray(Charsets.UTF_8)) }
                    ?: throw IllegalStateException("Cannot open selected destination.")
                toast("HTML saved successfully.")
                StudioLog.add(this, "HTML save", "", "", "SUCCESS", "HTML file exported.")
            } catch (e: Exception) {
                toast("HTML save failed: ${e.message}")
                StudioLog.add(this, "HTML save", "", "", "ERROR", e.message.orEmpty())
            }
        }
        if (requestCode == requestExport && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val src = exportFile ?: throw IllegalStateException("Rendered MP4 is missing.")
                        contentResolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
                            ?: throw IllegalStateException("Cannot open selected destination.")
                        src.delete()
                    }
                    setBusy(false, "MP4 saved successfully.")
                } catch (e: Exception) {
                    setBusy(false, "Save failed: ${e.message}")
                    StudioLog.add(this@AnimationStudioActivity, "MP4 save", "", "", "ERROR", e.message.orEmpty())
                }
            }
        }
    }

    private fun setBusy(busy: Boolean, message: String) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        status.text = message
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        try { web.stopLoading(); web.destroy() } catch (_: Exception) { }
        scope.cancel()
        super.onDestroy()
    }

    private val SAMPLE_HTML = """
<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>
*{box-sizing:border-box}html,body{margin:0;width:100%;height:100%;overflow:hidden;background:#080b10;color:#f4f6fa;font-family:Arial,sans-serif}
canvas{width:100%;height:100%;display:block}
</style></head><body><canvas id="c"></canvas><script>
const c=document.getElementById('c'),x=c.getContext('2d');let t=0;
function draw(){const w=c.width=c.clientWidth*devicePixelRatio,h=c.height=c.clientHeight*devicePixelRatio;x.fillStyle='#080b10';x.fillRect(0,0,w,h);
const g=x.createRadialGradient(w*.5,h*.42,10,w*.5,h*.42,w*.7);g.addColorStop(0,'#46351a');g.addColorStop(1,'#080b10');x.fillStyle=g;x.fillRect(0,0,w,h);
x.save();x.translate(w*.5,h*.46);x.rotate(Math.sin(t*.02)*.12);x.strokeStyle='#d6ae55';x.lineWidth=5*devicePixelRatio;x.shadowColor='#d6ae55';x.shadowBlur=24*devicePixelRatio;x.beginPath();x.arc(0,0,Math.min(w,h)*(.18+Math.sin(t*.03)*.02),0,Math.PI*2);x.stroke();x.restore();
x.textAlign='center';x.fillStyle='#f4d28b';x.font='bold '+(w*.075)+'px Arial';x.fillText('G.I KO KO AI',w/2,h*.78);x.fillStyle='#39ff88';x.font=(w*.035)+'px Arial';x.fillText('CREATE BEYOND LIMITS',w/2,h*.83);t++;requestAnimationFrame(draw)}
draw();
</script></body></html>
"""
}
