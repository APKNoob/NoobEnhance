package id.enhancer.video

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.DragEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat

class MainActivity : ComponentActivity() {

    private val selected = mutableStateOf<Uri?>(null)

    private val picker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) select(uri)
    }
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Seret & lepas video dari aplikasi lain (mode layar terpisah / tablet / DeX).
        window.decorView.setOnDragListener { _, e -> onDrag(e) }
        handleShare(intent)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            EnhancerTheme {
                Root(
                    selected = selected.value,
                    onPick = {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                    },
                    onStart = { start(it) },
                    onCancel = { cancelJob() },
                    onReset = {
                        selected.value = null
                        EnhanceState.phase.value = Phase.Idle
                    },
                    onOpen = { openResult(it) },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun onDrag(e: DragEvent): Boolean = when (e.action) {
        DragEvent.ACTION_DRAG_STARTED -> e.clipDescription?.hasMimeType("video/*") == true
        DragEvent.ACTION_DROP -> {
            val uri = e.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            if (uri != null) {
                requestDragAndDropPermissions(e)
                select(uri)
                true
            } else false
        }
        else -> true
    }

    private fun handleShare(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND && i.type?.startsWith("video/") == true) {
            IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java)?.let { select(it) }
        }
    }

    private fun select(uri: Uri) {
        if (EnhanceState.busy()) return
        selected.value = uri
        EnhanceState.phase.value = Phase.Idle
    }

    private fun start(o: Options) {
        val uri = selected.value ?: return
        val i = Intent(this, EnhanceService::class.java).apply {
            data = uri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra("noise", o.noise)
            putExtra("night", o.night)
            putExtra("sharpen", o.sharpen)
        }
        EnhanceState.phase.value = Phase.Analyzing
        ContextCompat.startForegroundService(this, i)
    }

    private fun cancelJob() {
        startService(Intent(this, EnhanceService::class.java).setAction(EnhanceService.ACTION_CANCEL))
    }

    private fun openResult(uri: Uri) {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "video/mp4")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
        } catch (e: ActivityNotFoundException) {
            // Tidak ada pemutar video; hasil tetap ada di Galeri.
        }
    }
}
