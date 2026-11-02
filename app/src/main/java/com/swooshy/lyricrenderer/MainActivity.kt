package com.swooshy.lyricrenderer

import android.annotation.SuppressLint
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.coroutines.resume

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var tvStatus: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnRender: Button

    private var selectedAudioFile: File? = null
    private var selectedVideoBgFile: File? = null
    private var customHtmlContent: String? = null

    private val W = 1080
    private val H = 1920
    private val FPS = 30

    @Volatile
    private var frameReadyContinuation: CancellableContinuation<Unit>? = null

    inner class RenderBridge {
        @JavascriptInterface
        fun onFrameReady() {
            frameReadyContinuation?.let { cont ->
                frameReadyContinuation = null
                if (cont.isActive) cont.resume(Unit)
            }
        }
    }

    private val pickAudioLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            try {
                selectedAudioFile = copyUriToTempFile(it, "input_audio.mp3")
                findViewById<Button>(R.id.btnPickAudio).text = "1. Аудио: выбрано ✅"
                tvStatus.text = "Аудио загружено. Можно начинать рендер!"
            } catch (t: Throwable) {
                tvStatus.text = "Ошибка чтения аудио: ${t.message}"
            }
        }
    }

    private val pickVideoLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            try {
                selectedVideoBgFile = copyUriToTempFile(it, "input_bg.mp4")
                findViewById<Button>(R.id.btnPickVideo).text = "2. Видео-фон: выбран ✅"
            } catch (t: Throwable) {
                tvStatus.text = "Ошибка чтения видео: ${t.message}"
            }
        }
    }

    private val pickHtmlLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            try {
                customHtmlContent = contentResolver.openInputStream(it)?.bufferedReader()?.use { r -> r.readText() }
                findViewById<Button>(R.id.btnPickHtml).text = "3. Свой HTML: выбран ✅"
            } catch (t: Throwable) {
                tvStatus.text = "Ошибка чтения HTML: ${t.message}"
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            WebView.enableSlowWholeDocumentDraw()
        } catch (_: Throwable) {}

        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.offscreenWebView)
        tvStatus = findViewById(R.id.tvStatus)
        progressBar = findViewById(R.id.progressBar)
        btnRender = findViewById(R.id.btnRender)

        // Масштаб 100%: 1 CSS-пиксель = 1 физический пиксель холста 1080x1920
        webView.setInitialScale(100)
        webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            textZoom = 100
            useWideViewPort = false
            loadWithOverviewMode = false
        }
        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.addJavascriptInterface(RenderBridge(), "AndroidBridge")

        findViewById<Button>(R.id.btnPickAudio).setOnClickListener { pickAudioLauncher.launch("audio/*") }
        findViewById<Button>(R.id.btnPickVideo).setOnClickListener { pickVideoLauncher.launch("video/*") }
        findViewById<Button>(R.id.btnPickHtml).setOnClickListener { pickHtmlLauncher.launch("text/html") }

        btnRender.setOnClickListener {
            val audio = selectedAudioFile
            if (audio == null) {
                tvStatus.text = "⚠️ Сначала выберите MP3 файл (Кнопка 1)!"
                return@setOnClickListener
            }
            btnRender.isEnabled = false
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    startRenderPipeline(audio, selectedVideoBgFile)
                } catch (t: Throwable) {
                    tvStatus.text = "❌ Сбой рендера:\n${t.javaClass.simpleName}: ${t.message}\n${t.stackTrace.take(3).joinToString("\n")}"
                    btnRender.isEnabled = true
                }
            }
        }
    }

    private suspend fun startRenderPipeline(audioFile: File, videoBgFile: File?) {
        val durSec = withContext(Dispatchers.IO) {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(audioFile.absolutePath)
                val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 15500L
                retriever.release()
                (durMs / 1000.0).coerceAtLeast(1.0)
            } catch (t: Throwable) {
                15.5
            }
        }

        val totalFrames = Math.ceil(durSec * FPS).toInt()
        val hasVideoBg = videoBgFile != null && videoBgFile.exists()

        tvStatus.text = "[1/4] Трек: ${String.format(Locale.US, "%.2f", durSec)} сек ($totalFrames кадров)\nЗагрузка шаблона и шрифтов..."

        val rawHtml = customHtmlContent ?: assets.open("default_template.html").bufferedReader().use { it.readText() }
        // Удаляем <meta name="viewport">, если ИИ его вставил, чтобы он не сбивал setInitialScale(100)
        val finalHtml = rawHtml
            .replace(Regex("<meta[^>]*name=[\"']viewport[\"'][^>]*>", RegexOption.IGNORE_CASE), "")
            .replace("__DUR__", String.format(Locale.US, "%.3f", durSec))
            .replace("__BG_COLOR__", if (hasVideoBg) "transparent" else "#070709")
            .replace("__GLOW_DISPLAY__", if (hasVideoBg) "none" else "block")

        val pageLoaded = CompletableDeferred<Unit>()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (!pageLoaded.isCompleted) pageLoaded.complete(Unit)
            }
        }
        webView.setInitialScale(100)
        webView.loadDataWithBaseURL("https://fonts.googleapis.com/", finalHtml, "text/html", "UTF-8", null)
        withTimeoutOrNull(5000) { pageLoaded.await() }

        // Оставляем только ожидание шрифтов и шаг кадра (без повторного уменьшения scale!)
        val initJs = """
            (function() {
                window.__fontsReady = false;
                if (document.fonts && document.fonts.ready) {
                    document.fonts.ready.then(function() { window.__fontsReady = true; });
                } else {
                    window.__fontsReady = true;
                }
                window.__stepFrame = function(t) {
                    if (window.seekTo) window.seekTo(t);
                    document.body.offsetHeight;
                    requestAnimationFrame(function() {
                        setTimeout(function() {
                            if (window.AndroidBridge) window.AndroidBridge.onFrameReady();
                        }, 0);
                    });
                };
            })();
        """.trimIndent()

        suspendCancellableCoroutine<Unit> { cont ->
            webView.evaluateJavascript(initJs) { cont.resume(Unit) }
        }

        for (i in 0 until 30) {
            val ready = suspendCancellableCoroutine<String> { cont ->
                webView.evaluateJavascript("window.__fontsReady") { res -> cont.resume(res ?: "false") }
            }
            if (ready == "true") break
            delay(100)
        }

        val framesDir = File(cacheDir, "frames").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }

        val startTime = System.currentTimeMillis()
        val bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val ext = if (hasVideoBg) "png" else "jpg"

        for (f in 0 until totalFrames) {
            val t = f.toDouble() / FPS

            withTimeoutOrNull(80) {
                suspendCancellableCoroutine<Unit> { cont ->
                    frameReadyContinuation = cont
                    webView.evaluateJavascript("window.__stepFrame($t);", null)
                }
            }

            if (hasVideoBg) {
                bitmap.eraseColor(Color.TRANSPARENT)
            } else {
                bitmap.eraseColor(Color.parseColor("#070709"))
            }
            webView.draw(canvas)

            val frameFile = File(framesDir, String.format(Locale.US, "frame_%05d.%s", f, ext))
            withContext(Dispatchers.IO) {
                FileOutputStream(frameFile).use { out ->
                    if (hasVideoBg) {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    } else {
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    }
                }
            }

            val done = f + 1
            if (done % 5 == 0 || done == totalFrames) {
                val pct = (done * 80) / totalFrames
                val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
                val speed = done / elapsed.coerceAtLeast(0.1)
                progressBar.progress = pct
                tvStatus.text = String.format(
                    Locale.US,
                    "[3/4] Отрисовка кадров: %d / %d\nСкорость: %.1f кадр/с",
                    done, totalFrames, speed
                )
            }
        }

        tvStatus.text = "[4/4] Кодирование MP4 через FFmpeg..."
        progressBar.progress = 85

        val tempOutputFile = File(cacheDir, "swooshy_tvoya_lyric.mp4")
        if (tempOutputFile.exists()) tempOutputFile.delete()

        val durStr = String.format(Locale.US, "%.3f", durSec)
        val inputPattern = File(framesDir, "frame_%05d.$ext").absolutePath

        val ffCommand = if (hasVideoBg) {
            "-y -stream_loop -1 -i \"${videoBgFile!!.absolutePath}\" " +
            "-framerate $FPS -i \"$inputPattern\" " +
            "-i \"${audioFile.absolutePath}\" " +
            "-filter_complex \"[0:v]scale=$W:$H:force_original_aspect_ratio=increase,crop=$W:$H[bg];[bg][1:v]overlay=0:0:format=auto[outv]\" " +
            "-map \"[outv]\" -map 2:a -t $durStr " +
            "-c:v libx264 -preset ultrafast -crf 18 -pix_fmt yuv420p -c:a aac -b:a 192k -movflags +faststart \"${tempOutputFile.absolutePath}\""
        } else {
            "-y -framerate $FPS -i \"$inputPattern\" " +
            "-i \"${audioFile.absolutePath}\" " +
            "-map 0:v -map 1:a -t $durStr " +
            "-c:v libx264 -preset ultrafast -crf 18 -pix_fmt yuv420p -c:a aac -b:a 192k -movflags +faststart \"${tempOutputFile.absolutePath}\""
        }

        val session = withContext(Dispatchers.IO) {
            FFmpegKit.execute(ffCommand)
        }

        framesDir.deleteRecursively()

        if (ReturnCode.isSuccess(session.returnCode) && tempOutputFile.exists()) {
            progressBar.progress = 100
            val savedPath = saveVideoToDownloads(tempOutputFile)
            val totalTime = (System.currentTimeMillis() - startTime) / 1000.0
            tvStatus.text = "✅ ГОТОВО за ${String.format(Locale.US, "%.1f", totalTime)} сек!\nСохранено в Загрузки:\n$savedPath"
        } else {
            tvStatus.text = "❌ Ошибка FFmpeg:\n${session.allLogsAsString.takeLast(400)}"
        }
        btnRender.isEnabled = true
    }

    private fun copyUriToTempFile(uri: Uri, fileName: String): File {
        val tempFile = File(cacheDir, fileName)
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempFile).use { output -> input.copyTo(output) }
        }
        return tempFile
    }

    private fun saveVideoToDownloads(sourceFile: File): String {
        val fileName = "swooshy_lyric_${System.currentTimeMillis() / 1000}.mp4"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                contentResolver.openOutputStream(it)?.use { out ->
                    sourceFile.inputStream().use { input -> input.copyTo(out) }
                }
            }
            return "Downloads/$fileName"
        } else {
            val dest = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)
            sourceFile.copyTo(dest, overwrite = true)
            return dest.absolutePath
        }
    }
}
