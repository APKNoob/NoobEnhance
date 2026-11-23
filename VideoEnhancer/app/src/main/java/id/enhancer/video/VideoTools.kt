package id.enhancer.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt

data class VideoInfo(
    val name: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val bitrate: Int,
)

object VideoTools {

    fun info(ctx: Context, uri: Uri): VideoInfo {
        var name = "video"
        var size = 0L
        ctx.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null,
        )?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            fun num(k: Int) = r.extractMetadata(k)?.toLongOrNull() ?: 0L
            var w = num(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH).toInt()
            var h = num(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT).toInt()
            val rot = num(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION).toInt()
            if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
            return VideoInfo(
                name, size,
                num(MediaMetadataRetriever.METADATA_KEY_DURATION), w, h,
                num(MediaMetadataRetriever.METADATA_KEY_BITRATE).toInt(),
            )
        } finally {
            r.release()
        }
    }

    fun thumbnail(ctx: Context, uri: Uri, info: VideoInfo): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            val full = r.getFrameAtTime(info.durationMs * 100L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return null
            val s = 720f / maxOf(full.width, full.height)
            if (s >= 1f) full
            else Bitmap.createScaledBitmap(
                full, (full.width * s).toInt().coerceAtLeast(1), (full.height * s).toInt().coerceAtLeast(1), true,
            ).also { full.recycle() }
        } catch (e: Throwable) {
            null
        } finally {
            r.release()
        }
    }

    /**
     * Mengambil 12 frame sampel dari seluruh durasi, mengukur kecerahan (median) dan tingkat noise,
     * lalu menurunkan parameter enhance yang cocok untuk video ini.
     */
    fun analyze(ctx: Context, uri: Uri, info: VideoInfo, o: Options): EnhanceParams {
        var median = 0.40f
        var noise = 0.01f
        try {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(ctx, uri)
                val n = 12
                val meds = ArrayList<Float>()
                val noises = ArrayList<Float>()
                for (i in 0 until n) {
                    val t = info.durationMs * 1000L * (2 * i + 1) / (2 * n)
                    val bmp = r.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 640, 360)
                        ?: continue
                    val (m, s) = frameStats(bmp)
                    bmp.recycle()
                    meds += m
                    noises += s
                }
                if (meds.isNotEmpty()) {
                    median = meds.average().toFloat()
                    noise = noises.average().toFloat()
                }
            } finally {
                r.release()
            }
        } catch (e: Throwable) {
            // Analisis gagal: pakai nilai default yang aman.
        }

        val nightRaw = ((0.45f - median) / 0.33f).coerceIn(0f, 1f)
        val denoise = if (o.noise) (0.30f + noise * 12f + (1f - median) * 0.25f).coerceIn(0.3f, 1f) else 0f
        return EnhanceParams(
            denoise = denoise,
            night = if (o.night) 0.12f + 0.80f * nightRaw else 0f,
            sharpen = if (o.sharpen) (0.70f - noise * 6f).coerceIn(0.35f, 0.70f) else 0f,
            sat = if (o.night) 0.08f + 0.12f * nightRaw else 0f,
            temporal = 0.6f * denoise,
            maxGain = 6f,
        )
    }

    /** Mengembalikan (median luma 0..1, estimasi sigma noise metode Immerkaer). */
    private fun frameStats(b: Bitmap): Pair<Float, Float> {
        val w = b.width
        val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        val y = FloatArray(w * h)
        val hist = IntArray(256)
        for (i in px.indices) {
            val c = px[i]
            val l = (0.2126f * ((c shr 16) and 255) + 0.7152f * ((c shr 8) and 255) + 0.0722f * (c and 255)) / 255f
            y[i] = l
            hist[(l * 255f).toInt().coerceIn(0, 255)]++
        }
        var acc = 0
        var med = 0
        for (i in 0..255) {
            acc += hist[i]
            if (acc >= px.size / 2) { med = i; break }
        }
        var sum = 0.0
        for (j in 1 until h - 1) {
            for (i in 1 until w - 1) {
                val k = j * w + i
                val v = 4f * y[k] - 2f * (y[k - 1] + y[k + 1] + y[k - w] + y[k + w]) +
                    y[k - w - 1] + y[k - w + 1] + y[k + w - 1] + y[k + w + 1]
                sum += abs(v)
            }
        }
        val sigma = (sqrt(PI / 2.0) * sum / (6.0 * (w - 2) * (h - 2))).toFloat()
        return (med / 255f) to sigma
    }
}
