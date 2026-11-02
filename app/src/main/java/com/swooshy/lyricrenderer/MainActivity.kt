package com.swooshy.lyricrenderer

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Surface
import android.view.View
import android.webkit.WebView
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { WebView.enableSlowWholeDocumentDraw() } catch (_: Throwable) {}
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.offscreenWebView)
        tvStatus = findViewById(R.id.tvStatus)
        progressBar = findViewById(R.id.progressBar)
        btnRender = findViewById(R.id.btnRender)

        webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.setBackgroundColor(Color.TRANSPARENT)

        webView.measure(
            View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY)
        )
        webView.layout(0, 0, W, H)

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
                    startHardwareRender(audio, selectedVideoBgFile)
                } catch (t: Throwable) {
                    tvStatus.text = "❌ Сбой рендера:\n${t.javaClass.simpleName}: ${t.message}\n${t.stackTrace.take(3).joinToString("\n")}"
                    btnRender.isEnabled = true
                }
            }
        }
    }

    private suspend fun startHardwareRender(audioFile: File, videoBgFile: File?) {
        val durSec = withContext(Dispatchers.IO) {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(audioFile.absolutePath)
                val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 15500L
                retriever.release()
                (durMs / 1000.0).coerceAtLeast(1.0)
            } catch (_: Throwable) { 15.5 }
        }

        val totalFrames = Math.ceil(durSec * FPS).toInt()
        val hasVideoBg = videoBgFile != null && videoBgFile.exists()

        tvStatus.text = "[1/3] Трек: ${String.format(Locale.US, "%.2f", durSec)} сек ($totalFrames кадров)\nЗагрузка шаблона и шрифтов..."

        val rawHtml = customHtmlContent ?: assets.open("default_template.html").bufferedReader().use { it.readText() }
        val finalHtml = rawHtml
            .replace("__DUR__", String.format(Locale.US, "%.3f", durSec))
            .replace("__BG_COLOR__", if (hasVideoBg) "transparent" else "#070709")
            .replace("__GLOW_DISPLAY__", if (hasVideoBg) "none" else "block")

        webView.loadDataWithBaseURL("https://fonts.googleapis.com/", finalHtml, "text/html", "UTF-8", null)
        delay(2200)

        val tempOutputFile = File(cacheDir, "swooshy_tvoya_lyric.mp4")
        if (tempOutputFile.exists()) tempOutputFile.delete()

        val startTime = System.currentTimeMillis()

        // Запускаем аппаратный кодек H.264 (MediaCodec)
        val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, W, H).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 12_000_000) // 12 Mbps высокое качество
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface: Surface = encoder.createInputSurface()
        encoder.start()

        val muxer = MediaMuxer(tempOutputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var videoTrackIndex = -1
        var muxerStarted = false
        val bufferInfo = MediaCodec.BufferInfo()

        val bgRetriever = if (hasVideoBg) {
            MediaMetadataRetriever().apply { setDataSource(videoBgFile!!.absolutePath) }
        } else null
        val bgDurMs = bgRetriever?.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 5000L

        val webBitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val webCanvas = Canvas(webBitmap)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)

        for (f in 0 until totalFrames) {
            val t = f.toDouble() / FPS

            // 1. Обновляем JS-сцену и рисуем кадр из WebView
            suspendCancellableCoroutine<Unit> { cont ->
                webView.evaluateJavascript("if(window.seekTo) window.seekTo($t);") { cont.resume(Unit) }
            }
            webView.measure(
                View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY)
            )
            webView.layout(0, 0, W, H)
            webBitmap.eraseColor(Color.TRANSPARENT)
            webView.draw(webCanvas)

            // 2. Передаем кадр напрямую в аппаратную поверхность видеокодека
            withContext(Dispatchers.IO) {
                val surfaceCanvas = inputSurface.lockHardwareCanvas()
                try {
                    if (bgRetriever != null) {
                        val bgTimeUs = ((f * 1000L / FPS) % bgDurMs) * 1000L
                        val bgFrame = bgRetriever.getFrameAtTime(bgTimeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                        if (bgFrame != null) {
                            val scale = maxOf(W.toFloat() / bgFrame.width, H.toFloat() / bgFrame.height)
                            val sw = (bgFrame.width * scale).toInt()
                            val sh = (bgFrame.height * scale).toInt()
                            val dx = (W - sw) / 2f
                            val dy = (H - sh) / 2f
                            val scaled = Bitmap.createScaledBitmap(bgFrame, sw, sh, true)
                            surfaceCanvas.drawBitmap(scaled, dx, dy, paint)
                            bgFrame.recycle()
                        } else {
                            surfaceCanvas.drawColor(Color.parseColor("#070709"))
                        }
                    } else {
                        surfaceCanvas.drawColor(Color.parseColor("#070709"))
                    }
                    surfaceCanvas.drawBitmap(webBitmap, 0f, 0f, paint)
                } finally {
                    inputSurface.unlockCanvasAndPost(surfaceCanvas)
                }

                // Сливаем готовые H.264 пакеты из энкодера в MP4
                drainEncoder(encoder, muxer, bufferInfo, endOfStream = false,
                    getTrack = { videoTrackIndex },
                    setTrack = { idx -> videoTrackIndex = idx; muxerStarted = true }
                )
            }

            val done = f + 1
            if (done % 5 == 0 || done == totalFrames) {
                val pct = (done * 90) / totalFrames
                val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
                val speed = done / elapsed.coerceAtLeast(0.1)
                progressBar.progress = pct
                tvStatus.text = String.format(
                    Locale.US,
                    "[2/3] Аппаратный рендер: %d%%\nКадр: %d / %d (%.1f кадр/с)",
                    pct, done, totalFrames, speed
                )
            }
        }

        bgRetriever?.release()

        // Завершаем видеопоток
        withContext(Dispatchers.IO) {
            drainEncoder(encoder, muxer, bufferInfo, endOfStream = true,
                getTrack = { videoTrackIndex },
                setTrack = { idx -> videoTrackIndex = idx; muxerStarted = true }
            )
            encoder.stop()
            encoder.release()
            inputSurface.release()
            if (muxerStarted) muxer.stop()
            muxer.release()
        }

        // [3/3] Приклеиваем аудиодорожку к видео (если формат AAC/M4A — напрямую, если MP3 — транскодируем в AAC)
        tvStatus.text = "[3/3] Добавление аудиодорожки..."
        progressBar.progress = 95

        val finalOutputFile = File(cacheDir, "swooshy_final.mp4")
        if (finalOutputFile.exists()) finalOutputFile.delete()

        withContext(Dispatchers.IO) {
            muxVideoAndAudio(tempOutputFile, audioFile, finalOutputFile, (durSec * 1_000_000L).toLong())
        }

        val savedPath = saveVideoToDownloads(if (finalOutputFile.exists()) finalOutputFile else tempOutputFile)
        progressBar.progress = 100
        val totalTime = (System.currentTimeMillis() - startTime) / 1000.0
        tvStatus.text = "✅ ГОТОВО за ${String.format(Locale.US, "%.1f", totalTime)} сек!\nСохранено в Загрузки:\n$savedPath"
        btnRender.isEnabled = true
    }

    private fun drainEncoder(
        encoder: MediaCodec,
        muxer: MediaMuxer,
        bufferInfo: MediaCodec.BufferInfo,
        endOfStream: Boolean,
        getTrack: () -> Int,
        setTrack: (Int) -> Unit
    ) {
        if (endOfStream) encoder.signalEndOfInputStream()
        while (true) {
            val outIndex = encoder.dequeueOutputBuffer(bufferInfo, if (endOfStream) 10_000L else 0L)
            if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break
            } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val trackIdx = muxer.addTrack(encoder.outputFormat)
                muxer.start()
                setTrack(trackIdx)
            } else if (outIndex >= 0) {
                val encodedData = encoder.getOutputBuffer(outIndex) ?: continue
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    bufferInfo.size = 0
                }
                if (bufferInfo.size != 0 && getTrack() != -1) {
                    encodedData.position(bufferInfo.offset)
                    encodedData.limit(bufferInfo.offset + bufferInfo.size)
                    bufferInfo.presentationTimeUs = System.nanoTime() / 1000L
                    muxer.writeSampleData(getTrack(), encodedData, bufferInfo)
                }
                encoder.releaseOutputBuffer(outIndex, false)
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }
    }

    // Аппаратное перекодирование MP3 -> AAC и склейка с нашим H.264 видео в MP4
    private fun muxVideoAndAudio(videoFile: File, audioFile: File, outputFile: File, maxDurationUs: Long) {
        val videoExtractor = MediaExtractor().apply { setDataSource(videoFile.absolutePath) }
        val audioExtractor = MediaExtractor().apply { setDataSource(audioFile.absolutePath) }

        var videoTrackIn = -1
        for (i in 0 until videoExtractor.trackCount) {
            val fmt = videoExtractor.getTrackFormat(i)
            if (fmt.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                videoTrackIn = i; break
            }
        }

        var audioTrackIn = -1
        var audioInFormat: MediaFormat? = null
        for (i in 0 until audioExtractor.trackCount) {
            val fmt = audioExtractor.getTrackFormat(i)
            if (fmt.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                audioTrackIn = i; audioInFormat = fmt; break
            }
        }

        if (videoTrackIn == -1 || audioTrackIn == -1 || audioInFormat == null) {
            videoFile.copyTo(outputFile, overwrite = true)
            return
        }

        videoExtractor.selectTrack(videoTrackIn)
        audioExtractor.selectTrack(audioTrackIn)

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val outVideoTrack = muxer.addTrack(videoExtractor.getTrackFormat(videoTrackIn))

        val sampleRate = audioInFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channelCount = audioInFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val inMime = audioInFormat.getString(MediaFormat.KEY_MIME) ?: "audio/mpeg"

        // Настраиваем декодер MP3 и энкодер AAC для совместимости с MP4
        val decoder = MediaCodec.createDecoderByType(inMime)
        decoder.configure(audioInFormat, null, null, 0)
        decoder.start()

        val aacFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        encoder.configure(aacFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        var outAudioTrack = -1
        var muxerStarted = false
        val info = MediaCodec.BufferInfo()

        var inputDone = false
        var decodeDone = false
        var encodeDone = false

        while (!encodeDone) {
            if (!inputDone) {
                val inIdx = decoder.dequeueInputBuffer(5000)
                if (inIdx >= 0) {
                    val buf = decoder.getInputBuffer(inIdx)!!
                    val sampleSize = audioExtractor.readSampleData(buf, 0)
                    val sampleTime = audioExtractor.sampleTime
                    if (sampleSize < 0 || sampleTime > maxDurationUs) {
                        decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(inIdx, 0, sampleSize, sampleTime, 0)
                        audioExtractor.advance()
                    }
                }
            }

            if (!decodeDone) {
                val outIdx = decoder.dequeueOutputBuffer(info, 5000)
                if (outIdx >= 0) {
                    val decBuf = decoder.getOutputBuffer(outIdx)
                    val encInIdx = encoder.dequeueInputBuffer(5000)
                    if (encInIdx >= 0) {
                        val encInBuf = encoder.getInputBuffer(encInIdx)!!
                        encInBuf.clear()
                        if (decBuf != null && info.size > 0) {
                            decBuf.position(info.offset)
                            decBuf.limit(info.offset + info.size)
                            encInBuf.put(decBuf)
                        }
                        val flags = if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                        encoder.queueInputBuffer(encInIdx, 0, info.size, info.presentationTimeUs, flags)
                        decoder.releaseOutputBuffer(outIdx, false)
                        if (flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) decodeDone = true
                    }
                }
            }

            val encOutIdx = encoder.dequeueOutputBuffer(info, 5000)
            if (encOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                outAudioTrack = muxer.addTrack(encoder.outputFormat)
                muxer.start()
                muxerStarted = true

                // Как только muxer стартовал, переносим все видеокадры с правильным таймингом 30 FPS
                val vBuf = ByteBuffer.allocate(2 * 1024 * 1024)
                val vInfo = MediaCodec.BufferInfo()
                var frameNum = 0L
                while (true) {
                    val sz = videoExtractor.readSampleData(vBuf, 0)
                    if (sz < 0) break
                    vInfo.offset = 0
                    vInfo.size = sz
                    vInfo.presentationTimeUs = (frameNum * 1_000_000L) / FPS
                    vInfo.flags = videoExtractor.sampleFlags
                    muxer.writeSampleData(outVideoTrack, vBuf, vInfo)
                    videoExtractor.advance()
                    frameNum++
                }
            } else if (encOutIdx >= 0) {
                val encOutBuf = encoder.getOutputBuffer(encOutIdx)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                if (info.size > 0 && muxerStarted && encOutBuf != null) {
                    encOutBuf.position(info.offset)
                    encOutBuf.limit(info.offset + info.size)
                    muxer.writeSampleData(outAudioTrack, encOutBuf, info)
                }
                encoder.releaseOutputBuffer(encOutIdx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encodeDone = true
            }
        }

        decoder.stop(); decoder.release()
        encoder.stop(); encoder.release()
        if (muxerStarted) muxer.stop()
        muxer.release()
        videoExtractor.release()
        audioExtractor.release()
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
