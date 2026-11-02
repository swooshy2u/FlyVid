package com.swooshy.lyricrenderer

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
import android.webkit.WebView
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
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

    private val pickAudioLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            selectedAudioFile = copyUriToTempFile(it, "input_audio.mp3")
            findViewById<Button>(R.id.btnPickAudio).text = "1. Аудио: выбрано ✅"
            tvStatus.text = "Аудио загружено. Можно начинать рендер!"
        }
    }

    private val pickVideoLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            selectedVideoBgFile = copyUriToTempFile(it, "input_bg.mp4")
            findViewById<Button>(R.id.btnPickVideo).text = "2. Видео-фон: выбран ✅"
        }
    }

    private val pickHtmlLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            customHtmlContent = contentResolver.openInputStream(it)?.bufferedReader()?.use { r -> r.readText() }
            findViewById<Button>(R.id.btnPickHtml).text = "3. Свой HTML: выбран ✅"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WebView.enableSlowWholeDocumentDraw()
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.offscreenWebView)
        tvStatus = findViewById(R.id.tvStatus)
        progressBar = findViewById(R.id.progressBar)
        btnRender = findViewById(R.id.btnRender)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.setBackgroundColor(Color.TRANSPARENT)

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
                } catch (e: Exception) {
                    tvStatus.text = "Ошибка: ${e.message}"
                    btnRender.isEnabled = true
                }
            }
        }
    }

    private suspend fun startRenderPipeline(audioFile: File, videoBgFile: File?) {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(audioFile.absolutePath)
        val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 15500L
        retriever.release()

        val durSec = durMs / 1000.0
        val totalFrames = Math.ceil(durSec * FPS).toInt()
        val hasVideoBg = videoBgFile != null && videoBgFile.exists()

        tvStatus.text = "[1/4] Трек: ${String.format(Locale.US, "%.2f", durSec)} сек ($totalFrames кадров)\nЗагрузка шаблона и шрифтов..."

        val rawHtml = customHtmlContent ?: assets.open("default_template.html").bufferedReader().use { it.readText() }
        val finalHtml = rawHtml
            .replace("__DUR__", String.format(Locale.US, "%.3f", durSec))
            .replace("__BG_COLOR__", if (hasVideoBg) "transparent" else "#070709")
            .replace("__GLOW_DISPLAY__", if (hasVideoBg) "none" else "block")

        webView.loadDataWithBaseURL("https://fonts.googleapis.com/", finalHtml, "text/html", "UTF-8", null)
        delay(2000)

        suspendCancellableCoroutine<Unit> { cont ->
            webView.evaluateJavascript("document.fonts.ready.then(() => true)") { cont.resume(Unit) }
        }

        val tempOutputFile = File(cacheDir, "swooshy_tvoya_lyric.mp4")
        if (tempOutputFile.exists()) tempOutputFile.delete()

        val pipePath = FFmpegKitConfig.registerNewFFmpegPipe(this)
        val durStr = String.format(Locale.US, "%.3f", durSec)

        val ffCommand = if (hasVideoBg) {
            "-y -v error -stream_loop -1 -i \"${videoBgFile!!.absolutePath}\" " +
            "-f rawvideo -pix_fmt rgba -s ${W}x${H} -r $FPS -i \"$pipePath\" " +
            "-i \"${audioFile.absolutePath}\" " +
            "-filter_complex \"[0:v]scale=$W:$H:force_original_aspect_ratio=increase,crop=$W:$H[bg];[bg][1:v]overlay=0:0:format=auto[outv]\" " +
            "-map \"[outv]\" -map 2:a -t $durStr " +
            "-c:v libx264 -preset superfast -crf 18 -pix_fmt yuv420p -c:a aac -b:a 192k -movflags +faststart \"${tempOutputFile.absolutePath}\""
        } else {
            "-y -v error -f rawvideo -pix_fmt rgba -s ${W}x${H} -r $FPS -i \"$pipePath\" " +
            "-i \"${audioFile.absolutePath}\" " +
            "-map 0:v -map 1:a -t $durStr " +
            "-c:v libx264 -preset superfast -crf 18 -pix_fmt yuv420p -c:a aac -b:a 192k -movflags +faststart \"${tempOutputFile.absolutePath}\""
        }

        val ffmpegDeferred = CompletableDeferred<Boolean>()
        FFmpegKit.executeAsync(ffCommand) { session ->
            ffmpegDeferred.complete(ReturnCode.isSuccess(session.returnCode))
        }

        val startTime = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            FileOutputStream(File(pipePath)).use { pipeOut ->
                val bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                val byteBuffer = ByteBuffer.allocate(W * H * 4)

                for (f in 0 until totalFrames) {
                    val t = f.toDouble() / FPS

                    withContext(Dispatchers.Main) {
                        suspendCancellableCoroutine<Unit> { cont ->
                            webView.evaluateJavascript("window.seekTo($t);") { cont.resume(Unit) }
                        }
                        bitmap.eraseColor(Color.TRANSPARENT)
                        webView.draw(canvas)
                    }

                    byteBuffer.rewind()
                    bitmap.copyPixelsToBuffer(byteBuffer)
                    pipeOut.write(byteBuffer.array())

                    val done = f + 1
                    if (done % 5 == 0 || done == totalFrames) {
                        val pct = (done * 100) / totalFrames
                        val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
                        val speed = done / elapsed.coerceAtLeast(0.1)
                        val eta = ((totalFrames - done) / speed.coerceAtLeast(0.1)).toInt()

                        withContext(Dispatchers.Main) {
                            progressBar.progress = pct
                            tvStatus.text = String.format(
                                Locale.US,
                                "[4/4] Рендер: %d%%\nКадр: %d / %d\nСкорость: %.1f кадр/с | Осталось: ~%d сек",
                                pct, done, totalFrames, speed, eta
                            )
                        }
                    }
                }
            }
        }

        FFmpegKitConfig.closeFFmpegPipe(pipePath)
        val success = ffmpegDeferred.await()

        if (success && tempOutputFile.exists()) {
            val savedPath = saveVideoToDownloads(tempOutputFile)
            val totalTime = (System.currentTimeMillis() - startTime) / 1000.0
            tvStatus.text = "✅ ГОТОВО за ${String.format(Locale.US, "%.1f", totalTime)} сек!\nСохранено в Загрузки:\n$savedPath"
        } else {
            tvStatus.text = "❌ Ошибка при сборке FFmpeg."
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
