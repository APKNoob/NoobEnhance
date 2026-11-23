package id.enhancer.video

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow

sealed interface Phase {
    data object Idle : Phase
    data object Analyzing : Phase
    data class Processing(val percent: Int, val etaSec: Long) : Phase
    data object Saving : Phase
    data class Done(val uri: Uri, val sizeBytes: Long) : Phase
    data class Failed(val message: String) : Phase
}

/** Status tunggal yang dibagi antara service (pemroses) dan UI. */
object EnhanceState {
    val phase = MutableStateFlow<Phase>(Phase.Idle)

    fun busy(): Boolean = phase.value.let {
        it is Phase.Analyzing || it is Phase.Processing || it is Phase.Saving
    }
}

data class Options(
    val noise: Boolean = true,
    val night: Boolean = true,
    val sharpen: Boolean = true,
)

/** Parameter yang dihitung otomatis dari analisis tiap video, lalu dikirim ke shader GPU. */
data class EnhanceParams(
    val denoise: Float,    // 0..1 kekuatan pengurangan noise
    val night: Float,      // 0..0.95 kekuatan angkat bayangan (kurva Zero-DCE)
    val sharpen: Float,    // 0..1 ketajaman
    val sat: Float,        // tambahan saturasi
    val temporal: Float,   // 0..0.8 perataan antar-frame di area diam
    val maxGain: Float,    // batas penguatan agar noise gelap tidak meledak
)
