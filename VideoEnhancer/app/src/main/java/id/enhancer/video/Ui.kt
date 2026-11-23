package id.enhancer.video

import android.net.Uri
import android.text.format.Formatter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Ink = Color(0xFF0E1220)
private val Panel = Color(0xFF171C2E)
private val Panel2 = Color(0xFF212843)
private val Paper = Color(0xFFEEF1F8)
private val Muted = Color(0xFF8F98B3)
private val Amber = Color(0xFFFFB547)
private val Teal = Color(0xFF5ED3B0)
private val Rose = Color(0xFFFF8A8A)

@Composable
fun EnhancerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Amber, onPrimary = Ink,
            background = Ink, onBackground = Paper,
            surface = Panel, onSurface = Paper,
            surfaceVariant = Panel2, onSurfaceVariant = Muted,
            secondaryContainer = Amber, onSecondaryContainer = Ink,
            outline = Muted,
        ),
    ) {
        Surface(color = Ink, modifier = Modifier.fillMaxSize(), content = content)
    }
}

@Composable
fun Root(
    selected: Uri?,
    onPick: () -> Unit,
    onStart: (Options) -> Unit,
    onCancel: () -> Unit,
    onReset: () -> Unit,
    onOpen: (Uri) -> Unit,
) {
    val phase by EnhanceState.phase.collectAsState()
    var opts by remember { mutableStateOf(Options()) }

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Header()
        when (val p = phase) {
            is Phase.Analyzing -> Working(0, "Menganalisis video…", -1, true, onCancel)
            is Phase.Processing -> Working(p.percent, "Memperbaiki setiap frame", p.etaSec, false, onCancel)
            is Phase.Saving -> Working(100, "Menyimpan ke Galeri…", -1, false, null)
            is Phase.Done -> DoneCard(p, onOpen, onReset)
            else -> {
                if (p is Phase.Failed) ErrorCard(p.message)
                if (selected == null) DropZone(onPick)
                else Preview(selected, opts, { opts = it }, onPick) { onStart(opts) }
            }
        }
    }
}

@Composable
private fun Header() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Video Enhancer", color = Amber, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text(
            "Video gelap jadi terang, noise hilang, detail tajam.",
            color = Paper, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium,
            fontSize = 30.sp, lineHeight = 36.sp,
        )
        Text(
            "Tanpa batas durasi, resolusi, atau ukuran. Hasil langsung masuk Galeri.",
            color = Muted, fontSize = 15.sp, lineHeight = 21.sp,
        )
    }
}

/** Satu-satunya elemen "berani": sisi kiri gelap berbintik noise, sisi kanan sudah diperbaiki. */
@Composable
private fun DropZone(onClick: () -> Unit) {
    val dots = remember {
        val r = java.util.Random(7)
        List(900) { Triple(r.nextFloat(), r.nextFloat(), r.nextFloat()) }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(280.dp)
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onClick),
    ) {
        Canvas(Modifier.matchParentSize()) {
            val split = size.width * 0.55f
            drawRect(Color(0xFF080B15), size = Size(split, size.height))
            dots.forEach { (a, b, c) ->
                drawCircle(
                    Color.White.copy(alpha = 0.04f + c * 0.12f), radius = 1.6f,
                    center = Offset(a * split, b * size.height),
                )
            }
            drawRect(
                Brush.verticalGradient(listOf(Color(0xFF3B5A9A), Color(0xFFE9A04F))),
                topLeft = Offset(split, 0f), size = Size(size.width - split, size.height),
            )
            drawLine(Amber, Offset(split, 0f), Offset(split, size.height), strokeWidth = 3f)
            drawCircle(Amber, 14.dp.toPx(), Offset(split, size.height / 2))
            drawCircle(Ink, 5.dp.toPx(), Offset(split, size.height / 2))
        }
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth(0.5f).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Pilih video", color = Paper, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text("Ketuk di sini, atau seret video ke layar.", color = Muted, fontSize = 14.sp, lineHeight = 19.sp)
        }
    }
}

