package id.enhancer.video

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Foreground service: video panjang tetap diproses saat layar mati / aplikasi di latar belakang.
 * Pipeline: decode (hardware) -> EnhanceEffect (GPU) -> encode HEVC (hardware) -> simpan ke Galeri.
 */
class EnhanceService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private val holder = ProgressHolder()
    private val nm by lazy { NotificationManagerCompat.from(this) }

    private var job: Job? = null
    private var transformer: Transformer? = null
    private var tmp: File? = null
    private var wake: PowerManager.WakeLock? = null
    private var t0 = 0L
    private var lastPercent = -1
    private var displayName = "video"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        ServiceCompat.startForeground(
            this, NOTIF_ID, notification("Menyiapkan…", -1),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        if (intent?.action == ACTION_CANCEL) {
            cancelAll()
            return START_NOT_STICKY
        }
        val uri = intent?.data
        if (uri == null || job?.isActive == true) {
            if (job?.isActive != true) stopSelf()
            return START_NOT_STICKY
        }
        val opts = Options(
            noise = intent?.getBooleanExtra("noise", true) ?: true,
            night = intent?.getBooleanExtra("night", true) ?: true,
            sharpen = intent?.getBooleanExtra("sharpen", true) ?: true,
        )
        job = scope.launch { process(uri, opts) }
        return START_NOT_STICKY
    }

    private suspend fun process(uri: Uri, o: Options) {
        try {
            EnhanceState.phase.value = Phase.Analyzing
            wake = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "enhancer:work")
                .apply { acquire(12 * 3600_000L) }

            val (info, params) = withContext(Dispatchers.IO) {
                val i = VideoTools.info(this@EnhanceService, uri)
                i to VideoTools.analyze(this@EnhanceService, uri, i, o)
            }
            displayName = info.name

            val bitrate = targetBitrate(info)
            val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: cacheDir
            dir.mkdirs()
            val estimate = bitrate.toLong() / 8 * (info.durationMs / 1000)
            val free = StatFs(dir.path).availableBytes
            if (estimate > 0 && free < estimate * 21 / 10) {
                fail("Ruang penyimpanan kurang. Butuh sekitar ${gb(estimate * 21 / 10)} GB kosong, tersedia ${gb(free)} GB.")
                return
            }
            val out = File(dir, "enhancing.mp4").also { it.delete(); tmp = it }
            startTransform(uri, out, params, bitrate)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(e.message ?: "Gagal memulai proses.")
        }
    }

    private fun startTransform(uri: Uri, out: File, params: EnhanceParams, bitrate: Int) {
        val encoder = DefaultEncoderFactory.Builder(this)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
            .setEnableFallback(true)
            .build()

        val t = Transformer.Builder(this)
            .setVideoMimeType(MimeTypes.VIDEO_H265)
            .setEncoderFactory(encoder)
            .addListener(listener)
            .build()
        transformer = t

        val item = EditedMediaItem.Builder(MediaItem.fromUri(uri))
            .setEffects(Effects(emptyList<AudioProcessor>(), listOf<Effect>(EnhanceEffect(params))))
            .build()
        val composition = Composition.Builder(EditedMediaItemSequence.Builder(item).build())
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()

        t0 = SystemClock.elapsedRealtime()
        lastPercent = -1
        EnhanceState.phase.value = Phase.Processing(0, -1)
        t.start(composition, out.absolutePath)
        handler.post(poller)
    }

    private val listener = object : Transformer.Listener {
        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
            onExported()
        }

        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
            fail("Proses gagal: ${exportException.message ?: exportException.errorCodeName}")
        }
    }

    private val poller = object : Runnable {
        override fun run() {
            val t = transformer ?: return
            if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                val p = holder.progress.coerceIn(0, 99)
                if (p != lastPercent) {
                    lastPercent = p
                    val elapsed = (SystemClock.elapsedRealtime() - t0) / 1000
                    val eta = if (p >= 2) elapsed * (100 - p) / p else -1L
                    EnhanceState.phase.value = Phase.Processing(p, eta)
                    nm.notify(NOTIF_ID, notification("Memproses $p%", p))
                }
            }
            handler.postDelayed(this, 700)
        }
    }

    private fun onExported() {
        handler.removeCallbacks(poller)
        transformer = null
        val src = tmp ?: return
        scope.launch {
            try {
                EnhanceState.phase.value = Phase.Saving
                val saved = withContext(Dispatchers.IO) { saveToGallery(src) }
                EnhanceState.phase.value = Phase.Done(saved.first, saved.second)
                finish("Selesai. Video tersimpan di Galeri.")
            } catch (e: Exception) {
                fail("Gagal menyimpan ke Galeri: ${e.message}")
            } finally {
                src.delete()
            }
        }
    }

    private fun saveToGallery(src: File): Pair<Uri, Long> {
        val base = displayName.substringBeforeLast('.', displayName)
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "${base}_enhanced.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/VideoEnhancer")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = contentResolver
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: error("MediaStore menolak membuat berkas")
        try {
            resolver.openOutputStream(uri)!!.use { out ->
                src.inputStream().use { it.copyTo(out, 1 shl 20) }
            }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri to src.length()
    }

    private fun targetBitrate(i: VideoInfo): Int {
        val base = if (i.bitrate > 0) i.bitrate.toLong()
        else (i.width.toLong() * i.height * 30 * 0.12).toLong()
        return (base * 1.2).toLong().coerceIn(4_000_000L, 200_000_000L).toInt()
    }

    private fun gb(bytes: Long) = "%.1f".format(bytes / 1_000_000_000.0)

    private fun fail(msg: String) {
        handler.removeCallbacks(poller)
        transformer = null
        tmp?.delete()
        EnhanceState.phase.value = Phase.Failed(msg)
        finish(msg)
    }

    private fun cancelAll() {
        job?.cancel()
        handler.removeCallbacks(poller)
        transformer?.cancel()
        transformer = null
        tmp?.delete()
        EnhanceState.phase.value = Phase.Idle
        releaseWake()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun finish(text: String) {
        releaseWake()
        stopForeground(STOP_FOREGROUND_REMOVE)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        nm.notify(
            NOTIF_ID + 1,
            NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Video Enhancer")
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
        stopSelf()
    }

    private fun releaseWake() {
        wake?.takeIf { it.isHeld }?.release()
        wake = null
    }

    private fun ensureChannel() {
        val m = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (m.getNotificationChannel(CHANNEL) == null) {
            m.createNotificationChannel(
                NotificationChannel(CHANNEL, "Proses video", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun notification(text: String, percent: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, EnhanceService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Memperbaiki video")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Batalkan", cancel)
            .apply { if (percent >= 0) setProgress(100, percent, false) else setProgress(0, 0, true) }
            .build()
    }

    override fun onDestroy() {
        handler.removeCallbacks(poller)
        transformer?.cancel()
        releaseWake()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_CANCEL = "id.enhancer.video.CANCEL"
        private const val CHANNEL = "enhance"
        private const val NOTIF_ID = 42
    }
}
