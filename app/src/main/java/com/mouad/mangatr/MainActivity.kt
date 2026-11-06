package com.mouad.mangatr

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var overlay: FrameLayout
    private lateinit var status: TextView

    // إذا ظهر خطأ 404 من Gemini غيّر اسم النموذج هنا
    private val MODEL = "gemini-flash-latest"

    private val sources = listOf(
        "日本語" to TranslateLanguage.JAPANESE,
        "한국어" to TranslateLanguage.KOREAN,
        "中文" to TranslateLanguage.CHINESE,
        "English" to TranslateLanguage.ENGLISH
    )
    private val targets = listOf(
        "عربي" to TranslateLanguage.ARABIC,
        "Français" to TranslateLanguage.FRENCH,
        "English" to TranslateLanguage.ENGLISH
    )
    private val srcNames = listOf("Japanese", "Korean", "Chinese", "English")
    private val dstNames = listOf("Arabic (clear Modern Standard Arabic)", "French", "English")
    private var si = 2
    private var ti = 0
    @Volatile private var busy = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun ui(s: String) = runOnUiThread { status.text = s }
    private fun prefs() = getSharedPreferences("mangatr", MODE_PRIVATE)

    private fun recognizer(): TextRecognizer = when (si) {
        0 -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        1 -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        2 -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val urlBox = EditText(this).apply {
            hint = "https://..."
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_GO
        }
        val goBtn = Button(this).apply { text = "Go" }
        val srcBtn = Button(this).apply { text = "من: ${sources[si].first}" }
        val tgtBtn = Button(this).apply { text = "إلى: ${targets[ti].first}" }
        val keyBtn = Button(this).apply { text = "مفتاح" }
        val clrBtn = Button(this).apply { text = "مسح" }
        val screenBtn = Button(this).apply { text = "ترجم الشاشة" }
        val chapterBtn = Button(this).apply { text = "ترجم الفصل كامل" }

        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(urlBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(goBtn)
        }
        fun row(vararg bs: Button) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            bs.forEach {
                it.textSize = 12f
                addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        }

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(v: WebView?, url: String?, f: Bitmap?) {
                    this@MainActivity.overlay.removeAllViews()
                    if (url != null) urlBox.setText(url)
                }
            }
            webChromeClient = WebChromeClient()
            setOnScrollChangeListener { _, _, _, _, _ -> this@MainActivity.overlay.removeAllViews() }
        }
        overlay = FrameLayout(this)
        val stack = FrameLayout(this).apply {
            addView(web, FrameLayout.LayoutParams(-1, -1))
            addView(this@MainActivity.overlay, FrameLayout.LayoutParams(-1, -1))
        }
        status = TextView(this).apply { textSize = 12f; setPadding(dp(8), dp(2), dp(8), dp(2)) }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(24), 0, 0)
            addView(row1)
            addView(row(srcBtn, tgtBtn, keyBtn, clrBtn))
            addView(row(screenBtn, chapterBtn))
            addView(stack, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(status)
        })

        fun go() {
            var u = urlBox.text.toString().trim()
            if (u.isEmpty()) return
            if (!u.startsWith("http")) u = "https://$u"
            web.loadUrl(u)
        }
        goBtn.setOnClickListener { go() }
        urlBox.setOnEditorActionListener { _, _, _ -> go(); true }
        srcBtn.setOnClickListener { si = (si + 1) % sources.size; srcBtn.text = "من: ${sources[si].first}" }
        tgtBtn.setOnClickListener { ti = (ti + 1) % targets.size; tgtBtn.text = "إلى: ${targets[ti].first}" }
        keyBtn.setOnClickListener { askKey() }
        clrBtn.setOnClickListener {
            this@MainActivity.overlay.removeAllViews()
            web.evaluateJavascript("document.querySelectorAll('.__mt').forEach(function(e){e.remove()})", null)
        }
        screenBtn.setOnClickListener { translateScreen() }
        chapterBtn.setOnClickListener { translateChapter() }
        urlBox.setText("https://")

        status.text = if ((prefs().getString("gk", "") ?: "").isEmpty())
            "لم تضف مفتاح Gemini بعد (زر مفتاح)" else "وضع Gemini مفعّل"
    }

    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    private fun askKey() {
        val et = EditText(this).apply {
            hint = "AIza..."
            setSingleLine()
            setText(prefs().getString("gk", ""))
        }
        AlertDialog.Builder(this)
            .setTitle("مفتاح Gemini من aistudio.google.com")
            .setView(et)
            .setPositiveButton("حفظ") { _, _ ->
                prefs().edit().putString("gk", et.text.toString().trim()).apply()
                status.text = "تم حفظ المفتاح"
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    // ---------- ترجمة الشاشة (ML Kit) ----------
    private fun translateScreen() {
        if (busy) return
        val src = sources[si].second
        val dst = targets[ti].second
        if (src == dst) { status.text = "اللغة المصدر تساوي اللغة الهدف"; return }
        if (web.width == 0 || web.height == 0) return

        val bmp = Bitmap.createBitmap(web.width, web.height, Bitmap.Config.ARGB_8888)
        web.draw(Canvas(bmp))

        busy = true
        this.overlay.removeAllViews()
        status.text = "جاري التحضير..."

        val translator = Translation.getClient(
            TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(dst).build()
        )
        val rec = recognizer()

        fun done(msg: String) {
            status.text = msg
            busy = false
            translator.close()
            rec.close()
        }

        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                rec.process(InputImage.fromBitmap(bmp, 0))
                    .addOnSuccessListener { res ->
                        val blocks = res.textBlocks.filter { it.boundingBox != null && it.text.isNotBlank() }
                        if (blocks.isEmpty()) { done("لم أجد نصًا في الشاشة"); return@addOnSuccessListener }
                        var left = blocks.size
                        blocks.forEach { b ->
                            translator.translate(b.text.replace("\n", " "))
                                .addOnSuccessListener { t -> addBox(b.boundingBox!!, t) }
                                .addOnCompleteListener {
                                    left--
                                    if (left == 0) done("تمت الترجمة: ${blocks.size}")
                                }
                        }
                    }
                    .addOnFailureListener { done("فشل قراءة النص") }
            }
            .addOnFailureListener { done("فشل تحميل نموذج الترجمة (يلزم إنترنت أول مرة)") }
    }

    private fun addBox(r: Rect, text: String) {
        val pad = dp(3)
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            setAutoSizeTextTypeUniformWithConfiguration(7, 30, 1, TypedValue.COMPLEX_UNIT_SP)
        }
        val lp = FrameLayout.LayoutParams(maxOf(r.width(), dp(40)), maxOf(r.height(), dp(24)))
        lp.leftMargin = r.left
        lp.topMargin = r.top
        this.overlay.addView(tv, lp)
    }

    // ---------- ترجمة الفصل ----------
    private fun translateChapter() {
        if (busy) return
        if (sources[si].second == targets[ti].second) { status.text = "اللغة المصدر تساوي اللغة الهدف"; return }
        busy = true
        this.overlay.removeAllViews()
        status.text = "تمرير الصفحة لتحميل كل الصور..."
        var steps = 0
        val h = Handler(Looper.getMainLooper())
        val step = object : Runnable {
            override fun run() {
                web.evaluateJavascript(
                    "(function(){window.scrollBy(0,Math.round(window.innerHeight*0.9));" +
                        "return (window.scrollY+window.innerHeight)>=(document.documentElement.scrollHeight-5);})()"
                ) { r ->
                    steps++
                    if (r == "true" || steps > 300) {
                        web.evaluateJavascript("window.scrollTo(0,0)", null)
                        h.postDelayed({ collectImages() }, 1500)
                    } else {
                        h.postDelayed(this, 900)
                    }
                }
            }
        }
        h.post(step)
    }

    private fun collectImages() {
        val js = """(function(){var a=[];document.querySelectorAll('img').forEach(function(im){
            var s=im.currentSrc||im.src||im.getAttribute('data-src')||im.getAttribute('data-original');
            var r=im.getBoundingClientRect();
            if(s&&s.indexOf('http')===0&&r.width>150&&r.height>150){
              a.push({s:s,x:r.left+window.scrollX,y:r.top+window.scrollY,w:r.width,h:r.height});}
        });return JSON.stringify(a);})()"""
        web.evaluateJavascript(js) { r ->
            val arr = try { JSONArray(JSONTokener(r).nextValue() as String) } catch (e: Exception) { null }
            if (arr == null || arr.length() == 0) {
                status.text = "لم أجد صور في هذه الصفحة"
                busy = false
                return@evaluateJavascript
            }
            val pageUrl = web.url ?: ""
            val ua = web.settings.userAgentString
            val key = prefs().getString("gk", "") ?: ""
            Thread {
                if (key.isNotEmpty()) processGemini(arr, pageUrl, ua, key)
                else processImages(arr, pageUrl, ua)
            }.start()
        }
    }

    // ---------- المسار الجديد: Gemini ----------
    private fun processGemini(arr: JSONArray, pageUrl: String, ua: String, key: String) {
        val srcName = srcNames[si]
        val dstName = dstNames[ti]
        var total = 0
        try {
            for (i in 0 until arr.length()) {
                ui("Gemini: الصورة ${i + 1}/${arr.length()}")
                val o = arr.getJSONObject(i)
                val bmp = download(o.getString("s"), pageUrl, ua) ?: continue
                val cw = o.getDouble("w")
                val scale = cw / bmp.width
                val tileH = bmp.width * 2
                val boxes = JSONArray()
                var y0 = 0
                while (y0 < bmp.height) {
                    val th = minOf(tileH, bmp.height - y0)
                    if (th < 150) { y0 += th; continue }
                    val tile = Bitmap.createBitmap(bmp, 0, y0, bmp.width, th)
                    val items = askGemini(tile, srcName, dstName, key)
                    if (tile !== bmp) tile.recycle()
                    for (k in 0 until items.length()) {
                        val it = items.optJSONObject(k) ?: continue
                        val t = it.optString("t", "")
                        val bx = it.optJSONArray("box") ?: continue
                        if (t.isBlank() || bx.length() < 4) continue
                        val ymin = bx.getDouble(0)
                        val xmin = bx.getDouble(1)
                        val ymax = bx.getDouble(2)
                        val xmax = bx.getDouble(3)
                        val w = (xmax - xmin) / 1000.0 * cw + 6
                        val hh = (ymax - ymin) / 1000.0 * th * scale + 6
                        val x = o.getDouble("x") + xmin / 1000.0 * cw - 3
                        val y = o.getDouble("y") + (y0 + ymin / 1000.0 * th) * scale - 3
                        val f = minOf(28.0, maxOf(8.0, Math.sqrt(w * hh / (t.length * 1.2 + 1))))
                        boxes.put(
                            JSONObject().put("t", t).put("x", x).put("y", y)
                                .put("w", w).put("h", hh).put("f", f)
                        )
                        total++
                    }
                    y0 += th
                    Thread.sleep(3500)
                }
                bmp.recycle()
                inject(boxes)
            }
            ui("تمت ترجمة $total نص بواسطة Gemini")
        } catch (e: Exception) {
            ui("خطأ: ${e.message}")
        } finally {
            busy = false
        }
    }

    private fun askGemini(tile: Bitmap, src: String, dst: String, key: String): JSONArray {
        val w = minOf(tile.width, 900)
        val s = if (w == tile.width) tile
        else Bitmap.createScaledBitmap(tile, w, (tile.height * w.toDouble() / tile.width).toInt(), true)
        val bos = ByteArrayOutputStream()
        s.compress(Bitmap.CompressFormat.JPEG, 80, bos)
        if (s !== tile) s.recycle()
        val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)

        val prompt = "This image is part of a $src comic page (manga/manhua/manhwa). " +
            "Find every piece of dialogue, caption and sound-effect text. " +
            "Translate each one into natural $dst, keeping the story context and the tone of speech. " +
            "Return ONLY a JSON array. Each element must be {\"t\": \"translation\", \"box\": [ymin, xmin, ymax, xmax]} " +
            "where box tightly surrounds the ORIGINAL text block, with coordinates normalized to 0-1000 relative to this image. " +
            "If there is no text return []."

        val body = JSONObject()
            .put(
                "contents", JSONArray().put(
                    JSONObject().put(
                        "parts", JSONArray()
                            .put(JSONObject().put("text", prompt))
                            .put(
                                JSONObject().put(
                                    "inline_data",
                                    JSONObject().put("mime_type", "image/jpeg").put("data", b64)
                                )
                            )
                    )
                )
            )
            .put(
                "generationConfig",
                JSONObject().put("responseMimeType", "application/json").put("temperature", 0.3)
            )

        var attempt = 0
        while (true) {
            val c = URL("https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent")
                .openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("x-goog-api-key", key)
            c.connectTimeout = 20000
            c.readTimeout = 90000
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            if (code == 200) {
                val txt = c.inputStream.bufferedReader().readText()
                val parts = JSONObject(txt).getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts")
                var out = parts.getJSONObject(0).getString("text").trim()
                out = out.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                if (out.startsWith("{")) {
                    val ob = JSONObject(out)
                    return ob.getJSONArray(ob.keys().next())
                }
                return JSONArray(out)
            }
            val err = c.errorStream?.bufferedReader()?.readText() ?: ""
            if ((code == 429 || code == 503) && attempt < 3) {
                attempt++
                ui("انتظار الحصة... ($attempt)")
                Thread.sleep(20000)
                continue
            }
            throw Exception("Gemini $code: ${err.take(150)}")
        }
    }

    // ---------- المسار الاحتياطي: ML Kit ----------
    private fun processImages(arr: JSONArray, pageUrl: String, ua: String) {
        val translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(sources[si].second)
                .setTargetLanguage(targets[ti].second)
                .build()
        )
        val rec = recognizer()
        try {
            ui("تحميل نموذج الترجمة...")
            Tasks.await(translator.downloadModelIfNeeded(DownloadConditions.Builder().build()))
            var total = 0
            for (i in 0 until arr.length()) {
                ui("الصورة ${i + 1}/${arr.length()}")
                val o = arr.getJSONObject(i)
                val bmp = download(o.getString("s"), pageUrl, ua) ?: continue
                val scale = o.getDouble("w") / bmp.width
                val boxes = JSONArray()
                var y0 = 0
                while (y0 < bmp.height) {
                    val th = minOf(2000, bmp.height - y0)
                    val tile = Bitmap.createBitmap(bmp, 0, y0, bmp.width, th)
                    try {
                        val res = Tasks.await(rec.process(InputImage.fromBitmap(tile, 0)))
                        for (b in res.textBlocks) {
                            val r = b.boundingBox ?: continue
                            if (b.text.isBlank()) continue
                            val t = try {
                                Tasks.await(translator.translate(b.text.replace("\n", " ")))
                            } catch (e: Exception) { continue }
                            val w = r.width() * scale
                            val hh = r.height() * scale
                            val f = minOf(28.0, maxOf(8.0, Math.sqrt(w * hh / (t.length * 1.2 + 1))))
                            boxes.put(
                                JSONObject()
                                    .put("t", t)
                                    .put("x", o.getDouble("x") + r.left * scale)
                                    .put("y", o.getDouble("y") + (y0 + r.top) * scale)
                                    .put("w", w).put("h", hh).put("f", f)
                            )
                            total++
                        }
                    } catch (e: Exception) { }
                    if (tile !== bmp) tile.recycle()
                    y0 += th
                }
                bmp.recycle()
                inject(boxes)
            }
            ui("تمت ترجمة $total نص")
        } catch (e: Exception) {
            ui("خطأ: ${e.message}")
        } finally {
            translator.close()
            rec.close()
            busy = false
        }
    }

    private fun download(u: String, referer: String, ua: String): Bitmap? = try {
        val c = URL(u).openConnection() as HttpURLConnection
        c.setRequestProperty("Referer", referer)
        c.setRequestProperty("User-Agent", ua)
        CookieManager.getInstance().getCookie(u)?.let { c.setRequestProperty("Cookie", it) }
        c.connectTimeout = 15000
        c.readTimeout = 30000
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        c.inputStream.use { BitmapFactory.decodeStream(it, null, opts) }
    } catch (e: Exception) { null }

    private fun inject(boxes: JSONArray) {
        if (boxes.length() == 0) return
        val json = boxes.toString().replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
        val js = """(function(b){b.forEach(function(o){var d=document.createElement('div');
            d.className='__mt';d.textContent=o.t;
            d.style.cssText='position:absolute;z-index:99999;background:#fff;color:#000;text-align:center;display:flex;align-items:center;justify-content:center;overflow:hidden;font-family:sans-serif;line-height:1.15;padding:2px;box-sizing:border-box;pointer-events:none;left:'+o.x+'px;top:'+o.y+'px;width:'+o.w+'px;height:'+o.h+'px;font-size:'+o.f+'px;';
            document.body.appendChild(d);});})($json);"""
        runOnUiThread { web.evaluateJavascript(js, null) }
    }
}
