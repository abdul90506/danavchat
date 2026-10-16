package com.danav.chat.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.annotation.OptIn
import androidx.exifinterface.media.ExifInterface
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt

data class Prepared(val file: File, val mime: String, val w: Int = 0, val h: Int = 0, val duration: Long = 0, val name: String? = null)

object MediaUtils {
    const val MAX_IMAGE_BYTES = 10L * 1024 * 1024
    const val MAX_VIDEO_BYTES = 100L * 1024 * 1024
    const val MAX_FILE_BYTES = 10L * 1024 * 1024

    fun outDir(ctx: Context): File = File(ctx.filesDir, "media").apply { mkdirs() }

    fun queryName(ctx: Context, uri: Uri): Pair<String, Long> {
        var name = "file"; var size = 0L
        runCatching {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0) name = c.getString(ni) ?: name
                    if (si >= 0) size = c.getLong(si)
                }
            }
        }
        return name to size
    }

    /** Resize to max 1600px, JPEG 80% — like WhatsApp. */
    suspend fun prepareImage(ctx: Context, uri: Uri, maxSide: Int = 1600): Prepared = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IllegalStateException("Cannot read image")
        val rotation = runCatching {
            ctx.contentResolver.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        }.getOrDefault(0f)
        val scale = minOf(1f, maxSide.toFloat() / max(bmp.width, bmp.height))
        if (rotation != 0f || scale < 1f) {
            val m = Matrix().apply { postScale(scale, scale); postRotate(rotation) }
            val nb = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (nb != bmp) bmp.recycle()
            bmp = nb
        }
        val out = File(outDir(ctx), "IMG_${UUID.randomUUID()}.jpg")
        FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        val p = Prepared(out, "image/jpeg", bmp.width, bmp.height)
        bmp.recycle()
        p
    }

    /** Center-square crop for profile photos, preserving a clean circular-avatar source. */
    suspend fun prepareAvatar(ctx: Context, uri: Uri): Prepared {
        val p = prepareImage(ctx, uri, 900)
        return withContext(Dispatchers.IO) {
            val src = BitmapFactory.decodeFile(p.file.absolutePath) ?: return@withContext p
            val side = minOf(src.width, src.height)
            val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
            FileOutputStream(p.file).use { square.compress(Bitmap.CompressFormat.JPEG, 86, it) }
            src.recycle(); if (square !== src) square.recycle()
            p.copy(w = side, h = side)
        }
    }

    fun videoInfo(path: String): Triple<Int, Int, Long> {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(path)
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
            val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            Triple(w, h, d)
        } catch (e: Exception) { Triple(0, 0, 0L) } finally { runCatching { r.release() } }
    }

    private suspend fun copyUri(ctx: Context, uri: Uri, ext: String): File = withContext(Dispatchers.IO) {
        val f = File(outDir(ctx), "${UUID.randomUUID()}.$ext")
        ctx.contentResolver.openInputStream(uri)?.use { i -> FileOutputStream(f).use { o -> i.copyTo(o) } }
            ?: throw IllegalStateException("Cannot read file")
        f
    }

    /** Transcode to ~720p H.264 so videos upload fast and stay under the free 100 MB limit. */
    @OptIn(UnstableApi::class)
    suspend fun prepareVideo(ctx: Context, uri: Uri): Prepared {
        val src = copyUri(ctx, uri, "mp4")
        val (w, h, dur) = videoInfo(src.absolutePath)
        val short = minOf(w, h)
        if ((short in 1..720 && src.length() < 16L * 1024 * 1024) || w == 0) {
            return Prepared(src, "video/mp4", w, h, dur)
        }
        val scale = 720f / short
        val nw = ((w * scale) / 2).roundToInt() * 2
        val nh = ((h * scale) / 2).roundToInt() * 2
        val out = File(outDir(ctx), "VID_${UUID.randomUUID()}.mp4")
        val ok = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val transformer = Transformer.Builder(ctx)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) { if (cont.isActive) cont.resume(true) }
                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            if (cont.isActive) cont.resume(false)
                        }
                    }).build()
                val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(src)))
                    .setEffects(Effects(emptyList(), listOf(Presentation.createForWidthAndHeight(nw, nh, Presentation.LAYOUT_SCALE_TO_FIT))))
                    .build()
                transformer.start(item, out.absolutePath)
                cont.invokeOnCancellation { runCatching { transformer.cancel() } }
            }
        }
        return if (ok && out.length() > 0 && out.length() < src.length()) {
            src.delete()
            val (ow, oh, od) = videoInfo(out.absolutePath)
            Prepared(out, "video/mp4", ow, oh, if (od > 0) od else dur)
        } else {
            out.delete(); Prepared(src, "video/mp4", w, h, dur)
        }
    }

    suspend fun prepareFile(ctx: Context, uri: Uri): Prepared {
        val (name, _) = queryName(ctx, uri)
        val ext = name.substringAfterLast('.', "bin").take(8)
        val f = copyUri(ctx, uri, ext)
        val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
        return Prepared(f, mime, name = name)
    }
}

