package id.enhancer.video

import android.content.Context
import android.opengl.GLES20
import android.util.Size
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.SingleFrameGlShaderProgram
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Efek GPU: denoise (spasial + temporal) -> sharpen -> night view (kurva Zero-DCE) -> saturasi. */
class EnhanceEffect(private val params: EnhanceParams) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        EnhanceProgram(params, useHdr)
}

private class EnhanceProgram(
    private val p: EnhanceParams,
    useHdr: Boolean,
) : SingleFrameGlShaderProgram(useHdr) {

    private var program = 0
    private var histTex = 0
    private var w = 0
    private var h = 0
    private var hasPrev = false

    private val quad: FloatBuffer = ByteBuffer.allocateDirect(16 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 0f, 1f, 1f, -1f, 0f, 1f, -1f, 1f, 0f, 1f, 1f, 1f, 0f, 1f))
            position(0)
        }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        if (program == 0) program = link(VERT, FRAG)
        if (inputWidth != w || inputHeight != h) {
            w = inputWidth
            h = inputHeight
            if (histTex != 0) GLES20.glDeleteTextures(1, intArrayOf(histTex), 0)
            val t = IntArray(1)
            GLES20.glGenTextures(1, t, 0)
            histTex = t[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, histTex)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
            )
            hasPrev = false
        }
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexId)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, histTex)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

        fun u(name: String) = GLES20.glGetUniformLocation(program, name)
        GLES20.glUniform1i(u("uTex"), 0)
        GLES20.glUniform1i(u("uPrev"), 1)
        GLES20.glUniform2f(u("uTexel"), 1f / w, 1f / h)
        GLES20.glUniform1f(u("uDenoise"), p.denoise)
        GLES20.glUniform1f(u("uNight"), p.night)
        GLES20.glUniform1f(u("uSharpen"), p.sharpen)
        GLES20.glUniform1f(u("uSat"), p.sat)
        GLES20.glUniform1f(u("uTemporal"), p.temporal)
        GLES20.glUniform1f(u("uMaxGain"), p.maxGain)
        GLES20.glUniform1f(u("uHasPrev"), if (hasPrev) 1f else 0f)

        val pos = GLES20.glGetAttribLocation(program, "aFramePosition")
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glVertexAttribPointer(pos, 4, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(pos)

        // Simpan hasil frame ini sebagai referensi untuk filter temporal frame berikutnya.
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, histTex)
        GLES20.glCopyTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        hasPrev = true
    }

    override fun release() {
        if (program != 0) GLES20.glDeleteProgram(program)
        if (histTex != 0) GLES20.glDeleteTextures(1, intArrayOf(histTex), 0)
        program = 0
        histTex = 0
        super.release()
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(s)
            GLES20.glDeleteShader(s)
            error("Shader gagal dikompilasi: $log")
        }
        return s
    }

    private fun link(vs: String, fs: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        val pr = GLES20.glCreateProgram()
        GLES20.glAttachShader(pr, v)
        GLES20.glAttachShader(pr, f)
        GLES20.glLinkProgram(pr)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(pr, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(pr)
            GLES20.glDeleteProgram(pr)
            error("Shader gagal di-link: $log")
        }
        GLES20.glDeleteShader(v)
        GLES20.glDeleteShader(f)
        return pr
    }

    private companion object {
        const val VERT = """
attribute vec4 aFramePosition;
varying vec2 vUV;
void main() {
  gl_Position = aFramePosition;
  vUV = aFramePosition.xy * 0.5 + 0.5;
}
"""

        const val FRAG = """
precision highp float;
varying vec2 vUV;
uniform sampler2D uTex;
uniform sampler2D uPrev;
uniform vec2 uTexel;
uniform float uDenoise;
uniform float uNight;
uniform float uSharpen;
uniform float uSat;
uniform float uTemporal;
uniform float uMaxGain;
uniform float uHasPrev;

float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

// Kurva Zero-DCE: LE(x) = x + a * x * (1 - x), diulang 4 kali.
float curveL(float l) {
  float x = l;
  for (int i = 0; i < 4; i++) { x = x + uNight * x * (1.0 - x); }
  return x;
}

void main() {
  vec3 c0 = texture2D(uTex, vUV).rgb;
  float l0 = luma(c0);

  // Area gelap akan diperkuat -> noise di sana harus dibersihkan lebih keras.
  float g0 = min(curveL(l0) / max(l0, 0.02), uMaxGain);
  float dark = 1.0 - smoothstep(0.0, 0.45, l0);
  float sigma = (0.010 + 0.055 * uDenoise) * (1.0 + 0.8 * dark) * (1.0 + 0.1 * g0);
  float radius = 1.5 + 2.5 * uDenoise;
  float wOn = step(0.001, uDenoise);

  // Bilateral denoise: 16 sampel pola Vogel, bobot berdasarkan selisih warna.
  vec3 acc = c0;
  float wsum = 1.0;
  float lsum = l0;
  float lmin = l0;
  float lmax = l0;
  for (int i = 0; i < 16; i++) {
    float fi = float(i);
    float a = fi * 2.39996323;
    float rr = sqrt((fi + 0.5) / 16.0);
    vec2 o = vec2(cos(a), sin(a)) * rr * radius * uTexel;
    vec3 s = texture2D(uTex, vUV + o).rgb;
    vec3 d = s - c0;
    float w = wOn * exp(-dot(d, d) / (2.0 * sigma * sigma)) * (1.0 - 0.5 * rr);
    acc += s * w;
    wsum += w;
    float ls = luma(s);
    lsum += ls;
    lmin = min(lmin, ls);
    lmax = max(lmax, ls);
  }
  vec3 cd = acc / wsum;
  float ld = luma(cd);

  // Sharpen: unsharp mask dengan coring (abaikan detail sekecil noise) dan batas halo.
  float lblur = lsum / 17.0;
  float detail = ld - lblur;
  float coring = 0.006 + 0.012 * uDenoise;
  detail *= smoothstep(coring, coring * 3.0, abs(detail));
  float lsharp = clamp(ld + uSharpen * 1.6 * detail, lmin, lmax);
  vec3 res = max(cd + (lsharp - ld), 0.0);

  // Night view: angkat bayangan dengan menjaga warna.
  float lr = luma(res);
  float gain = min(curveL(lr) / max(lr, 0.015), uMaxGain);
  res *= gain;
  res /= max(max(res.r, max(res.g, res.b)), 1.0);

  float ly = luma(res);
  res = clamp(mix(vec3(ly), res, 1.0 + uSat), 0.0, 1.0);

  // Denoise temporal: area yang diam dirata-ratakan dengan frame sebelumnya.
  if (uHasPrev > 0.5) {
    vec3 prev = texture2D(uPrev, vUV).rgb;
    float dd = length(res - prev);
    float k = uTemporal * (1.0 - smoothstep(0.02 + 0.04 * uDenoise, 0.10 + 0.08 * uDenoise, dd));
    res = mix(res, prev, k);
  }

  gl_FragColor = vec4(res, 1.0);
}
"""
    }
}