@Composable
private fun Preview(
    uri: Uri,
    opts: Options,
    onOpts: (Options) -> Unit,
    onPick: () -> Unit,
    onStart: () -> Unit,
) {
    val ctx = LocalContext.current
    var info by remember(uri) { mutableStateOf<VideoInfo?>(null) }
    var thumb by remember(uri) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(uri) {
        val r = withContext(Dispatchers.IO) {
            val i = runCatching { VideoTools.info(ctx, uri) }.getOrNull()
            i to i?.let { VideoTools.thumbnail(ctx, uri, it) }
        }
        info = r.first
        thumb = r.second?.asImageBitmap()
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(
            Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(20.dp)).background(Panel),
        ) {
            thumb?.let {
                Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
        info?.let { i ->
            Text(i.name, color = Paper, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat("Resolusi", "${i.width}×${i.height}", Modifier.weight(1f))
                Stat("Durasi", fmtDuration(i.durationMs), Modifier.weight(1f))
                Stat("Ukuran", Formatter.formatShortFileSize(ctx, i.sizeBytes), Modifier.weight(1f))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Kurangi noise", opts.noise, Modifier.weight(1f)) { onOpts(opts.copy(noise = it)) }
            Chip("Mode malam", opts.night, Modifier.weight(1f)) { onOpts(opts.copy(night = it)) }
            Chip("Pertajam", opts.sharpen, Modifier.weight(1f)) { onOpts(opts.copy(sharpen = it)) }
        }
        Button(
            onClick = onStart,
            enabled = opts.noise || opts.night || opts.sharpen,
            modifier = Modifier.fillMaxWidth().height(58.dp),
            shape = RoundedCornerShape(16.dp),
        ) { Text("Proses video", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
        TextButton(onClick = onPick, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Ganti video", color = Muted)
        }
    }
}

@Composable
private fun Chip(label: String, on: Boolean, modifier: Modifier, onChange: (Boolean) -> Unit) {
    FilterChip(
        selected = on,
        onClick = { onChange(!on) },
        label = { Text(label, fontSize = 13.sp, maxLines = 1) },
        modifier = modifier,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Panel, labelColor = Muted,
            selectedContainerColor = Amber, selectedLabelColor = Ink,
        ),
    )
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(Panel).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, color = Muted, fontSize = 12.sp)
        Text(value, color = Paper, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun Working(percent: Int, status: String, eta: Long, indeterminate: Boolean, onCancel: (() -> Unit)?) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Panel).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (!indeterminate) {
            Text("$percent%", color = Paper, fontFamily = FontFamily.Serif, fontSize = 64.sp)
        }
        val bar = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
        if (indeterminate) LinearProgressIndicator(modifier = bar, color = Amber, trackColor = Panel2)
        else LinearProgressIndicator(progress = { percent / 100f }, modifier = bar, color = Amber, trackColor = Panel2)
        Text(status, color = Paper, fontSize = 16.sp)
        if (eta >= 0) Text("Sisa sekitar ${fmtEta(eta)}", color = Muted)
        Text(
            "Kamu boleh mengunci layar atau pindah aplikasi. Proses tetap berjalan di latar belakang.",
            color = Muted, fontSize = 14.sp, lineHeight = 20.sp,
        )
        if (onCancel != null) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Batalkan") }
        }
    }
}

@Composable
private fun DoneCard(p: Phase.Done, onOpen: (Uri) -> Unit, onReset: () -> Unit) {
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Panel).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Selesai", color = Teal, fontFamily = FontFamily.Serif, fontSize = 40.sp)
        Text(
            "Video tersimpan di Galeri, album Movies/VideoEnhancer (${Formatter.formatShortFileSize(ctx, p.sizeBytes)}).",
            color = Paper, fontSize = 16.sp, lineHeight = 22.sp,
        )
        Button(
            onClick = { onOpen(p.uri) },
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(16.dp),
        ) { Text("Buka video", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
        TextButton(onClick = onReset, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Proses video lain", color = Muted)
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Proses berhenti", color = Rose, fontWeight = FontWeight.SemiBold)
        Text(message, color = Paper, fontSize = 14.sp, lineHeight = 20.sp)
    }
}

private fun fmtDuration(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

private fun fmtEta(sec: Long): String = when {
    sec >= 3600 -> "${sec / 3600} jam ${(sec % 3600) / 60} menit"
    sec >= 60 -> "${sec / 60} menit"
    else -> "$sec detik"
}