/** Voice note recorder (AAC/M4A) with live amplitude for the waveform. */
class VoiceRecorder(private val ctx: Context) {
    private var rec: MediaRecorder? = null
    var file: File? = null; private set
    private var startedAt = 0L
    private val samples = ArrayList<Int>()
    val amplitude = MutableStateFlow(0f)
    val elapsed = MutableStateFlow(0L)
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun start(): Boolean = try {
        val f = File(MediaUtils.outDir(ctx), "PTT_${UUID.randomUUID()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioChannels(1)
        r.setAudioSamplingRate(44100)
        r.setAudioEncodingBitRate(64000)
        r.setOutputFile(f.absolutePath)
        r.prepare(); r.start()
        rec = r; file = f; startedAt = System.currentTimeMillis(); samples.clear()
        job = scope.launch {
            while (isActive) {
                val a = runCatching { r.maxAmplitude }.getOrDefault(0)
                val norm = (a / 32767f).coerceIn(0f, 1f)
                amplitude.value = norm
                samples.add((norm * 100).roundToInt())
                elapsed.value = System.currentTimeMillis() - startedAt
                delay(80)
            }
        }
        true
    } catch (e: Exception) { release(); false }

    /** Stop. Returns (file, durationMs, waveform[48]) or null if too short / failed. */
    fun stop(): Triple<File, Long, List<Int>>? {
        val dur = System.currentTimeMillis() - startedAt
        val ok = runCatching { rec?.stop() }.isSuccess
        release()
        val f = file ?: return null
        if (!ok || dur < 800) { f.delete(); return null }
        return Triple(f, dur, downsample(samples, 48))
    }

    fun cancel() { runCatching { rec?.stop() }; release(); file?.delete() }

    private fun release() { job?.cancel(); runCatching { rec?.release() }; rec = null; amplitude.value = 0f }

    private fun downsample(src: List<Int>, n: Int): List<Int> {
        if (src.isEmpty()) return List(n) { 5 }
        return List(n) { i ->
            val from = i * src.size / n; val to = max(from + 1, (i + 1) * src.size / n)
            val peak = src.subList(from, minOf(to, src.size)).maxOrNull() ?: 0
            (peak * 1.6f).roundToInt().coerceIn(4, 100)
        }
    }
}

/** App-wide single audio player for voice notes. */
object AudioPlayer {
    val playingId = MutableStateFlow<String?>(null)
    val progress = MutableStateFlow(0f)
    val position = MutableStateFlow(0L)
    val isPlaying = MutableStateFlow(false)
    private var mp: MediaPlayer? = null
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun toggle(id: String, source: String, seekFraction: Float? = null) {
        if (playingId.value == id && mp != null) {
            val p = mp!!
            if (seekFraction != null) { p.seekTo((p.duration * seekFraction).toInt()); return }
            if (p.isPlaying) { p.pause(); isPlaying.value = false } else { p.start(); isPlaying.value = true; tick() }
            return
        }
        stop()
        playingId.value = id
        progress.value = 0f
        val p = MediaPlayer()
        mp = p
        runCatching {
            p.setDataSource(source)
            p.setOnPreparedListener {
                if (seekFraction != null) it.seekTo((it.duration * seekFraction).toInt())
                it.start(); isPlaying.value = true; tick()
            }
            p.setOnCompletionListener { stop() }
            p.setOnErrorListener { _, _, _ -> stop(); true }
            p.prepareAsync()
        }.onFailure { stop() }
    }

    fun seek(id: String, fraction: Float) {
        if (playingId.value == id) mp?.let { it.seekTo((it.duration * fraction).toInt()); progress.value = fraction }
    }

    private fun tick() {
        job?.cancel()
        job = scope.launch {
            while (isActive) {
                val p = mp ?: break
                runCatching {
                    if (p.duration > 0) progress.value = p.currentPosition.toFloat() / p.duration
                    position.value = p.currentPosition.toLong()
                }
                delay(50)
            }
        }
    }

    fun stop() {
        job?.cancel()
        mp?.let { runCatching { it.stop() }; it.release() }
        mp = null
        playingId.value = null; isPlaying.value = false; progress.value = 0f; position.value = 0
    }
}
